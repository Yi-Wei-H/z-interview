package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.user.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 语音面试服务（Voice Interview Service）
 * 语音面试服务
 * <p>
 * 提供语音面试会话管理的业务逻辑，包括：
 * - 会话生命周期管理（创建、结束、查询）
 * - 阶段切换与状态跟踪
 * - 消息持久化与对话历史
 * - 活跃会话的 Redis 缓存
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewService {

    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final RedissonClient redissonClient;
    private final VoiceInterviewProperties properties;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;

    private static final String SESSION_CACHE_KEY_PREFIX = "voice:interview:session:";
    private static final int CACHE_TTL_HOURS = 1;

    /**
     * 创建一个新的语音面试会话
     * 创建新的语音面试会话
     *
     * @param request 包含角色类型与阶段配置的会话创建请求
     * @return SessionResponseDTO，含会话详情与 WebSocket URL
     */
    @Transactional
    public SessionResponseDTO createSession(CreateSessionRequest request) {
        String effectiveSkillId = request.getSkillId() != null ? request.getSkillId() : InterviewDefaults.SKILL_ID;
        String effectiveLlmProvider = (request.getLlmProvider() != null && !request.getLlmProvider().isBlank())
            ? request.getLlmProvider()
            : null;

        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                .userId(UserContext.requireUserId())
                .roleType(effectiveSkillId)
                .skillId(effectiveSkillId)
                .difficulty(request.getDifficulty() != null ? request.getDifficulty() : InterviewDefaults.DIFFICULTY)
                .customJdText(request.getCustomJdText())
                .resumeId(request.getResumeId())
                .introEnabled(request.getIntroEnabled())
                .techEnabled(request.getTechEnabled())
                .projectEnabled(request.getProjectEnabled())
                .hrEnabled(request.getHrEnabled())
                .llmProvider(effectiveLlmProvider)
                .plannedDuration(request.getPlannedDuration())
                .currentPhase(determineFirstPhase(request))
                .build();

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        cacheSession(saved);

        log.info("已创建语音面试会话: sessionId={}, skillId={}, phase={}",
                saved.getId(), effectiveSkillId, saved.getCurrentPhase());

        return buildSessionResponse(saved);
    }

    /**
     * 仅当会话处于 IN_PROGRESS 状态时结束，用于 WebSocket 异常断开的兜底。
     * 正常结束的 endSession 已设为 COMPLETED，此方法不会重复操作。
     */
    @Transactional
    public void endSessionIfInProgress(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong).orElse(null);
        if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            return;
        }
        log.info("WebSocket 断开后自动结束 IN_PROGRESS 会话 {}", sessionId);
        endSession(session);
    }

    /**
     * 按 ID 获取会话并校验当前登录用户属主，非属主按不存在处理（404 语义）
     *
     * @param sessionId Long 类型的会话 ID
     * @return 当前用户拥有的 VoiceInterviewSessionEntity
     */
    public VoiceInterviewSessionEntity getOwnedSession(Long sessionId) {
        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        if (!UserContext.requireUserId().equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId);
        }
        return session;
    }

    /**
     * 结束面试会话，并更新会话状态
     * 结束面试会话并更新状态
     *
     * @param sessionId 会话 ID（字符串格式，内部会转换为 Long）
     */
    @Transactional
    public void endSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("会话不存在: sessionId={}", sessionId);
            return;
        }

        endSession(session);
        // 评估任务归属会话所属用户（本方法可能在 WS 线程执行，ThreadLocal 不可靠）
        sendEvaluateTaskAfterCommit(sessionIdLong, session.getUserId());
    }

    private void endSession(VoiceInterviewSessionEntity session) {
        session.setEndTime(LocalDateTime.now());
        session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
        session.setStatus(VoiceInterviewSessionStatus.COMPLETED);
        session.setActualDuration((int) Duration.between(session.getStartTime(), LocalDateTime.now()).toSeconds());
        session.setEvaluateStatus(AsyncTaskStatus.PENDING);

        sessionRepository.save(session);
        invalidateSessionCache(session.getId());

        log.info("已结束语音面试会话: sessionId={}, 时长 {} 秒，已触发评估",
                session.getId(), session.getActualDuration());
    }

    /**
     * 按 ID 获取会话，带 Redis 缓存回退
     * 通过ID获取会话，支持Redis缓存
     *
     * @param sessionId 会话 ID（字符串格式，内部会转换为 Long）
     * @return VoiceInterviewSessionEntity，不存在时返回 null
     */
    public VoiceInterviewSessionEntity getSession(String sessionId) {
        return getSession(parseSessionId(sessionId));
    }

    /**
     * 按 ID 获取会话，带 Redis 缓存回退
     * 通过ID获取会话，支持Redis缓存
     *
     * @param sessionId Long 类型的会话 ID
     * @return VoiceInterviewSessionEntity，不存在时返回 null
     */
    public VoiceInterviewSessionEntity getSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }

        // 先查缓存
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        VoiceInterviewSessionEntity cached = bucket.get();

        if (cached != null) {
            log.debug("会话 {} 命中缓存", sessionId);
            return cached;
        }

        // 缓存未命中，回退到数据库
        return sessionRepository.findById(sessionId).orElse(null);
    }

    /**
     * 开启一个新的面试阶段
     * 开始新的面试阶段
     *
     * @param sessionId 会话 ID（字符串格式）
     * @param phaseStr  阶段字符串（INTRO、TECH、PROJECT、HR）
     */
    @Transactional
    public void startPhase(String sessionId, String phaseStr) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("无法开始阶段——会话不存在: sessionId={}", sessionId);
            return;
        }

        try {
            VoiceInterviewSessionEntity.InterviewPhase newPhase =
                    VoiceInterviewSessionEntity.InterviewPhase.valueOf(phaseStr.toUpperCase());

            VoiceInterviewSessionEntity.InterviewPhase oldPhase = session.getCurrentPhase();
            session.setCurrentPhase(newPhase);
            sessionRepository.save(session);
            cacheSession(session); // 同步更新缓存

            log.info("会话 {} 阶段已从 {} 切换为 {}", sessionId, oldPhase, newPhase);

        } catch (IllegalArgumentException e) {
            log.error("非法的阶段字符串: {}", phaseStr, e);
        }
    }

    /**
     * 获取会话当前所处阶段
     * 获取会话当前阶段
     *
     * @param sessionId 会话 ID（字符串格式）
     * @return 当前 InterviewPhase，会话不存在时返回 null
     */
    public VoiceInterviewSessionEntity.InterviewPhase getCurrentPhase(String sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        return session != null ? session.getCurrentPhase() : null;
    }

    /**
     * 将对话消息（用户与 AI 文本）保存到数据库
     * 保存对话消息（用户和AI文本）到数据库
     *
     * @param sessionId 会话 ID（字符串格式）
     * @param userText  用户语音识别出的文本
     * @param aiText    AI 生成的回答文本
     */
    @Transactional
    public void saveMessage(String sessionId, String userText, String aiText) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("无法保存消息——会话不存在: sessionId={}", sessionId);
            return;
        }

        String normalizedUserText = VoiceInterviewMessageEntity.trimToNull(userText);
        String normalizedAiText = VoiceInterviewMessageEntity.trimToNull(aiText);

        boolean answerAttached = normalizedUserText != null
            && fillLatestUnansweredQuestion(sessionIdLong, normalizedUserText);
        if (normalizedAiText == null) {
            return;
        }

        VoiceInterviewMessageEntity message = VoiceInterviewMessageEntity.builder()
                .sessionId(sessionIdLong)
                .userId(session.getUserId())
                .messageType("DIALOGUE")
                .phase(session.getCurrentPhase())
                .userRecognizedText(normalizedUserText != null && !answerAttached
                    ? normalizedUserText
                    : null)
                .aiGeneratedText(normalizedAiText)
                .sequenceNum(getNextSequenceNum(sessionIdLong))
                .build();

        messageRepository.save(message);
        log.debug("已保存会话消息: sessionId={}, phase={}, sequence={}",
                sessionId, session.getCurrentPhase(), message.getSequenceNum());
    }

    private boolean fillLatestUnansweredQuestion(Long sessionId, String userText) {
        return messageRepository
            .findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
                sessionId)
            .map(message -> {
                message.setUserRecognizedText(userText);
                messageRepository.save(message);
                log.debug("已回填语音消息的用户回答: sessionId={}, sequence={}",
                    sessionId, message.getSequenceNum());
                return true;
            })
            .orElse(false);
    }

    /**
     * 获取会话的对话历史
     * 获取会话的对话历史记录
     *
     * @param sessionId 会话 ID（字符串格式）
     * @return 按序号排序的消息列表
     */
    public List<VoiceInterviewMessageEntity> getConversationHistory(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        return messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionIdLong);
    }

    /**
     * 以 DTO 形式获取对话历史（供前端使用）
     */
    public List<VoiceInterviewMessageDTO> getConversationHistoryDTO(String sessionId) {
        return getConversationHistory(sessionId).stream()
            .map(msg -> VoiceInterviewMessageDTO.builder()
                .id(msg.getId())
                .sessionId(msg.getSessionId())
                .messageType(msg.getMessageType())
                .phase(msg.getPhase() != null ? msg.getPhase().name() : null)
                .userRecognizedText(msg.getUserRecognizedText())
                .aiGeneratedText(msg.getAiGeneratedText())
                .timestamp(msg.getTimestamp())
                .sequenceNum(msg.getSequenceNum())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * 将面试会话置为暂停
     * 暂停面试会话
     *
     * @param sessionId 会话 ID
     * @param reason 暂停原因（user_initiated 或 timeout）
     */
    @Transactional
    public void pauseSession(String sessionId, String reason) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));

        if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "会话状态为 " + session.getStatus() + "，无法暂停"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());

        sessionRepository.save(session);
        invalidateSessionCache(sessionIdLong);

        log.info("会话 {} 已暂停: reason={}", sessionId, reason);
    }

    /**
     * 恢复面试会话（resume）
     * 恢复面试会话
     *
     * @param sessionId 会话 ID
     * @return SessionResponseDTO，含 WebSocket URL
     */
    @Transactional
    public SessionResponseDTO resumeSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));

        if (session.getStatus() != VoiceInterviewSessionStatus.PAUSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "会话状态为 " + session.getStatus() + "，无法恢复"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        session.setResumedAt(LocalDateTime.now());

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        cacheSession(saved);

        log.info("会话 {} 已恢复，对话历史共 {} 条消息",
            sessionId, messageRepository.countBySessionId(sessionIdLong));

        return buildSessionResponse(saved);
    }

    /**
     * 获取某用户的全部会话
     * 获取用户所有会话
     *
     * @param userId 用户 ID（当前登录用户）
     * @param status 按状态过滤（可选）
     * @return 会话元数据列表
     */
    public List<SessionMetaDTO> getAllSessions(Long userId, String status) {
        List<VoiceInterviewSessionEntity> sessions;
        if (status != null && !status.isEmpty()) {
            VoiceInterviewSessionStatus statusEnum =
                VoiceInterviewSessionStatus.valueOf(status.toUpperCase());
            sessions = sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, statusEnum);
        } else {
            sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        }

        return sessions.stream()
            .map(session -> SessionMetaDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .status(session.getStatus().name())
                .currentPhase(session.getCurrentPhase().name())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .actualDuration(session.getActualDuration())
                .messageCount(messageRepository.countBySessionId(session.getId()))
                .evaluateStatus(session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null)
                .evaluateError(session.getEvaluateError())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * 按 ID 获取会话 DTO
     * 通过ID获取会话DTO
     *
     * @param sessionId Long 类型的会话 ID
     * @return SessionResponseDTO，含会话详情；不存在时返回 null
     */
    public SessionResponseDTO getSessionDTO(Long sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);

        if (session == null) {
            return null;
        }

        return buildSessionResponse(session);
    }

    /**
     * 基于时长与问题数量判断会话是否应切换到下一阶段
     * 检查是否应该转换到下一个阶段（基于时长和问题数量）
     *
     * @param session        当前会话
     * @param phaseStartTime 当前阶段开始时间
     * @param questionCount  当前阶段已提问的问题数量
     * @return 应切换返回 true，否则返回 false
     */
    public boolean shouldTransitionToNextPhase(VoiceInterviewSessionEntity session,
                                                LocalDateTime phaseStartTime,
                                                int questionCount) {
        VoiceInterviewSessionEntity.InterviewPhase currentPhase = session.getCurrentPhase();
        if (currentPhase == null || currentPhase == VoiceInterviewSessionEntity.InterviewPhase.COMPLETED) {
            return false;
        }

        Duration phaseDuration = Duration.between(phaseStartTime, LocalDateTime.now());
        VoiceInterviewProperties.DurationConfig config = getPhaseConfig(currentPhase);

        // 规则 1：达到最大时长（强制切换）
        if (phaseDuration.toMinutes() >= config.getMaxDuration()) {
            log.info("阶段 {} 已达最大时长 {} 分钟，强制切换",
                    currentPhase, config.getMaxDuration());
            return true;
        }

        // 规则 2：达到最少问题数且信息收集充分（由 AI 判断）
        // MVP 阶段先用基于问题数量的简单启发式判断
        if (questionCount >= config.getMaxQuestions()) {
            log.info("阶段 {} 已达最大问题数 {}，建议切换",
                    currentPhase, config.getMaxQuestions());
            return true;
        }

        // 规则 3：达到建议时长且问题数达到下限
        if (phaseDuration.toMinutes() >= config.getSuggestedDuration()
                && questionCount >= config.getMinQuestions()) {
            log.info("阶段 {} 已达建议时长 {} 且已有 {} 个问题，建议切换",
                    currentPhase, config.getSuggestedDuration(), questionCount);
            return true;
        }

        return false;
    }

    /**
     * 获取当前阶段之后下一个已启用的阶段
     * 获取当前阶段之后的下一个启用的阶段
     *
     * @param session 当前会话
     * @return 下一个 InterviewPhase；没有更多阶段时返回 COMPLETED
     */
    public VoiceInterviewSessionEntity.InterviewPhase getNextPhase(VoiceInterviewSessionEntity session) {
        VoiceInterviewSessionEntity.InterviewPhase current = session.getCurrentPhase();
        if (current == null) {
            return getFirstEnabledPhase(session);
        }

        return switch (current) {
            case INTRO -> session.getTechEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.TECH :
                    session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT :
                            session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                                    VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case TECH -> session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT :
                    session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                            VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case PROJECT -> session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                    VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case HR, COMPLETED -> VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
        };
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据已启用的阶段确定第一个阶段
     * 根据启用的阶段确定第一个阶段
     */
    private VoiceInterviewSessionEntity.InterviewPhase determineFirstPhase(CreateSessionRequest request) {
        if (request.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (request.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (request.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (request.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    /**
     * 获取会话中第一个已启用的阶段
     */
    private VoiceInterviewSessionEntity.InterviewPhase getFirstEnabledPhase(VoiceInterviewSessionEntity session) {
        if (session.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (session.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (session.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (session.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    private SessionResponseDTO buildSessionResponse(VoiceInterviewSessionEntity session) {
        return SessionResponseDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .currentPhase(session.getCurrentPhase().name())
                .status(session.getStatus().name())
                .startTime(session.getStartTime())
                .plannedDuration(session.getPlannedDuration())
                .webSocketUrl(String.format("ws://localhost:8080/ws/voice-interview/%d", session.getId()))
                .build();
    }

    /**
     * 从配置中读取阶段时长配置
     */
    private VoiceInterviewProperties.DurationConfig getPhaseConfig(VoiceInterviewSessionEntity.InterviewPhase phase) {
        return switch (phase) {
            case INTRO -> properties.getPhase().getIntro();
            case TECH -> properties.getPhase().getTech();
            case PROJECT -> properties.getPhase().getProject();
            case HR -> properties.getPhase().getHr();
            default -> new VoiceInterviewProperties.DurationConfig(0, 0, 0, 0, 0);
        };
    }

    /**
     * 生成会话内下一条消息的序号
     */
    private int getNextSequenceNum(Long sessionId) {
        return (int) messageRepository.countBySessionId(sessionId) + 1;
    }

    /**
     * 更新会话实体上的评估状态（Producer/Consumer/Controller 共用）
     */
    public void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
        try {
            sessionRepository.findById(sessionId).ifPresent(session -> {
                session.setEvaluateStatus(status);
                session.setEvaluateError(error);
                sessionRepository.save(session);
                log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
            });
        } catch (Exception e) {
            log.error("更新评估状态失败: sessionId={}, status={}, error={}",
                    sessionId, status, e.getMessage(), e);
        }
    }

    /**
     * 为会话触发异步评估（由 Controller 调用）
     */
    @Transactional
    public void triggerEvaluation(Long sessionId) {
        VoiceInterviewSessionEntity session = getOwnedSession(sessionId);
        updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        sendEvaluateTaskAfterCommit(sessionId, session.getUserId());
    }

    private void sendEvaluateTaskAfterCommit(Long sessionId, Long userId) {
        Runnable sendTask = () -> voiceEvaluateStreamProducer.sendEvaluateTask(sessionId, userId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendTask.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendTask.run();
            }
        });
    }

    /**
     * 删除语音面试会话及其关联的消息和评估记录
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }
        evaluationRepository.findBySessionId(sessionId).ifPresent(evaluationRepository::delete);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        log.info("已删除语音面试会话: sessionId={}", sessionId);
    }

    /**
     * 将会话写入 Redis 缓存
     */
    private void cacheSession(VoiceInterviewSessionEntity session) {
        String cacheKey = getSessionCacheKey(session.getId());
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.set(session, Duration.ofHours(CACHE_TTL_HOURS));
        log.debug("会话已写入缓存: sessionId={}", session.getId());
    }

    /**
     * 使 Redis 中的会话缓存失效
     */
    private void invalidateSessionCache(Long sessionId) {
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.delete();
        log.debug("会话缓存已失效: sessionId={}", sessionId);
    }

    /**
     * 生成会话的 Redis 缓存键
     */
    private String getSessionCacheKey(Long sessionId) {
        return SESSION_CACHE_KEY_PREFIX + sessionId;
    }

    /**
     * 将 session id 从 String 解析为 Long，带错误处理
     */
    private Long parseSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            log.error("会话 ID 格式非法: {}", sessionId, e);
            return null;
        }
    }

    /**
     * 清理超时的 IN_PROGRESS 会话和卡住的 PROCESSING 评估。
     * 由 @Scheduled 在 WebSocketHandler 中定时触发。
     */
    @Transactional
    public int cleanupStaleSessions() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusHours(2);

        List<VoiceInterviewSessionEntity> staleSessions = sessionRepository
            .findByStatusAndStartTimeBefore(VoiceInterviewSessionStatus.IN_PROGRESS, staleThreshold);

        int cleaned = 0;
        for (VoiceInterviewSessionEntity session : staleSessions) {
            log.info("清理超时的 IN_PROGRESS 会话 {}: startTime={}",
                session.getId(), session.getStartTime());
            endSession(session);
            cleaned++;
        }

        LocalDateTime evalStaleThreshold = LocalDateTime.now().minusMinutes(30);
        List<VoiceInterviewSessionEntity> stuckEvals = sessionRepository
            .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PROCESSING, evalStaleThreshold);

        for (VoiceInterviewSessionEntity session : stuckEvals) {
            log.info("重置卡住的 PROCESSING 评估: sessionId={}", session.getId());
            session.setEvaluateStatus(AsyncTaskStatus.FAILED);
            session.setEvaluateError("评估超时，请重新触发");
            sessionRepository.save(session);
            cleaned++;
        }

        return cleaned;
    }
}
