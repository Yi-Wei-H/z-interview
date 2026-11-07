package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import interview.guide.modules.llmprovider.service.VoiceAiSettingService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Qwen3 Realtime ASR 服务
 *
 * 基于阿里云 DashScope 的 qwen3-asr-flash-realtime 模型提供实时语音识别。
 * 本服务通过 WebSocket 管理多个并发会话的连接，并借助服务端 VAD（语音活动检测）
 * 完成音频转写。
 *
 * 主要特性：
 * - 基于线程安全并发 Map 的多会话管理
 * - 服务端 VAD，静默 400ms 自动断句
 * - 基于回调的结果处理，支持实时转写更新
 * - 会话结束时自动清理资源
 *
 * 配置：
 * - 模型：qwen3-asr-flash-realtime
 * - 音频格式：PCM，16kHz 采样率
 * - 语言：中文（zh）
 * - VAD：启用，类型为 server_vad
 *
 * @see OmniRealtimeConversation
 * @see OmniRealtimeCallback
 */
@Slf4j
@Service
@DependsOn(VoiceAiSettingService.BEAN_NAME)
public class QwenAsrService {

    // 运行时配置值（从 VoiceInterviewProperties 加载；保留 setter 供测试使用）
    private String url;

    private String model;

    private String apiKey;

    private String language;

    private String format;

    private Integer sampleRate;

    private Boolean enableTurnDetection;

    private String turnDetectionType;

    private Float turnDetectionThreshold;

    private Integer turnDetectionSilenceDurationMs;

