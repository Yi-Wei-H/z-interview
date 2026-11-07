package interview.guide.modules.voiceinterview;

import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语音面试会话用户隔离集成测试
 *
 * <p>验证语音面试会话列表按 userId 隔离：B 查不到 A 的会话。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VoiceSessionUserIsolationTest {

    @Autowired
    private VoiceInterviewSessionRepository sessionRepository;

    @BeforeEach
    void cleanUp() {
        // H2 内存库在同测试类间共享（DB_CLOSE_DELAY=-1），先清理避免相互影响
        sessionRepository.deleteAll();
    }

    @Test
    void sessionListIsolatedByUser() {
        sessionRepository.save(session(1L));
        sessionRepository.save(session(2L));

        var user1Sessions = sessionRepository.findByUserIdOrderByStartTimeDesc(1L);
        var user2Sessions = sessionRepository.findByUserIdOrderByStartTimeDesc(2L);

        assertEquals(1, user1Sessions.size());
        assertEquals(1L, user1Sessions.get(0).getUserId());
        assertEquals(1, user2Sessions.size());
        assertEquals(2L, user2Sessions.get(0).getUserId());
        // B 的列表里绝不能出现 A 的会话
        assertFalse(user2Sessions.stream().anyMatch(s -> s.getUserId().equals(1L)));
        assertTrue(user1Sessions.stream().anyMatch(s -> s.getUserId().equals(1L)));
    }

    private VoiceInterviewSessionEntity session(Long userId) {
        return VoiceInterviewSessionEntity.builder()
                .userId(userId)
                .roleType("java-backend")
                .build();
    }
}
