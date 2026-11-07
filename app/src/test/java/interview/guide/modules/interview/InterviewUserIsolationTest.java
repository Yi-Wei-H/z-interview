package interview.guide.modules.interview;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文字面试模块用户隔离集成测试
 *
 * <p>验证面试会话按用户隔离：
 * <ul>
 *   <li>跨用户按 sessionId 查不到他人会话（统一 404 语义的前提）</li>
 *   <li>面试记录列表按 userId 隔离，B 看不到 A 的会话</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InterviewUserIsolationTest {

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @BeforeEach
    void cleanUp() {
        // H2 内存库在同测试类间共享（DB_CLOSE_DELAY=-1），先清理避免相互影响
        sessionRepository.deleteAll();
    }

    @Test
    void crossUserSessionInvisible() {
        sessionRepository.save(session(1L, "sid-a"));
        assertTrue(sessionRepository.findBySessionIdAndUserId("sid-a", 1L).isPresent());
        assertTrue(sessionRepository.findBySessionIdAndUserId("sid-a", 2L).isEmpty());
    }

    @Test
    void sessionListIsolatedByUser() {
        sessionRepository.save(session(1L, "sid-a"));
        sessionRepository.save(session(2L, "sid-b"));

        var user1Sessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(1L);
        var user2Sessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(2L);

        assertEquals(1, user1Sessions.size());
        assertEquals("sid-a", user1Sessions.get(0).getSessionId());
        assertEquals(1, user2Sessions.size());
        assertEquals("sid-b", user2Sessions.get(0).getSessionId());
        // B 的列表里绝不能出现 A 的会话
        assertFalse(user2Sessions.stream().anyMatch(s -> "sid-a".equals(s.getSessionId())));
    }

    private InterviewSessionEntity session(Long userId, String sessionId) {
        return InterviewSessionEntity.builder()
                .userId(userId)
                .sessionId(sessionId)
                .status(InterviewSessionEntity.SessionStatus.IN_PROGRESS)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
