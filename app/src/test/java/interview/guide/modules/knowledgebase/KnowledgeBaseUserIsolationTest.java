package interview.guide.modules.knowledgebase;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识库模块用户隔离集成测试
 *
 * <p>验证知识库与 RAG 会话按用户隔离：
 * <ul>
 *   <li>同一 file_hash 允许属于不同用户（唯一约束为 (user_id, file_hash)）</li>
 *   <li>跨用户访问查不到他人知识库（按 id、按列表均隔离）</li>
 *   <li>RAG 会话列表按用户隔离，两用户互不可见</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KnowledgeBaseUserIsolationTest {

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private RagChatSessionRepository ragChatSessionRepository;

    @BeforeEach
    void cleanUp() {
        // H2 内存库在同测试类间共享（DB_CLOSE_DELAY=-1），先清理避免相互影响
        ragChatSessionRepository.deleteAll();
        knowledgeBaseRepository.deleteAll();
    }

    @Test
    void sameFileHashAllowedForDifferentUsers() {
        knowledgeBaseRepository.save(kb(1L, "hash-kb"));
        knowledgeBaseRepository.save(kb(2L, "hash-kb")); // 不抛唯一约束异常
        assertTrue(knowledgeBaseRepository.existsByUserIdAndFileHash(1L, "hash-kb"));
        assertTrue(knowledgeBaseRepository.existsByUserIdAndFileHash(2L, "hash-kb"));
    }

    @Test
    void crossUserKbInvisible() {
        KnowledgeBaseEntity a = knowledgeBaseRepository.save(kb(1L, "hash-a"));
        assertTrue(knowledgeBaseRepository.findByIdAndUserId(a.getId(), 1L).isPresent());
        assertTrue(knowledgeBaseRepository.findByIdAndUserId(a.getId(), 2L).isEmpty());
        assertEquals(0, knowledgeBaseRepository.findByUserIdOrderByUploadedAtDesc(2L).size());
    }

    @Test
    void crossUserRagSessionInvisible() {
        ragChatSessionRepository.save(session(1L, "用户1的会话"));
        ragChatSessionRepository.save(session(2L, "用户2的会话"));

        assertEquals(1, ragChatSessionRepository.findByUserIdOrderByUpdatedAtDesc(1L).size());
        assertEquals(1, ragChatSessionRepository.findByUserIdOrderByUpdatedAtDesc(2L).size());
        assertEquals("用户1的会话",
            ragChatSessionRepository.findByUserIdOrderByUpdatedAtDesc(1L).getFirst().getTitle());
        assertEquals("用户2的会话",
            ragChatSessionRepository.findByUserIdOrderByUpdatedAtDesc(2L).getFirst().getTitle());
    }

    private KnowledgeBaseEntity kb(Long userId, String fileHash) {
        return KnowledgeBaseEntity.builder().userId(userId).fileHash(fileHash)
                .name("测试库").originalFilename("a.pdf")
                .uploadedAt(LocalDateTime.now()).build();
    }

    private RagChatSessionEntity session(Long userId, String title) {
        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setUserId(userId);
        session.setTitle(title);
        return session;
    }
}
