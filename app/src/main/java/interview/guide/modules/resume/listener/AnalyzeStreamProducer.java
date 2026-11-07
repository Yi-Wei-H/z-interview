package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 简历分析任务生产者
 * 负责发送分析任务到 Redis Stream
 */
@Slf4j
@Component
public class AnalyzeStreamProducer extends AbstractStreamProducer<AnalyzeStreamProducer.AnalyzeTaskPayload> {

    private final ResumeRepository resumeRepository;
    private final TransactionalExecutor transactionalExecutor;

    // record 是 Java 14 正式引入的一种不可变数据载体，专门用于承载数据。它自动生成构造器、getter、equals()、hashCode() 和 toString() 方法。
    // 简而言之，存数据类，只携带数据，相当于一个实体类。
    record AnalyzeTaskPayload(Long resumeId, String content, Long userId) {}

    public AnalyzeStreamProducer(
        RedisService redisService,
        ResumeRepository resumeRepository,
        TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.resumeRepository = resumeRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 发送分析任务到 Redis Stream
     *
     * @param userId   归属用户ID
     * @param resumeId 简历ID
     * @param content  简历内容
     */
    public void sendAnalyzeTask(Long userId, Long resumeId, String content) {
        sendTask(new AnalyzeTaskPayload(resumeId, content, userId));
    }

    @Override
    protected String taskDisplayName() {
        return "分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(AnalyzeTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_RESUME_ID, payload.resumeId().toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
            AsyncTaskStreamConstants.FIELD_USER_ID, payload.userId().toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(AnalyzeTaskPayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected void onSendFailed(AnalyzeTaskPayload payload, String error) {
        transactionalExecutor.runRequiresNew(
            () -> updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, truncateError(error)));
    }

    /**
     * 更新分析状态
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        resumeRepository.findById(resumeId).ifPresent(resume -> {
            resume.setAnalyzeStatus(status);
            if (error != null) {
                resume.setAnalyzeError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            resumeRepository.save(resume);
        });
    }
}