    public QwenAsrService(VoiceInterviewProperties voiceInterviewProperties) {
        applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
    }

    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
        log.info("QwenAsrService 已重载: model={}, url={}", model, url);
    }

    private void applyAsrConfig(VoiceInterviewProperties.AsrConfig asr) {
        this.url = asr.getUrl();
        this.model = asr.getModel();
        this.apiKey = asr.getApiKey();
        this.language = asr.getLanguage();
        this.format = asr.getFormat();
        this.sampleRate = asr.getSampleRate();
        this.enableTurnDetection = asr.isEnableTurnDetection();
        this.turnDetectionType = asr.getTurnDetectionType();
        this.turnDetectionThreshold = asr.getTurnDetectionThreshold();
        this.turnDetectionSilenceDurationMs = asr.getTurnDetectionSilenceDurationMs();
    }

    /**
     * 活跃的 ASR 会话表。
     * 键：session id（用户提供的标识）
     * 值：AsrSession，包含 OmniRealtimeConversation 实例与回调
     */
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();

    /** 防止同一 interview sessionId 上并发 stop/start；并在重连时与 {@link #sessionLocks} 配合 */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    private Object lockForSession(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /**
     * 开启一个新的转写会话。
     *
     * 该方法新建一条到 DashScope ASR 服务的 WebSocket 连接，
     * 并注册用于处理转写结果和错误的回调。
     *
     * 会话使用服务端 VAD（语音活动检测）自动检测句子边界。
     * 检测到语音并完成转写后，onResult 回调会携带转写文本被调用。
     *
     * @param sessionId 本会话的唯一标识
     * @param onFinal 一句话/一段话定稿时的回调（{@code completed} 事件）
     * @param onError 发生错误时的回调
     * @throws IllegalStateException 会话已存在或服务未初始化时抛出
     */
    public void startTranscription(String sessionId, Consumer<String> onFinal, Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, null, onError);
    }

    /**
     * 与 {@link #startTranscription(String, Consumer, Consumer)} 相同，但会转发部分转写结果
     * （{@code conversation.item.input_audio_transcription.text}），用于实时字幕。
     *
     * @param onPartial 不需要部分结果时可传 null
     */
    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, onPartial, null, onError);
    }

    // 开始转录
    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        synchronized (lockForSession(sessionId)) {
            startTranscriptionLocked(sessionId, onFinal, onPartial, onReady, onError);
        }
    }

    /**
     * 停止旧连接并重新建立（用于 ASR WebSocket 被服务端关闭后恢复识别）。
     */
    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        restartTranscription(sessionId, onFinal, onPartial, null, onError);
    }

    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        synchronized (lockForSession(sessionId)) {
            log.info("[会话: {}] 正在重启 DashScope ASR（stop + start）", sessionId);
            stopTranscription(sessionId);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startTranscriptionLocked(sessionId, onFinal, onPartial, onReady, onError);

            // 校验重连是否成功
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Thread.sleep(100);
                    AsrSession newSession = sessions.get(sessionId);
                    if (newSession != null && newSession.isReady()) {
                        log.info("[会话: {}] ASR 重连校验成功", sessionId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[会话: {}] ASR 重连校验被中断", sessionId);
                    return;
                }
            }

            log.warn("[会话: {}] ASR 重连 1 秒后仍未完全就绪", sessionId);
        }
    }

    //开始转录锁定
    private void startTranscriptionLocked(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        if (sessions.containsKey(sessionId)) {
            throw new IllegalStateException("Session already exists: " + sessionId);
        }

        try {
            // 基于连接配置构建 OmniRealtimeParam
            OmniRealtimeParam param = OmniRealtimeParam.builder()
                    .model(model)
                    .url(url)
                    .apikey(apiKey)
                    .build();

            final AtomicReference<OmniRealtimeConversation> conversationRef = new AtomicReference<>();

            // 创建 WebSocket 事件的回调处理器
            OmniRealtimeCallback callback = new OmniRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("[会话: {}] WebSocket 连接已建立", sessionId);
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(sessionId, message, onFinal, onPartial, onError);
                }

                @Override
                public void onClose(int code, String reason) {
                    OmniRealtimeConversation closed = conversationRef.get();
                    log.warn("[会话: {}] DashScope ASR WebSocket 已关闭: code={}, reason={}",
                            sessionId, code, reason);
                    // 仅移除与本次连接对应的会话，避免重连后旧 onClose 误删新连接（典型「第三轮起无声」根因）
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && closed != null && existing.getConversation() == closed) {
                            return null;
                        }
                        return existing;
                    });
                }
            };

            // 创建 OmniRealtimeConversation 实例
            OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);
            conversationRef.set(conversation);
            AsrSession asrSession = new AsrSession(conversation, onFinal, onPartial, onError);

            // 连接前先放入会话表，确保 hasActiveSession() 返回 true
            sessions.put(sessionId, asrSession);

            // 异步连接服务端（不阻塞当前线程）
            Thread connectionThread = new Thread(() -> {
                try {
                    // 阻塞直到 WebSocket 握手完成
                    conversation.connect();

                    // 用转写参数配置会话
                    OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
                    transcriptionParam.setLanguage(language);
                    transcriptionParam.setInputSampleRate(sampleRate);
                    transcriptionParam.setInputAudioFormat(format);

                    OmniRealtimeConfig config = OmniRealtimeConfig.builder()
                            .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                            .enableTurnDetection(enableTurnDetection)// VAD 是一种音频处理技术，用于检测音频信号中是否包含人声，并判断说话的起止时间。
                            .turnDetectionType(turnDetectionType)
                            .turnDetectionThreshold(turnDetectionThreshold)
                            .turnDetectionSilenceDurationMs(turnDetectionSilenceDurationMs)
                            .transcriptionConfig(transcriptionParam)
                            .build();

                    // 将配置更新到会话
                    conversation.updateSession(config);
                    // 检查连接是否已被新连接替代（防止 stale callback）
                    if (sessions.get(sessionId) != asrSession) {
                        log.debug("[会话: {}] 忽略过期的 ASR 连接就绪回调", sessionId);
                        return;
                    }
                    asrSession.markReady();
                    if (onReady != null) {
                        onReady.run();
                    }

                    log.info("[会话: {}] 转写会话已成功启动", sessionId);

                } catch (Exception e) {
                    log.error("[会话: {}] 建立连接失败", sessionId, e);
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && existing.getConversation() == conversation) {
                            return null;
                        }
                        return existing;
                    });
                    onError.accept(e);
                }
            }, "ASR-Connection-" + sessionId);
            connectionThread.setDaemon(true);
            connectionThread.start();

        } catch (Exception e) {
            String errorMsg = "Failed to create transcription session: " + sessionId;
            log.error(errorMsg, e);
            sessions.remove(sessionId);
            onError.accept(new IllegalStateException(errorMsg, e));
            throw new IllegalStateException(errorMsg, e);
        }
    }

    /**
     * 向 ASR 服务发送音频数据进行转写。
     *
     * 音频数据应为 PCM 格式、16kHz 采样率。
     * 发送到 DashScope 服务前会先做 Base64 编码。
     *
     * 启用服务端 VAD 后，服务会自动检测语音段，并在检测到静默时触发转写。
     *
     * @param sessionId 会话标识
     * @param audioData 原始 PCM 音频字节
     * @throws IllegalStateException 会话不存在时抛出
     */
    public void sendAudio(String sessionId, byte[] audioData) {
        AsrSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("No active session found: " + sessionId);
        }

        try {
            if (!session.awaitReady(1200)) {
                throw new IllegalStateException("ASR session not ready: " + sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR session ready wait interrupted: " + sessionId, e);
        }

        try {
            // 将音频数据转为 Base64
            String audioBase64 = Base64.getEncoder().encodeToString(audioData);

            // 发送给 ASR 服务
            session.getConversation().appendAudio(audioBase64);

            log.trace("[会话: {}] 已发送音频数据 {} 字节", sessionId, audioData.length);

        } catch (Exception e) {
            log.error("[会话: {}] appendAudio 失败（上游可能会重连）", sessionId, e);
            // 抛出以便 WebSocket 层执行 restartTranscription；不在此重复 onError 避免用户先看到红条再恢复
            throw new IllegalStateException("ASR append failed: " + sessionId, e);
        }
    }

    /**
     * 停止转写并关闭会话。
     *
     * 该方法通知 ASR 服务完成尚未结束的转写，等待最终结果，
     * 然后关闭 WebSocket 连接。
     *
     * @param sessionId 会话标识
     */
    public void stopTranscription(String sessionId) {
        synchronized (lockForSession(sessionId)) {
            AsrSession session = sessions.remove(sessionId);
            // 清理会话锁，防止内存泄漏
            sessionLocks.remove(sessionId);
            if (session == null) {
                log.warn("[会话: {}] 尝试停止不存在的会话", sessionId);
                return;
            }

            try {
                session.getConversation().endSession();
                log.info("[会话: {}] 转写会话已停止", sessionId);
            } catch (InterruptedException e) {
                log.error("[会话: {}] 结束会话时线程被中断", sessionId, e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[会话: {}] 结束会话出错（可能已关闭）: {}", sessionId, e.getMessage());
            }

            try {
                session.getConversation().close();
            } catch (Exception e) {
                log.debug("[会话: {}] 连接已关闭: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 检查指定 ID 的会话当前是否活跃。
     *
     * @param sessionId 会话标识
     * @return 会话存在且活跃时返回 true，否则返回 false
     */
    public boolean hasActiveSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public boolean isReady(String sessionId) {
        AsrSession session = sessions.get(sessionId);
        return session != null && session.isReady();
    }

    /**
     * 销毁服务并清理所有活跃会话。
     *
     * 该方法在 Spring 容器关闭时自动调用，
     * 会停止所有活跃会话并释放资源。
     */
    @PreDestroy
    public void destroy() {
        log.info("销毁 QwenAsrService（活跃会话 {} 个）", sessions.size());

        // 停止所有活跃会话
        sessions.keySet().forEach(sessionId -> {
            try {
                stopTranscription(sessionId);
            } catch (Exception e) {
                log.error("[会话: {}] 清理时出错", sessionId, e);
            }
        });

        sessions.clear();
        log.info("QwenAsrService 已销毁");
    }

    /**
     * 处理 DashScope ASR 服务下发的服务端事件。
     *
     * 该方法处理以下事件类型：
     * - session.created：会话创建成功
     * - session.updated：会话配置已更新
     * - conversation.item.input_audio_transcription.completed：最终转写结果
     * - conversation.item.input_audio_transcription.text / .delta：部分转写（实时字幕）
     * - error：发生错误
     *
     * @param sessionId 会话标识
     * @param message 服务端下发的 JSON 事件消息
     * @param onFinal 定稿文本回调
     * @param onPartial 流式部分文本回调（可选）
     * @param onError 错误回调
     */
    private void handleServerEvent(
            String sessionId,
            JsonObject message,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        try {
            String eventType = message.get("type").getAsString();

            log.trace("[会话: {}] 收到事件: {}", sessionId, eventType);

            switch (eventType) {
                case "session.created":
                    log.debug("[会话: {}] 服务端会话已创建", sessionId);
                    break;

                case "session.updated":
                    log.debug("[会话: {}] 会话配置已更新", sessionId);
                    break;

                // 当前这一句的最终定稿
                case "conversation.item.input_audio_transcription.completed":
                    // 最终转写结果
                    JsonObject transcriptObj = message.getAsJsonObject();
                    String transcript = transcriptObj.get("transcript").getAsString();
                    String language = transcriptObj.has("language") ?
                            transcriptObj.get("language").getAsString() : "unknown";
                    String emotion = transcriptObj.has("emotion") ?
                            transcriptObj.get("emotion").getAsString() : "neutral";

                    log.debug("[会话: {}] 转写完成: language={}, emotion={}, text={}",
                            sessionId, language, emotion, transcript);

                    onFinal.accept(transcript);
                    break;

                //ASR 服务端在用户说话过程中实时推送的中间结果
                case "conversation.item.input_audio_transcription.text":
                case "conversation.item.input_audio_transcription.delta":
                    dispatchPartialTranscript(sessionId, message, onPartial);
                    break;

                // 电话线断了 → 需要重新拨号
                case "error":
                    // 错误事件
                    JsonObject errorObj = message.getAsJsonObject("error");
                    String errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                    String errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                    String errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";

                    String fullErrorMessage = String.format("ASR 错误 [%s/%s]: %s", errorType, errorCode, errorMessage);
                    log.error("[会话: {}] {}", sessionId, fullErrorMessage);

                    onError.accept(new IllegalStateException(fullErrorMessage));
                    break;

                case "session.finished":
                    log.debug("[会话: {}] 服务端会话已结束", sessionId);
                    break;

                // 一次对话中某句话没听清 → 让对方再说一遍
                case "conversation.item.input_audio_transcription.failed":
                    log.error("[会话: {}] ASR 转写失败（单句）: {}", sessionId, message);
                    break;

                default:
                    if (eventType != null && eventType.contains("transcription")) {
                        log.debug("[会话: {}] 未处理的转写相关事件: {}", sessionId, message);
                    } else {
                        log.trace("[会话: {}] 未处理的事件类型: {}", sessionId, eventType);
                    }
            }

        } catch (Exception e) {
            log.error("[会话: {}] 处理服务端事件出错", sessionId, e);
            onError.accept(e);
        }
    }

    /**
     * 将部分/流式 ASR 文本转发给实时 UI（仅靠 VAD 并不代表 STT 结果可见）。
     */
    private void dispatchPartialTranscript(
            String sessionId, JsonObject message, Consumer<String> onPartial) {
        if (onPartial == null) {
            log.trace("[会话: {}] 收到部分转写（无消费者）", sessionId);
            return;
        }
        String text = extractTranscriptPayload(message);
        if (text != null && !text.isBlank()) {
            onPartial.accept(text);
        } else {
            log.trace("[会话: {}] 部分 ASR 事件无可提取文本: {}", sessionId, message);
        }
    }

    /**
     * 从 ASR JSON 事件中提取可展示的文本。
     * <p>
     * 对于 {@code conversation.item.input_audio_transcription.text} 事件，官方预览版的
     * 载荷是 {@code text}（已确认前缀）+ {@code stash}（草稿后缀），两者都可能为空。
     * </p>
     * 多层兜底：transcript → text+stash → delta → item.transcript，确保尽可能提取到文本
     */
    static String extractTranscriptPayload(JsonObject message) {
        // .completed 事件
        if (message.has("transcript") && !message.get("transcript").isJsonNull()) {
            JsonElement el = message.get("transcript");
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
        }
        // 实时部分结果：text + stash（参见阿里 qwen-asr-realtime 服务端事件文档）
        if (message.has("text") || message.has("stash")) {
            String prefix = "";// 已确认的文本（ASR 高置信度部分）
            String suffix = "";// 未确认的文本（ASR 低置信度部分，暂存）
            if (message.has("text") && !message.get("text").isJsonNull() && message.get("text").isJsonPrimitive()) {
                prefix = message.get("text").getAsString();
            }
            if (message.has("stash") && !message.get("stash").isJsonNull() && message.get("stash").isJsonPrimitive()) {
                suffix = message.get("stash").getAsString();
            }
            String combined = prefix + suffix;
            if (!combined.isBlank()) {
                return combined;
            }
        }
        // 实时增量文本
        if (message.has("delta")) {
            JsonElement d = message.get("delta");
            if (d.isJsonPrimitive()) {
                return d.getAsString();
            }
            if (d.isJsonObject()) {
                JsonObject o = d.getAsJsonObject();
                if (o.has("text") && !o.get("text").isJsonNull()) {
                    return o.get("text").getAsString();
                }
                if (o.has("transcript") && !o.get("transcript").isJsonNull()) {
                    return o.get("transcript").getAsString();
                }
            }
        }
        //嵌套在 item 对象中
        if (message.has("item") && message.get("item").isJsonObject()) {
            JsonObject item = message.getAsJsonObject("item");
            if (item.has("transcript") && !item.get("transcript").isJsonNull()) {
                return item.get("transcript").getAsString();
            }
        }
        return null;
    }

    /**
     * 内部类，保存会话数据。
     */
    private static class AsrSession {
        private final OmniRealtimeConversation conversation;
        private final Consumer<String> onFinal;
        private final Consumer<String> onPartial;
        private final Consumer<Throwable> onError;
        private final CountDownLatch readyLatch = new CountDownLatch(1);

        AsrSession(
                OmniRealtimeConversation conversation,
                Consumer<String> onFinal,
                Consumer<String> onPartial,
                Consumer<Throwable> onError) {
            this.conversation = conversation;
            this.onFinal = onFinal;
            this.onPartial = onPartial;
            this.onError = onError;
        }

        public OmniRealtimeConversation getConversation() {
            return conversation;
        }

        public Consumer<Throwable> getOnError() {
            return onError;
        }

        void markReady() {
            readyLatch.countDown();
        }

        boolean isReady() {
            return readyLatch.getCount() == 0;
        }

        boolean awaitReady(long timeoutMs) throws InterruptedException {
            return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    // 配置 setter（供 Spring @Value 注入或测试使用）

    public void setUrl(String url) {
        this.url = url;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setEnableTurnDetection(Boolean enableTurnDetection) {
        this.enableTurnDetection = enableTurnDetection;
    }

    public void setTurnDetectionType(String turnDetectionType) {
        this.turnDetectionType = turnDetectionType;
    }

    public void setTurnDetectionThreshold(Float turnDetectionThreshold) {
        this.turnDetectionThreshold = turnDetectionThreshold;
    }

    public void setTurnDetectionSilenceDurationMs(Integer turnDetectionSilenceDurationMs) {
        this.turnDetectionSilenceDurationMs = turnDetectionSilenceDurationMs;
    }
}
