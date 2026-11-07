package interview.guide.modules.voiceinterview.controller;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.result.Result;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationStatusDTO;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.user.context.UserContext;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 语音面试控制器（Voice Interview Controller）
 * 语音面试控制器
 * <p>
 * 语音面试会话管理的 REST API 端点：
 * - 会话生命周期（创建、查询、结束）
 * - 消息历史查询
 * - 异步评估触发与状态轮询
 * </p>
 * todo: 待解读 获取语音评估
 */
@RestController
@RequestMapping("/api/voice-interview")
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewController {

    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;

    /**
     * 创建新的语音面试会话
     */
    @PostMapping("/sessions")
    public Result<SessionResponseDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        log.info("创建语音面试会话: roleType={}", request.getRoleType());
        SessionResponseDTO session = voiceInterviewService.createSession(request);
        return Result.success(session);
    }

    /**
     * 按 ID 获取会话详情
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<SessionResponseDTO> getSession(@PathVariable Long sessionId) {
        log.info("获取会话详情: sessionId={}", sessionId);
        voiceInterviewService.getOwnedSession(sessionId); // 属主校验，非属主按 404 处理
        SessionResponseDTO session = voiceInterviewService.getSessionDTO(sessionId);
        if (session == null) {
            return Result.error("Session not found: " + sessionId);
        }
        return Result.success(session);
    }

    /**
     * 结束面试会话
     * <p>
     * 同时会通过 Redis Stream 触发异步评估。
     * </p>
     */
    @PostMapping("/sessions/{sessionId}/end")
    public Result<Void> endSession(@PathVariable Long sessionId) {
        log.info("结束会话: sessionId={}", sessionId);
        voiceInterviewService.getOwnedSession(sessionId); // 属主校验
        voiceInterviewService.endSession(sessionId.toString());
        return Result.success();
    }

    /**
     * 暂停面试会话
     */
    @PutMapping("/sessions/{sessionId}/pause")
    public Result<Void> pauseSession(
        @PathVariable Long sessionId,
        @RequestBody Map<String, String> request
    ) {
        log.info("暂停会话: sessionId={}", sessionId);
        voiceInterviewService.getOwnedSession(sessionId); // 属主校验
        String reason = request.getOrDefault("reason", "user_initiated");
        voiceInterviewService.pauseSession(sessionId.toString(), reason);
        return Result.success();
    }

    /**
     * 恢复面试会话
     */
    @PutMapping("/sessions/{sessionId}/resume")
    public Result<SessionResponseDTO> resumeSession(@PathVariable Long sessionId) {
        log.info("恢复会话: sessionId={}", sessionId);
        voiceInterviewService.getOwnedSession(sessionId); // 属主校验
        SessionResponseDTO session = voiceInterviewService.resumeSession(sessionId.toString());
        return Result.success(session);
    }

    /**
     * 获取当前登录用户的所有会话
     */
    @GetMapping("/sessions")
    public Result<List<SessionMetaDTO>> getAllSessions(
        @RequestParam(required = false) String status
    ) {
        Long userId = UserContext.requireUserId();
        log.info("获取用户会话列表: userId={}, status={}", userId, status);
        List<SessionMetaDTO> sessions = voiceInterviewService.getAllSessions(userId, status);
        return Result.success(sessions);
    }

    /**
     * 删除语音面试会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        log.info("删除语音面试会话: sessionId={}", sessionId);
        voiceInterviewService.getOwnedSession(sessionId); // 属主校验
        voiceInterviewService.deleteSession(sessionId);
        return Result.success();
    }

    /**
     * 获取会话的对话历史
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<VoiceInterviewMessageDTO>> getMessages(@PathVariable Long sessionId) {
        log.info("获取会话消息历史: sessionId={}", sessionId);
        voiceInterviewService.getOwnedSession(sessionId); // 属主校验
        List<VoiceInterviewMessageDTO> messages =
                voiceInterviewService.getConversationHistoryDTO(sessionId.toString());
        return Result.success(messages);
    }

    /**
     * 获取会话的评估状态与结果
     * <p>
     * 返回当前评估状态（PENDING/PROCESSING/COMPLETED/FAILED），
     * COMPLETED 时同时返回评估结果。
     * 前端轮询该端点直到评估完成。
     * </p>
     * todo: 待解读 生成语音评估
     */
    @GetMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusDTO> getEvaluation(@PathVariable Long sessionId) {
        log.info("获取会话评估状态: sessionId={}", sessionId);

        VoiceInterviewSessionEntity session = voiceInterviewService.getOwnedSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }

        AsyncTaskStatus status = session.getEvaluateStatus();
        VoiceEvaluationStatusDTO.VoiceEvaluationStatusDTOBuilder builder = VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(status != null ? status.name() : null)
                .evaluateError(session.getEvaluateError());

        if (status == AsyncTaskStatus.COMPLETED) {
            VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
            builder.evaluation(evaluation);
        }

        return Result.success(builder.build());
    }

    /**
     * 为会话触发异步评估
     * <p>
     * 将评估任务投递到 Redis Stream 后立即返回。
     * 前端随后轮询 GET /evaluation 跟踪进度。
     * 若评估已在进行或已完成，则返回当前状态。
     * </p>
     */
    @PostMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusDTO> generateEvaluation(@PathVariable Long sessionId) {
        log.info("触发异步评估: sessionId={}", sessionId);

        VoiceInterviewSessionEntity session = voiceInterviewService.getOwnedSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }

        // 已完成时直接返回缓存结果
        if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
            VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
            return Result.success(VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(AsyncTaskStatus.COMPLETED.name())
                    .evaluation(evaluation)
                    .build());
        }

        // 已在进行中时返回当前状态
        if (session.getEvaluateStatus() == AsyncTaskStatus.PENDING
                || session.getEvaluateStatus() == AsyncTaskStatus.PROCESSING) {
            return Result.success(VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(session.getEvaluateStatus().name())
                    .build());
        }

        // 通过服务触发新的异步评估
        voiceInterviewService.triggerEvaluation(sessionId);

        return Result.success(VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(AsyncTaskStatus.PENDING.name())
                .build());
    }
}
