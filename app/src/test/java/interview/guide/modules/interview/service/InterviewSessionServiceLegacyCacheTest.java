package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.user.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧缓存条目归属语义单测（最终审查修复回归）：
 * <ul>
 *   <li>缓存条目 userId=null（升级窗口遗留，含 resumeText）→ 视为未命中，走 DB 恢复并按 entity.userId 重建缓存（自愈），属主本人不 404</li>
 *   <li>缓存条目 userId 非 null 且非属主 → 直接 404，不落 DB</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewSessionServiceLegacyCacheTest {

    @Mock
    private InterviewQuestionService questionService;
    @Mock
    private AnswerEvaluationService evaluationService;
    @Mock
    private InterviewPersistenceService persistenceService;
    @Mock
    private InterviewSessionCache sessionCache;
    @Mock
    private EvaluateStreamProducer evaluateStreamProducer;
    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    private InterviewSessionService service;

    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "sid-legacy";

    @BeforeEach
    void setUp() {
        service = new InterviewSessionService(
            questionService, evaluationService, persistenceService, sessionCache,
            new ObjectMapper(), evaluateStreamProducer, llmProviderRegistry);
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CachedSession cache(Long userId) {
        CachedSession cached = new CachedSession();
        cached.setSessionId(SESSION_ID);
        cached.setUserId(userId);
        cached.setResumeText("legacy-resume-text");
        cached.setQuestionsJson("[]");
        cached.setCurrentIndex(0);
        cached.setStatus(InterviewSessionDTO.SessionStatus.IN_PROGRESS);
        return cached;
    }

    private InterviewSessionEntity entity() {
        return InterviewSessionEntity.builder()
            .userId(USER_ID)
            .sessionId(SESSION_ID)
            .questionsJson("[]")
            .status(InterviewSessionEntity.SessionStatus.IN_PROGRESS)
            .build();
    }

    @Test
    @DisplayName("旧缓存 userId=null 视为未命中：走 DB 恢复并按 entity.userId 重建缓存")
    void legacyNullUserCacheFallsBackToDatabaseRestore() {
        CachedSession rebuilt = cache(USER_ID);
        when(sessionCache.getSession(SESSION_ID))
            .thenReturn(Optional.of(cache(null)))   // 首次：升级窗口遗留的 null userId 条目
            .thenReturn(Optional.of(rebuilt));      // 恢复后回读重建条目
        when(persistenceService.findBySessionIdAndUserId(SESSION_ID, USER_ID))
            .thenReturn(Optional.of(entity()));
        when(persistenceService.findAnswersBySessionId(SESSION_ID)).thenReturn(List.of());

        InterviewSessionDTO dto = service.getSession(SESSION_ID);

        assertEquals(SESSION_ID, dto.sessionId());
        // 走了 DB 恢复（而非 404），并以属主 userId 重建缓存条目（自愈）
        verify(persistenceService).findBySessionIdAndUserId(SESSION_ID, USER_ID);
        verify(sessionCache).saveSession(eq(USER_ID), eq(SESSION_ID), anyString(), any(),
            any(), anyInt(), any());
    }

    @Test
    @DisplayName("缓存存在但非属主：直接 404，不落 DB 恢复")
    void foreignOwnedCacheStillRejected() {
        when(sessionCache.getSession(SESSION_ID)).thenReturn(Optional.of(cache(999L)));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.getSession(SESSION_ID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        verify(persistenceService, never()).findBySessionIdAndUserId(anyString(), any());
    }
}
