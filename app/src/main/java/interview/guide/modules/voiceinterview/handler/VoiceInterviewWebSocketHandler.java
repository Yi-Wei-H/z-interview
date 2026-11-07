package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import interview.guide.modules.user.interceptor.WsAuthHandshakeInterceptor;
import interview.guide.modules.voiceinterview.dto.WebSocketControlMessage;
import interview.guide.modules.voiceinterview.dto.WebSocketSubtitleMessage;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音面试 WebSocket 处理器（Voice Interview WebSocket Handler）
 * 语音面试 WebSocket 处理器
 * <p>
 * 处理语音面试的实时双向音频流。
 * 处理管线：用户音频 → STT → LLM → TTS → AI 音频
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    private final ObjectMapper objectMapper;
    private final QwenAsrService sttService;
    private final QwenTtsService ttsService;
    private final DashscopeLlmService llmService;
    private final VoiceInterviewService interviewService;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;// 监控指标

    /**
     * 合并多段 STT 定稿后再触发 LLM 的延迟调度（与 {@link VoiceInterviewProperties#getUserUtteranceDebounceMs()} 配合）
     */
    private final ScheduledExecutorService utteranceMergeScheduler = createUtteranceMergeScheduler();

    /**
     * LLM / TTS / JDBC 等阻塞工作全部跑在虚拟线程上，避免占满 utteranceMergeScheduler 的 2 个调度线程。
     */
    private final ExecutorService voicePipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    // k:开场白文本 v:预合成的 WAV 音频;用途：预热缓存，面试开始时直接发送，无需实时合成
    private final Map<String, byte[]> openingAudioCache = new ConcurrentHashMap<>();

    // 活动时间记录（用于暂停超时检测）
    // 活动跟踪（用于暂停超时）
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();
    // 4分30秒警告
    private static final long WARNING_TIME_MS = (long) (4.5 * 60 * 1000);  // 4:30
    // 5分钟超时
    private static final long PAUSE_TIMEOUT_MS = 5 * 60 * 1000;            // 5:00
    private static final int WS_SEND_TIME_LIMIT_MS = 10_000;
    // 发送缓冲区 512KB
    private static final int WS_SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    /** AI 音频播放结束后的冷却期，防止扬声器尾音被麦克风拾取触发 STT */
    // AI 播报后冷却 800ms
    private static final long AI_SPEAK_COOLDOWN_MS = 800;
    private static final int MAX_ASR_READY_RETRY = 2;
    // ASR 检查延迟
    private static final long ASR_READY_CHECK_DELAY_SECONDS = 10;
    private static final String DEFAULT_OPENING_QUESTION_ALGORITHM =
        "你好，我是本场面试官。第一个问题：请你口述一道算法题，不写代码，只讲\u300C问题建模、数据结构选型、步骤、复杂度、边界处理\u300D。";
    private static final String DEFAULT_OPENING_QUESTION_BACKEND =
        "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。";

    private static ScheduledExecutorService createUtteranceMergeScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "voice-utterance-merge");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);// 取消任务立即从队列移除
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);// 关闭时取消待执行任务
        return executor;
    }

    // 预合成开场白的音频并缓存
    @PostConstruct
    void warmupOpeningAudioCache() {
        voicePipelineExecutor.execute(() -> {
            try {
                VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
                if (opening == null) {
                    return;
                }
                LinkedHashSet<String> allTemplates = new LinkedHashSet<>();
                if (opening.getSkillQuestions() != null) {
                    allTemplates.addAll(opening.getSkillQuestions().values());
                }
                allTemplates.add(opening.getAlgorithmQuestion());
                allTemplates.add(opening.getBackendQuestion());
                for (String template : allTemplates) {
                    preloadOpeningAudio(template);
                }
                log.info("开场白音频缓存已预热: {} 条", openingAudioCache.size());
            } catch (Exception e) {
                log.warn("开场白音频缓存预热已跳过: {}", e.getMessage());
            }
        });
    }

    private void preloadOpeningAudio(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        byte[] wavAudio = synthesizeToWav(text);
        if (wavAudio.length > 0) {
            openingAudioCache.put(text, wavAudio);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);

        // 握手时已由 WsAuthHandshakeInterceptor 写入登录 userId，这里校验会话属主
        Long userId = (Long) session.getAttributes().get(WsAuthHandshakeInterceptor.USER_ID_ATTR);
        VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
        if (userId == null || sessionEntity == null || !userId.equals(sessionEntity.getUserId())) {
            log.warn("WebSocket 会话 {} 属主不匹配（userId={}, sessionId={}），正在关闭",
                    sessionId, userId, sessionEntity == null ? "not-found" : sessionEntity.getUserId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // 提高音频流的消息大小上限
        // 16kHz、16-bit 的 1 秒 PCM 音频原始约 32KB，Base64 编码后约 42KB
        // 上限设为 256KB，预留缓冲以容纳多条消息
        session.setTextMessageSizeLimit(256 * 1024); // 256KB；默认64KB，音频 Base64 编码后约 42KB/秒，需要更大缓冲
        session.setBinaryMessageSizeLimit(256 * 1024); // 256KB

        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
            session, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES
        );

        sessions.put(sessionId, safeSession);
        sessionStates.put(sessionId, new SessionState());
        lastActivityTime.put(sessionId, System.currentTimeMillis());
        log.info("会话 {} 的 WebSocket 连接已建立", sessionId);

        try {
            startDashScopeStt(sessionId, safeSession);

            // 发送欢迎消息
            sendMessage(safeSession, createWelcomeMessage());
            // 自动开场：面试官先说开场语并直接提出第一个问题（仅首次连接、无历史消息时触发）
            triggerOpeningQuestionIfNeeded(sessionId, safeSession);
        } catch (Exception e) {
            log.error("建立会话 {} 的 WebSocket 连接失败", sessionId, e);
            sendError(safeSession, "初始化语音识别失败: " + e.getMessage());
        }
    }

    /**
     * 创建欢迎消息
     */
    private String createWelcomeMessage() {
        return toJson(Map.of(
            "type", "control",
            "action", "welcome",
            "message", "连接成功，准备开始语音面试",
            "timestamp", System.currentTimeMillis()
        ));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            return "{}";
        }
    }

    /**
     * 向 WebSocket 会话发送消息
     */
    private void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
                log.debug("已发送消息到会话: {}", message.substring(0, Math.min(100, message.length())));
            } else {
                log.warn("会话已关闭，无法发送消息");
            }
        } catch (Exception e) {
            log.error("发送消息到会话出错", e);
        }
    }

    private void triggerOpeningQuestionIfNeeded(String sessionId, WebSocketSession session) {
        voicePipelineExecutor.execute(() -> {
            try {
                if (session == null || !session.isOpen()) {
                    return;
                }

                List<String> history = getHistory(sessionId);
                if (history != null && !history.isEmpty()) {
                    // 已有历史对话（如重连/恢复），不重复开场
                    return;
                }

                VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
                if (sessionEntity == null) {
                    log.warn("发送开场问题时会话实体不存在: sessionId={}", sessionId);
                    return;
                }

                String aiReply = buildOpeningQuestion(sessionEntity);
                if (aiReply == null || aiReply.isBlank()) {
                    return;
                }

                if (!session.isOpen()) {
                    return;
                }

                // 先落库再推前端，确保用户提交时 DB 中已有该条消息
                saveMessage(sessionId, null, aiReply);
                sendTextMessage(session, aiReply, true);

                // 语音随后下发
                byte[] wavAudio = getOpeningWavAudio(aiReply);
                if (wavAudio.length > 0 && session.isOpen()) {
                    sendAudio(session, wavAudio, aiReply);
                }

                log.info("已发送会话 {} 的开场问题", sessionId);
            } catch (Exception e) {
                log.error("发送会话 {} 的开场问题失败", sessionId, e);
            }
        });
    }

    private byte[] getOpeningWavAudio(String text) {
        byte[] cached = openingAudioCache.get(text);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        byte[] wav = synthesizeToWav(text);
        if (wav.length > 0) {
            openingAudioCache.put(text, wav);
        }
        return wav;
    }

    // 合成WAV语音
    private byte[] synthesizeToWav(String text) {
        byte[] pcm = ttsService.synthesize(text);
        if (pcm == null || pcm.length == 0) {
            return new byte[0];
        }
        return convertPcmToWav(pcm);
    }

    private String buildOpeningQuestion(VoiceInterviewSessionEntity sessionEntity) {
        String skillId = sessionEntity.getSkillId() != null ? sessionEntity.getSkillId() : "";
        VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
        Map<String, String> skillQuestions = opening != null ? opening.getSkillQuestions() : null;
        if (skillQuestions != null) {
            String bySkill = skillQuestions.get(skillId);
            if (bySkill != null && !bySkill.isBlank()) {
                return bySkill;
            }
        }
        List<String> algorithmSkills = opening != null && opening.getAlgorithmSkills() != null
            ? opening.getAlgorithmSkills()
            : List.of();

        if (algorithmSkills.contains(skillId)) {
            String configured = opening != null ? opening.getAlgorithmQuestion() : null;
            return configured != null && !configured.isBlank()
                ? configured
                : DEFAULT_OPENING_QUESTION_ALGORITHM;
        }
        String configured = opening != null ? opening.getBackendQuestion() : null;
        return configured != null && !configured.isBlank()
            ? configured
            : DEFAULT_OPENING_QUESTION_BACKEND;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = extractSessionId(session);

        try {
            // 提取消息类型
            JsonNode msg = objectMapper.readTree(message.getPayload());
            String type = msg.get("type").asText();
            // 记录消息日志
            int messageSize = message.getPayload().length();
            int messageSizeKB = messageSize / 1024;
            if ("audio".equals(type)) {
                log.trace("[WebSocket] 收到音频: sessionId={}, size={}KB", sessionId, messageSizeKB);
            } else {
                log.info("[WebSocket] 收到消息: sessionId={}, type={}, size={}KB ({} 字节)",
                    sessionId, type, messageSizeKB, messageSize);
            }

            // 大消息警告
            if (messageSizeKB > 200) {
                log.warn("[WebSocket] 检测到大消息: {}KB", messageSizeKB);
            }

            // 刷新最近活动时间，供暂停超时检测使用
            // 更新最后活动时间（用于暂停超时检测）
            lastActivityTime.put(sessionId, System.currentTimeMillis());

            switch (type) {
                // 消息是音频
                case "audio":
                    String audioData = msg.has("data") ? msg.get("data").asText() : null;
                    if (audioData != null && !audioData.isEmpty()) {
                        handleUserAudio(sessionId, audioData);
                    } else {
                        log.warn("收到的音频消息缺少 data 字段");
                    }
                    break;
                // 消息是控制
                case "control":
                    try {
                        handleControl(sessionId, objectMapper.treeToValue(msg, WebSocketControlMessage.class));
                    } catch (Exception e) {
                        log.error("处理会话 {} 的控制消息出错", sessionId, e);
                        sendError(session, "控制消息处理失败: " + e.getMessage());
                    }
                    break;
                default:
                    log.warn("未知消息类型: {}（会话 {}）", type, sessionId);
            }

        } catch (Exception e) {
            log.error("处理会话 {} 的消息出错", sessionId, e);
            sendError(session, "消息处理失败: " + e.getMessage());
        }
    }

    @Override
    public void destroy() {
        voicePipelineExecutor.shutdownNow();
        utteranceMergeScheduler.shutdownNow();
        try {
            // 等待线程池中所有任务完成，最多等 5 秒
            if (!voicePipelineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("voicePipelineExecutor 未在 5 秒内终止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        try {
            sessions.remove(sessionId);
            SessionState removedState = sessionStates.remove(sessionId);
            if (removedState != null) {
                Thread t = removedState.getProcessingThread();
                if (t != null) {
                    t.interrupt();
                }
            }
            lastActivityTime.remove(sessionId);

            // 停止 STT 转写
            sttService.stopTranscription(sessionId);
            log.info("会话 {} 的 WebSocket 连接已关闭: status={}", sessionId, status);

            // WebSocket 异常断开时自动结束会话，防止状态永远停留在 IN_PROGRESS
            try {
                interviewService.endSessionIfInProgress(sessionId);
            } catch (Exception endEx) {
                log.warn("断开后自动结束会话 {} 失败: {}", sessionId, endEx.getMessage());
            }
        } catch (Exception e) {
            log.error("连接关闭后清理会话 {} 出错", sessionId, e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("会话 {} 的 WebSocket 传输错误", extractSessionId(session), exception);
    }

    /** 无会话、append 失败等均可重连 ASR */
    private static boolean shouldRecoverAsrConnection(IllegalStateException ex) {
        String m = ex.getMessage();
        if (m == null) {
            return false;
        }
        return m.contains("No active session") || m.contains("ASR append failed");
    }

    private static boolean isAsrNotReady(IllegalStateException ex) {
        String m = ex.getMessage();
        return m != null && m.contains("ASR session not ready");
    }

    // 开始DashScope的STT服务
    private void startDashScopeStt(String sessionId, WebSocketSession session) {
        sttService.startTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                () -> sendAsrReady(session),
                error -> {
                    log.error("会话 {} 的 STT 出错", sessionId, error);
                    sendError(session, "语音识别失败: " + error.getMessage());
                }
        );

        scheduleAsrReadyCheck(sessionId, session, 0);
    }

    private void scheduleAsrReadyCheck(String sessionId, WebSocketSession session, int retryCount) {
        utteranceMergeScheduler.schedule(
            () -> checkAsrReadyOrRetry(sessionId, session, retryCount),
            ASR_READY_CHECK_DELAY_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void checkAsrReadyOrRetry(String sessionId, WebSocketSession session, int retryCount) {
        if (session == null || !session.isOpen() || sttService.isReady(sessionId)) {
            return;
        }

        if (retryCount < MAX_ASR_READY_RETRY) {
            int nextRetry = retryCount + 1;
            log.warn("[会话: {}] ASR {} 秒后仍未就绪，正在重试（{}/{}）",
                sessionId, ASR_READY_CHECK_DELAY_SECONDS, nextRetry, MAX_ASR_READY_RETRY);
            sendAsrStatus(session, "asr_reconnecting", "语音识别连接较慢，正在自动重连");
            restartDashScopeStt(sessionId);
            scheduleAsrReadyCheck(sessionId, session, nextRetry);
            return;
        }

        log.warn("[会话: {}] ASR 重试 {} 次后仍未就绪", sessionId, retryCount);
        sendError(session, "语音识别连接准备超时，请检查语音服务配置或稍后重试");
    }

    /**
     * DashScope ASR 断线后重连（回调与首次 start 一致）
     */
    private void restartDashScopeStt(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        sttService.restartTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                () -> sendAsrReady(session),
                error -> {
                    log.error("会话 {} 的 STT 出错", sessionId, error);
                    sendError(session, "语音识别失败: " + error.getMessage());
                }
        );
    }

    /**
     * 处理用户音频消息
     * 仅将音频交给 STT 转写器（结果通过回调返回）
     */
    private void handleUserAudio(String sessionId, String base64Audio) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            log.warn("会话不存在: sessionId={}", sessionId);
            return;
        }

        // AI 正在说话或处于回声冷却期时，丢弃麦克风输入，防止回声触发 LLM
        SessionState state = sessionStates.get(sessionId);
        if (state != null && state.isAiSpeakingOrCooldown()) {
            return;
        }

        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            log.debug("收到会话 {} 的音频数据: {} 字节", sessionId, audioData.length);

            try {
                sttService.sendAudio(sessionId, audioData);
            } catch (IllegalStateException ex) {
                if (isAsrNotReady(ex)) {
                    log.debug("[会话: {}] ASR 就绪前丢弃音频分片", sessionId);
                    return;
                } else if (shouldRecoverAsrConnection(ex)) {
                    log.warn("[会话: {}] ASR 发送失败（{}），重启 DashScope 并重试该分片",
                            sessionId, ex.getMessage() != null ? ex.getMessage() : "unknown");
                    restartDashScopeStt(sessionId);
                    // 重试 15 次，每次等待 80ms（共 1.2 秒）
                    boolean sent = false;
                    for (int i = 0; i < 15; i++) {
                        try {
                            Thread.sleep(80);
                            sttService.sendAudio(sessionId, audioData);
                            sent = true;
                            break;
                        } catch (IllegalStateException retry) {
                            if (isAsrNotReady(retry)) {
                                continue;// ASR 还没就绪，继续等待
                            }
                            if (!shouldRecoverAsrConnection(retry)) {
                                throw retry;// 其他异常，抛出
                            }
                        }
                    }
                    if (!sent) {
                        log.error("[会话: {}] 重启后 ASR 仍不可用", sessionId);
                        sendError(session, "语音识别连接中断，请刷新页面后重试");
                    }
                } else {
                    throw ex;
                }
            }

        } catch (Exception e) {
            log.error("处理会话 {} 的用户音频出错", sessionId, e);
            String errorMessage = getErrorMessage(e);
            sendError(session, errorMessage);
        }
    }

    /**
     * 处理回调返回的 STT 结果（partial = 实时字幕；final = 已定稿的句子，用于送 LLM）。
     */
    private void handleSttResult(String sessionId, String recognizedText, boolean isFinalSegment) {
        WebSocketSession session = sessions.get(sessionId);
        SessionState state = sessionStates.get(sessionId);

        if (session == null || state == null) {
            log.warn("会话或状态不存在: sessionId={}", sessionId);
            return;
        }

        if (!isFinalSegment) {
            state.markSttActivity();
            sendSubtitle(session, state.getMergeBufferPreviewWithPartial(recognizedText), false);
            return;
        }

        // 用户已提交、LLM 正在处理时，丢弃迟到的 STT 定稿段，防止污染下一轮 mergeBuffer
        if (state.isProcessing().get()) {
            log.debug("处理期间丢弃迟到的 STT 定稿段（会话 {}）: {}",
                sessionId, recognizedText);
            return;
        }

        log.debug("会话 {} 的 STT 定稿段: {}", sessionId, recognizedText);
        incrementCounter("app.voice.interview.asr.final_segments", "status", "received");

        // 合并多次 VAD 切段，只更新实时字幕；是否提交给 LLM 由前端手动 submit 控制
        state.appendFinalSttSegment(recognizedText);
        sendSubtitle(session, state.getMergeBufferPreview(), false);
    }

    /**
     * 手动提交：获取 mergeBuffer 中累积的用户文本并触发 LLM 管线。
     */
    private void flushMergedUtteranceToLlm(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        SessionState state = sessionStates.get(sessionId);
        if (session == null || state == null || !session.isOpen()) {
            return;
        }
        // 为什么用 schedule 重试而不是直接丢弃？ 防止用户在 LLM 处理期间连续点击"提交"，导致请求丢失。
        if (!state.isProcessing().compareAndSet(false, true)) {
            utteranceMergeScheduler.schedule(
                    () -> flushMergedUtteranceToLlm(sessionId),
                    400,
                    TimeUnit.MILLISECONDS);
            return;
        }
        long mergeStartAt = state.getMergeStartedAt();
        String userText = state.takeMergeBufferAndClear();
        if (userText == null || userText.trim().isEmpty()) {
            state.isProcessing().set(false);
            return;
        }
        long mergeWaitMs = Math.max(0, System.currentTimeMillis() - mergeStartAt);
        recordTimerMillis("app.voice.interview.asr.merge_wait", mergeWaitMs, "status", "success");
        state.setAccumulatedText(userText);
        log.info("会话 {} 的用户语音已合并，触发 LLM（长度 {}）", sessionId, userText.length());

        // 提交到虚拟线程执行阻塞的 LLM+TTS 管线，立即释放调度器线程
        voicePipelineExecutor.execute(() -> {
            state.setProcessingThread(Thread.currentThread());
            try {
                triggerLlmResponse(sessionId, session, state);
            } finally {
                state.isProcessing().set(false);
                state.setProcessingThread(null);
            }
        });
    }



    /**
     * 为完整句子触发 LLM 响应。
     * 开启流式后采用句子级 TTS 重叠：每检测到一个完整句子就并发发起一次 TTS，
     * 使 TTS 与 LLM 后续生成并行进行。
     */
    private void triggerLlmResponse(String sessionId, WebSocketSession session, SessionState state) {
        long turnStartNanos = System.nanoTime();
        state.aiSpeaking.set(true);
        try {
            if (!session.isOpen()) {
                log.warn("WebSocket 会话已关闭，跳过会话 {} 的 LLM 响应", sessionId);
                return;
            }

            String userText = state.getAccumulatedText();
            if (userText == null || userText.trim().isEmpty()) {
                log.warn("用户文本为空，跳过 LLM 响应");
                return;
            }

            log.info("获取会话 {} 的 LLM 响应: text={}", sessionId, userText);

            VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
            if (sessionEntity == null) {
                log.error("会话 {} 的实体不存在，无法生成 LLM 响应", sessionId);
                sendError(session, "会话不存在，请重新开始面试");
                return;
            }

            List<String> conversationHistory = getHistory(sessionId);

            long llmStartNanos = System.nanoTime();
            AtomicLong firstTokenAtNanos = new AtomicLong(0);
            boolean streamEnabled = voiceInterviewProperties.isLlmStreamingEnabled();
            String aiReply;

            // TTS服务流式输出
            if (streamEnabled) {
                // 句子级并发 TTS：LLM 流式输出期间每检测到一个完整句子就启动 TTS
                Semaphore ttsSemaphore = new Semaphore(
                    Math.max(1, voiceInterviewProperties.getMaxConcurrentTtsPerSession()));
                boolean chunkedEnabled = voiceInterviewProperties.isChunkedAudioEnabled();
                long ttsTimeoutSec = Math.max(5, voiceInterviewProperties.getTtsTimeoutSeconds());
                OrderedTtsChunkEmitter chunkEmitter = chunkedEnabled
                    ? new OrderedTtsChunkEmitter(sessionId, session, ttsSemaphore, ttsTimeoutSec)
                    : null;
                List<CompletableFuture<byte[]>> ttsFutures = new ArrayList<>();

                aiReply = llmService.chatStreamSentences(
                    userText,
                    partialText -> {
                        if (partialText == null || partialText.isBlank() || !session.isOpen()) {
                            return;
                        }
                        if (firstTokenAtNanos.compareAndSet(0L, System.nanoTime())) {
                            recordTimerSinceNanos(
                                "app.voice.interview.llm.first_token_latency",
                                llmStartNanos,
                                "status", "success"
                            );
                        }
                        sendTextMessage(session, partialText, false);
                    },
                    sentence -> {
                        if (sentence == null || sentence.isBlank()) {
                            return;
                        }
                        if (chunkEmitter != null) {
                            chunkEmitter.submit(sentence);
                            return;
                        }
                        ttsSemaphore.acquireUninterruptibly();
                        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                            try {
                                return ttsService.synthesize(sentence);
                            } finally {
                                ttsSemaphore.release();
                            }
                        }, voicePipelineExecutor);
                        ttsFutures.add(future);
                    },
                    sessionEntity,
                    conversationHistory
                );

                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "true");
                log.info("会话 {} 的 LLM 响应: '{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("LLM 处理期间 WebSocket 已关闭，丢弃会话 {} 的响应", sessionId);
                    return;
                }

                sendSubtitle(session, userText, true);
                sendTextMessage(session, aiReply, true);
                saveMessage(sessionId, userText, aiReply);

                // 按顺序收集所有 TTS 结果（带超时，防止单句 TTS 挂死阻塞整条管道）
                if (chunkEmitter != null) {
                    long ttsStartNanos = System.nanoTime();
                    chunkEmitter.finish();
                    int emittedChunks = chunkEmitter.awaitCompletion();
                    recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");
                    if (emittedChunks == 0 && session.isOpen()) {
                        log.info("[会话: {}] 流式 TTS 未产生分片，回退到全文 TTS",
                            sessionId);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                sendAudio(session, convertPcmToWav(fallbackPcm), aiReply);
                            }
                        } catch (Exception e) {
                            log.warn("[会话: {}] 兜底 TTS 失败: {}", sessionId, e.getMessage());
                        }
                    }
                } else if (!ttsFutures.isEmpty()) {
                    long ttsStartNanos = System.nanoTime();
                    // 合并模式：收集所有 PCM 后合并为一个完整音频
                    List<byte[]> pcmChunks = new ArrayList<>();
                    int totalSize = 0;
                    int failedCount = 0;
                    boolean audioSentByFallback = false;
                    for (CompletableFuture<byte[]> f : ttsFutures) {
                        try {
                            byte[] pcm = f.get(ttsTimeoutSec, TimeUnit.SECONDS);
                            if (pcm != null && pcm.length > 0) {
                                pcmChunks.add(pcm);
                                totalSize += pcm.length;
                            }
                        } catch (Exception e) {
                            f.cancel(true);
                            failedCount++;
                            log.warn("[会话: {}] 单句 TTS future 失败: {}", sessionId, e.getMessage());
                        }
                    }
                    recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                    if (!session.isOpen()) {
                        log.warn("TTS 处理期间 WebSocket 已关闭，丢弃会话 {} 的音频", sessionId);
                        return;
                    }

                    // 有句子级 TTS 失败且无成功结果时，用完整文本做一次兜底 TTS
                    if (totalSize == 0 && failedCount > 0 && session.isOpen()) {
                        log.info("[会话: {}] 全部 {} 个单句 TTS 调用失败，回退到全文 TTS",
                            sessionId, failedCount);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                byte[] wavAudio = convertPcmToWav(fallbackPcm);
                                log.info("[会话: {}] 兜底 TTS 成功: WAV {} 字节",
                                    sessionId, wavAudio.length);
                                sendAudio(session, wavAudio, aiReply);
                                audioSentByFallback = true;
                            }
                        } catch (Exception e) {
                            log.warn("[会话: {}] 兜底 TTS 也失败: {}", sessionId, e.getMessage());
                        }
                    }

                    if (!audioSentByFallback) {
                        if (totalSize > 0 && session.isOpen()) {
                            byte[] mergedPcm = new byte[totalSize];
                            int offset = 0;
                            for (byte[] chunk : pcmChunks) {
                                System.arraycopy(chunk, 0, mergedPcm, offset, chunk.length);
                                offset += chunk.length;
                            }
                            byte[] wavAudio = convertPcmToWav(mergedPcm);
                            log.info("[会话: {}] 发送合并音频: {} 句, WAV {} 字节",
                                sessionId, pcmChunks.size(), wavAudio.length);
                            sendAudio(session, wavAudio, aiReply);
                        } else {
                            log.error("[会话: {}] 所有 TTS 调用均返回空音频", sessionId);
                            incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                        }
                    }
                }
            } else {
                // 阻塞等待大模型完整返回回答文本
                aiReply = llmService.chat(userText, sessionEntity, conversationHistory);
                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "false");
                log.info("会话 {} 的 LLM 响应: '{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("LLM 处理期间 WebSocket 已关闭，丢弃会话 {} 的响应", sessionId);
                    return;
                }

                sendSubtitle(session, userText, true);// 发送用户说的话作为最终字幕
                sendTextMessage(session, aiReply, true);// 发送 AI 回答的完整文本给前端
                saveMessage(sessionId, userText, aiReply);//保存问答对到数据库/缓存，供后续历史记录使用

                long ttsStartNanos = System.nanoTime();
                log.info("[会话: {}] 开始 TTS 合成（文本长度: {}）",
                    sessionId, aiReply.length());
                byte[] aiAudio = ttsService.synthesize(aiReply);
                recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                if (!session.isOpen()) {
                    return;
                }

                if (aiAudio == null || aiAudio.length == 0) {
                    log.error("[会话: {}] TTS 返回空音频", sessionId);
                    incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                } else {
                    byte[] wavAudio = convertPcmToWav(aiAudio);
                    sendAudio(session, wavAudio, aiReply);
                }
            }

            state.setAccumulatedText("");
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "success");
            incrementCounter("app.voice.interview.turn.completed", "status", "success");

        } catch (Exception e) {
            log.error("触发会话 {} 的 LLM 响应出错", sessionId, e);
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "failure");
            incrementCounter("app.voice.interview.turn.completed", "status", "failure");
            incrementCounter("app.voice.interview.errors", "stage", "turn");
            if (session.isOpen()) {
                sendError(session, "AI响应失败: " + e.getMessage());
            }
        } finally {
            state.aiSpeaking.set(false);
            state.aiSpeakEndAt.set(System.currentTimeMillis() + AI_SPEAK_COOLDOWN_MS);
        }
    }

    /**
     * 将异常转换为用户可读的错误消息
     */
    private String getErrorMessage(Exception e) {
        Throwable cause = e.getCause();

        // 识别特定的阿里云错误
        if (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                if (message.contains("403") || message.contains("ACCESS_DENIED")) {
                    return "阿里云语音服务认证失败：AccessKey 无效或已过期。请在 .env 文件中配置正确的 ALIYUN_ACCESS_KEY";
                }
                if (message.contains("timeout") || message.contains("channel inactive")) {
                    return "阿里云语音服务连接超时。请检查网络连接或稍后重试";
                }
            }
        }

        // 默认错误消息
        return "语音处理失败：" + e.getMessage();
    }

    /**
     * 处理控制消息（end_interview、start_phase）
     */
    private void handleControl(String sessionId, WebSocketControlMessage control) {
        log.info("收到会话 {} 的控制消息: action={}, phase={}",
                sessionId, control.getAction(), control.getPhase());

        switch (control.getAction()) {
            case "submit":
                if (control.getData() != null) {
                    Object textObj = control.getData().get("text");
                    if (textObj instanceof String text && !text.isBlank()) {
                        SessionState state = sessionStates.get(sessionId);
                        if (state != null) {
                            state.setMergeBufferDirectly(text);
                        }
                    }
                }
                flushMergedUtteranceToLlm(sessionId);
                break;
            case "end_interview":
                interviewService.endSession(sessionId);
                break;
            case "start_phase":
                interviewService.startPhase(sessionId, control.getPhase());
                break;
        }
    }

    private void sendSubtitle(WebSocketSession session, String text, boolean isFinal) {
        WebSocketSubtitleMessage subtitle = WebSocketSubtitleMessage.builder()
                .type("subtitle")
                .text(text)
                .isFinal(isFinal)
                .build();
        sendMessage(session, toJson(subtitle));
    }

    private void sendAudio(WebSocketSession session, byte[] audio, String text) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        log.info("向前端发送音频: WAV {} 字节, Base64 长度 {}",
                audio.length, base64Audio.length());
        sendMessage(session, toJson(Map.of(
                "type", "audio",
                "data", base64Audio,
                "text", text
        )));
    }

    private void sendTextMessage(WebSocketSession session, String text) {
        sendTextMessage(session, text, false);
    }

    private void sendTextMessage(WebSocketSession session, String text, boolean isFinal) {
        sendMessage(session, toJson(Map.of(
                "type", "text",
                "content", text,
                "final", isFinal
        )));
    }

    private void sendError(WebSocketSession session, String error) {
        sendMessage(session, toJson(Map.of("type", "error", "message", error)));
    }

    private void sendAsrReady(WebSocketSession session) {
        sendAsrStatus(session, "asr_ready", "语音识别已就绪");
    }

    private void sendAsrStatus(WebSocketSession session, String action, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
            "type", "control",
            "action", action,
            "message", message,
            "timestamp", System.currentTimeMillis()
        )));
    }

    private void sendAudioChunk(WebSocketSession session, byte[] wavAudio, int index, boolean isLast) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
        sendMessage(session, toJson(Map.of(
                "type", "audio_chunk",
                "data", base64Audio,
                "index", index,
                "isLast", isLast
        )));
        log.debug("[会话] 已发送音频分片: index={}, isLast={}, size={} 字节", index, isLast, wavAudio.length);
    }

    private void sendAudioComplete(WebSocketSession session) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
            "type", "control",
            "action", "audio_complete",
            "message", "面试官语音播放完成",
            "timestamp", System.currentTimeMillis()
        )));
    }

    private class OrderedTtsChunkEmitter {

        private final String sessionId;
        private final WebSocketSession session;
        private final Semaphore ttsSemaphore;
        private final long ttsTimeoutSec;
        private final Map<Integer, CompletableFuture<byte[]>> futures = new ConcurrentHashMap<>();
        private final AtomicInteger nextIndex = new AtomicInteger();
        private final AtomicInteger emittedChunks = new AtomicInteger();
        private final Object lock = new Object();
        private final CompletableFuture<Integer> completion;
        private volatile int totalChunks = -1;

        OrderedTtsChunkEmitter(
                String sessionId,
                WebSocketSession session,
                Semaphore ttsSemaphore,
                long ttsTimeoutSec) {
            this.sessionId = sessionId;
            this.session = session;
            this.ttsSemaphore = ttsSemaphore;
            this.ttsTimeoutSec = ttsTimeoutSec;
            this.completion = CompletableFuture.supplyAsync(this::drainChunks, voicePipelineExecutor);
        }

        void submit(String sentence) {
            int index = nextIndex.getAndIncrement();
            ttsSemaphore.acquireUninterruptibly();
            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return ttsService.synthesize(sentence);
                } finally {
                    ttsSemaphore.release();
                }
            }, voicePipelineExecutor);

            futures.put(index, future);
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        void finish() {
            synchronized (lock) {
                totalChunks = nextIndex.get();
                lock.notifyAll();
            }
        }

        int awaitCompletion() {
            long timeoutSec = Math.max(ttsTimeoutSec + 2, (ttsTimeoutSec + 1) * Math.max(1, nextIndex.get()));
            try {
                return completion.get(timeoutSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[会话: {}] 流式 TTS 分片发射器未正常结束: {}",
                    sessionId, e.getMessage());
                completion.cancel(true);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sendAudioComplete(session);
                }
                return emitted;
            }
        }

        private int drainChunks() {
            int index = 0;
            try {
                while (true) {
                    CompletableFuture<byte[]> future = waitForFuture(index);
                    if (future == null) {
                        int emitted = emittedChunks.get();
                        if (emitted > 0) {
                            sendAudioComplete(session);
                        }
                        return emitted;
                    }

                    try {
                        byte[] pcm = future.get(ttsTimeoutSec, TimeUnit.SECONDS);
                        if (pcm != null && pcm.length > 0 && session.isOpen()) {
                            sendAudioChunk(session, convertPcmToWav(pcm), index, false);
                            emittedChunks.incrementAndGet();
                        }
                    } catch (Exception e) {
                        future.cancel(true);
                        log.warn("[会话: {}] 流式 TTS 分片 {} 失败: {}",
                            sessionId, index, e.getMessage());
                    } finally {
                        futures.remove(index);
                        index++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[会话: {}] 流式 TTS 分片发射器被中断", sessionId);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sendAudioComplete(session);
                }
                return emitted;
            }
        }

        private CompletableFuture<byte[]> waitForFuture(int index) throws InterruptedException {
            synchronized (lock) {
                while (!futures.containsKey(index)) {
                    if (totalChunks >= 0 && index >= totalChunks) {
                        return null;
                    }
                    lock.wait(100);
                }
                return futures.get(index);
            }
        }
    }

    private void recordTimerSinceNanos(String metricName, long startNanos, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        long elapsed = Math.max(0, System.nanoTime() - startNanos);
        registry.timer(metricName, tags).record(elapsed, TimeUnit.NANOSECONDS);
    }

    private void recordTimerMillis(String metricName, long millis, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        registry.timer(metricName, tags).record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    private void incrementCounter(String metricName, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        registry.counter(metricName, tags).increment();
    }

    private MeterRegistry getRegistry() {
        return meterRegistryProvider.getIfAvailable();
    }

    /**
     * 定时任务：检查暂停警告与超时
     * 每 30 秒执行一次
     * 定时任务：检查暂停警告和超时
     */
    @Scheduled(fixedRate = 30000)
    public void checkPauseTimeout() {
        long now = System.currentTimeMillis();

        lastActivityTime.forEach((sessionId, lastTime) -> {
            long elapsed = now - lastTime;

            // 4分30秒时发送警告
            if (elapsed > WARNING_TIME_MS && elapsed < PAUSE_TIMEOUT_MS) {
                sendPauseWarning(sessionId);
            }
            // 5分00秒时超时
            else if (elapsed >= PAUSE_TIMEOUT_MS) {
                log.warn("会话 {} 已不活跃 {} 分钟，正在暂停",
                    sessionId, PAUSE_TIMEOUT_MS / 60000);
                handlePauseTimeout(sessionId);
            }
        });
    }

    @Scheduled(fixedRate = 300_000)
    public void cleanupStaleSessions() {
        try {
            int cleaned = interviewService.cleanupStaleSessions();
            if (cleaned > 0) {
                log.info("超时会话清理: 已清理 {} 个会话", cleaned);
            }
        } catch (Exception e) {
            log.error("超时会话清理出错", e);
        }
    }

    /**
     * 发送暂停警告通知（pause_timeout_warning）
     * 发送暂停警告通知
     */
    private void sendPauseWarning(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            sendMessage(session, toJson(Map.of(
                "type", "control",
                "action", "pause_timeout_warning",
                "message", "会话将在30秒后暂停，请继续说话或点击继续",
                "timestamp", System.currentTimeMillis()
            )));
        }
    }

    /**
     * 处理暂停超时：保存状态并断开连接
     * 处理暂停超时 - 保存状态并断开连接
     */
    private void handlePauseTimeout(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);

        try {
            if (session != null && session.isOpen()) {
                sendMessage(session, toJson(Map.of(
                    "type", "control",
                    "action", "pause_timeout",
                    "message", "会话因超时已暂停,可在历史记录中恢复",
                    "timestamp", System.currentTimeMillis()
                )));
            }

            // 2. 将会话状态保存到数据库
            interviewService.pauseSession(sessionId, "timeout");

            // 3. 关闭 WebSocket 连接
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.GOING_AWAY);
            }

            // 4. 清理资源 - 停止 ASR 会话，防止资源泄漏
            sttService.stopTranscription(sessionId);
            sessions.remove(sessionId);
            sessionStates.remove(sessionId);
            lastActivityTime.remove(sessionId);

            log.info("会话 {} 因超时已暂停", sessionId);

        } catch (Exception e) {
            log.error("处理会话 {} 的暂停超时出错", sessionId, e);
        }
    }

    /**
     * 从 WebSocket URI 路径中提取 session id
     * 路径格式：/ws/voice-interview/{sessionId}
     */
    private String extractSessionId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * 获取会话的对话历史
     * 从数据库加载会话历史
     */
    private List<String> getHistory(String sessionId) {
        try {
            List<VoiceInterviewMessageEntity> messages = interviewService.getConversationHistory(sessionId);
            List<String> history = new ArrayList<>();
            String pendingAiQuestion = null;

            for (VoiceInterviewMessageEntity msg : messages) {
                String aiText = VoiceInterviewMessageEntity.trimToNull(msg.getAiGeneratedText());
                String userText = VoiceInterviewMessageEntity.trimToNull(msg.getUserRecognizedText());

                if (pendingAiQuestion != null) {
                    history.add("面试官：" + pendingAiQuestion);
                    pendingAiQuestion = null;
                    if (userText != null) {
                        history.add("候选人：" + userText);
                    }
                    if (aiText != null) {
                        pendingAiQuestion = aiText;
                    }
                    continue;
                }

                if (aiText != null && userText != null) {
                    history.add("面试官：" + aiText);
                    history.add("候选人：" + userText);
                } else if (aiText != null) {
                    pendingAiQuestion = aiText;
                } else if (userText != null) {
                    history.add("候选人：" + userText);
                }
            }
            if (pendingAiQuestion != null) {
                history.add("面试官：" + pendingAiQuestion);
            }

            log.debug("已为会话 {} 加载 {} 条历史消息", sessionId, history.size());
            return history;
        } catch (Exception e) {
            log.error("加载会话 {} 的对话历史出错", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 从数据库获取会话实体
     */
    private VoiceInterviewSessionEntity getSessionEntity(String sessionId) {
        try {
            Long sessionIdLong = Long.parseLong(sessionId);
            return interviewService.getSession(sessionIdLong);
        } catch (NumberFormatException e) {
            log.error("会话 ID 格式非法: {}", sessionId);
            return null;
        }
    }

    /**
     * 将消息保存到数据库
     */
    private void saveMessage(String sessionId, String userText, String aiText) {
        try {
            interviewService.saveMessage(sessionId, userText, aiText);
            log.debug("会话消息已保存到数据库: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("保存会话 {} 的消息出错", sessionId, e);
        }
    }

    /**
     * 将 PCM 音频转换为 WAV 格式
     * 在 PCM 数据前添加 44 字节的 WAV 头，供浏览器播放
     *
     * @param pcmData 原始 PCM 音频数据（24kHz、16-bit、单声道）
     * @return WAV 格式的音频数据
     * TODO: PCM 转 WAV
     */
    private byte[] convertPcmToWav(byte[] pcmData) {
        // Qwen TTS Realtime API 使用 24000Hz
        int sampleRate = 24000;
        int bitsPerSample = 16;
        int numChannels = 1;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = dataSize + 36;

        byte[] wavData = new byte[dataSize + 44];

        // 直接写入 WAV 头，避免流分配的开销
        int pos = 0;

        // RIFF 头
        wavData[pos++] = 'R'; wavData[pos++] = 'I'; wavData[pos++] = 'F'; wavData[pos++] = 'F';
        writeIntLE(wavData, pos, fileSize); pos += 4;
        wavData[pos++] = 'W'; wavData[pos++] = 'A'; wavData[pos++] = 'V'; wavData[pos++] = 'E';

        // fmt 块
        wavData[pos++] = 'f'; wavData[pos++] = 'm'; wavData[pos++] = 't'; wavData[pos++] = ' ';
        writeIntLE(wavData, pos, 16); pos += 4; // 块大小
        writeShortLE(wavData, pos, (short) 1); pos += 2; // 音频格式（1 = PCM）
        writeShortLE(wavData, pos, (short) numChannels); pos += 2;
        writeIntLE(wavData, pos, sampleRate); pos += 4;
        writeIntLE(wavData, pos, byteRate); pos += 4;
        writeShortLE(wavData, pos, (short) blockAlign); pos += 2;
        writeShortLE(wavData, pos, (short) bitsPerSample); pos += 2;

        // data 块
        wavData[pos++] = 'd'; wavData[pos++] = 'a'; wavData[pos++] = 't'; wavData[pos++] = 'a';
        writeIntLE(wavData, pos, dataSize); pos += 4;

        // 拷贝 PCM 数据
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.length);

        return wavData;
    }

    /**
     * 以小端格式写入 32 位整数
     */
    private static void writeIntLE(byte[] buf, int pos, int value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((value >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * 以小端格式写入 16 位短整型
     */
    private static void writeShortLE(byte[] buf, int pos, short value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * 内部类，保存会话状态
     */
    private static class SessionState {
        private final AtomicReference<String> accumulatedText = new AtomicReference<>("");
        private final AtomicBoolean processing = new AtomicBoolean(false);
        /** AI 正在播放 TTS 音频，期间丢弃麦克风回声 */
        private final AtomicBoolean aiSpeaking = new AtomicBoolean(false);
        /** AI 音频播放结束后，额外等待这段时间再接受用户音频（ms），防止回声尾音 */
        private final AtomicLong aiSpeakEndAt = new AtomicLong(0);
        /** 多段 STT completed 拼接，防抖后再送 LLM */
        private final AtomicReference<String> mergeBuffer = new AtomicReference<>("");
        /** mergeBuffer 开始计时点，用于”最长等待补充”判定 */
        private final AtomicLong mergeStartedAt = new AtomicLong(0);
        /** 最近一次 STT 活动时间（partial/final） */
        private final AtomicLong lastSttActivityAt = new AtomicLong(System.currentTimeMillis());
        /** 当前正在执行 LLM+TTS 管线的虚拟线程，断连时可中断 */
        private volatile Thread processingThread = null;

        void appendFinalSttSegment(String segment) {
            String s = segment == null ? "" : segment.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.updateAndGet(prev -> {
                if (prev == null || prev.isEmpty()) {
                    mergeStartedAt.set(System.currentTimeMillis());
                    return s;
                }
                return joinSegments(prev, s);
            });
            markSttActivity();
        }

        private static String joinSegments(String previous, String next) {
            String trimmedPrevious = previous.trim();
            String trimmedNext = next.trim();
            if (trimmedNext.equals(trimmedPrevious) || trimmedNext.startsWith(trimmedPrevious)) {
                return trimmedNext;
            }
            if (trimmedPrevious.endsWith(trimmedNext)) {
                return trimmedPrevious;
            }
            if (trimmedPrevious.endsWith("。") || trimmedPrevious.endsWith("！")
                    || trimmedPrevious.endsWith("？") || trimmedPrevious.endsWith(".")
                    || trimmedPrevious.endsWith("!") || trimmedPrevious.endsWith("?")) {
                return trimmedPrevious + " " + trimmedNext;
            }
            return trimmedPrevious + "，" + trimmedNext;
        }

        String getMergeBufferPreview() {
            String s = mergeBuffer.get();
            return s == null ? "" : s;
        }

        void setMergeBufferDirectly(String text) {
            String s = text == null ? "" : text.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.set(s);
            if (mergeStartedAt.get() == 0) {
                mergeStartedAt.set(System.currentTimeMillis());
            }
        }

        String getMergeBufferPreviewWithPartial(String partial) {
            String current = partial == null ? "" : partial.trim();
            if (current.isEmpty()) {
                return getMergeBufferPreview();
            }

            String confirmed = getMergeBufferPreview();
            if (confirmed.isBlank()) {
                return current;
            }
            return joinSegments(confirmed, current);
        }

        String takeMergeBufferAndClear() {
            mergeStartedAt.set(0);
            return mergeBuffer.getAndSet("");
        }

        void markSttActivity() {
            lastSttActivityAt.set(System.currentTimeMillis());
        }

        long getMergeStartedAt() {
            long value = mergeStartedAt.get();
            return value > 0 ? value : System.currentTimeMillis();
        }

        long getLastSttActivityAt() {
            return lastSttActivityAt.get();
        }

        String getAccumulatedText() {
            return accumulatedText.get();
        }

        void setAccumulatedText(String text) {
            accumulatedText.set(text);
        }

        AtomicBoolean isProcessing() {
            return processing;
        }

        void setProcessingThread(Thread t) {
            this.processingThread = t;
        }

        public Thread getProcessingThread() {
            return processingThread;
        }

        boolean isAiSpeakingOrCooldown() {
            if (aiSpeaking.get()) {
                return true;
            }
            // AI 播放结束后的冷却期（默认 800ms），防止扬声器尾音被录入
            return System.currentTimeMillis() < aiSpeakEndAt.get();
        }
    }
}
