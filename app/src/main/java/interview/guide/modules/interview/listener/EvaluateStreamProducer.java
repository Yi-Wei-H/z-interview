package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面试评估任务生产者
 * 负责发送评估任务到 Redis Stream（消息携带归属 userId，供消费线程恢复用户上下文）
 */
@Slf4j
@Component
public class EvaluateStreamProducer extends AbstractStreamProducer<EvaluateStreamProducer.EvaluateTaskPayload> {

    private final InterviewSessionRepository sessionRepository;
    private final TransactionalExecutor transactionalExecutor;

    // record 是 Java 14 正式引入的一种不可变数据载体，专门用于承载数据。它自动生成构造器、getter、equals()、hashCode() 和 toString() 方法。
    // 简而言之，存数据类，只携带数据，相当于一个实体类。
    record EvaluateTaskPayload(String sessionId, Long userId) {}

    public EvaluateStreamProducer(
        RedisService redisService,
        InterviewSessionRepository sessionRepository,
        TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.sessionRepository = sessionRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 发送评估任务到 Redis Stream
     *
     * @param userId    归属用户ID
     * @param sessionId 面试会话ID
     */
    public void sendEvaluateTask(Long userId, String sessionId) {
        sendTask(new EvaluateTaskPayload(sessionId, userId));
    }

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(EvaluateTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId(),
            AsyncTaskStreamConstants.FIELD_USER_ID, payload.userId().toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(EvaluateTaskPayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected void onSendFailed(EvaluateTaskPayload payload, String error) {
        transactionalExecutor.runRequiresNew(
            () -> updateEvaluateStatus(payload.userId(), payload.sessionId(), AsyncTaskStatus.FAILED, truncateError(error)));
    }

    /**
     * 更新评估状态
     */
    private void updateEvaluateStatus(Long userId, String sessionId, AsyncTaskStatus status, String error) {
        sessionRepository.findBySessionIdAndUserId(sessionId, userId).ifPresent(session -> {
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            sessionRepository.save(session);
        });
    }
}
