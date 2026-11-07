package interview.guide.modules.resume;

import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 简历模块用户隔离集成测试
 *
 * <p>验证简历数据按用户隔离：
 * <ul>
 *   <li>同一 file_hash 允许属于不同用户（唯一约束为 (user_id, file_hash)）</li>
 *   <li>跨用户访问查不到他人简历（按 id、按列表均隔离）</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ResumeUserIsolationTest {

    @Autowired
    private ResumeRepository resumeRepository;

    @BeforeEach
    void cleanUp() {
        // H2 内存库在同测试类间共享（DB_CLOSE_DELAY=-1），先清理避免相互影响
        resumeRepository.deleteAll();
    }

    @Test
    void sameFileHashAllowedForDifferentUsers() {
        resumeRepository.save(resume(1L, "hash-x"));
        resumeRepository.save(resume(2L, "hash-x")); // 不抛唯一约束异常
        assertTrue(resumeRepository.existsByUserIdAndFileHash(1L, "hash-x"));
        assertTrue(resumeRepository.existsByUserIdAndFileHash(2L, "hash-x"));
    }

    @Test
    void crossUserAccessSeesNothing() {
        ResumeEntity a = resumeRepository.save(resume(1L, "hash-a"));
        assertTrue(resumeRepository.findByIdAndUserId(a.getId(), 1L).isPresent());
        assertTrue(resumeRepository.findByIdAndUserId(a.getId(), 2L).isEmpty());
        assertEquals(0, resumeRepository.findByUserIdOrderByUploadedAtDesc(2L).size());
    }

    private ResumeEntity resume(Long userId, String fileHash) {
        return ResumeEntity.builder().userId(userId).fileHash(fileHash)
                .originalFilename("a.pdf").uploadedAt(java.time.LocalDateTime.now()).build();
    }
}
