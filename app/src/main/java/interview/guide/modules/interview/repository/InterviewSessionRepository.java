package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试会话Repository
 * 所有查询均带 userId 维度（多用户隔离：跨用户访问统一 miss → 404）
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

    /**
     * 根据会话ID和归属用户查找
     */
    Optional<InterviewSessionEntity> findBySessionIdAndUserId(String sessionId, Long userId);

    /**
     * 根据会话ID和归属用户查找（同时加载关联的简历）
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId AND s.userId = :userId")
    Optional<InterviewSessionEntity> findBySessionIdAndUserIdWithResume(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    /**
     * 根据归属用户和简历查找所有面试记录
     */
    List<InterviewSessionEntity> findByUserIdAndResumeOrderByCreatedAtDesc(Long userId, ResumeEntity resume);

    /**
     * 根据归属用户和简历ID查找所有面试记录
     */
    List<InterviewSessionEntity> findByUserIdAndResumeIdOrderByCreatedAtDesc(Long userId, Long resumeId);

    /**
     * 根据归属用户和简历ID查找最近的面试记录（用于历史题去重）
     */
    List<InterviewSessionEntity> findTop10ByUserIdAndResumeIdOrderByCreatedAtDesc(Long userId, Long resumeId);

    /**
     * 查找归属用户的未完成面试（CREATED或IN_PROGRESS状态）
     */
    Optional<InterviewSessionEntity> findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
        Long userId,
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 根据归属用户、简历ID和状态查找会话
     */
    Optional<InterviewSessionEntity> findByUserIdAndResumeIdAndStatusIn(
        Long userId,
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 查找归属用户的所有面试会话（按创建时间倒序）
     */
    List<InterviewSessionEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 根据归属用户和 skillId 查找最近的面试记录（用于通用模式历史题去重）
     */
    List<InterviewSessionEntity> findTop10ByUserIdAndSkillIdOrderByCreatedAtDesc(Long userId, String skillId);

    /**
     * 根据归属用户 + resumeId + skillId 查找最近的面试记录（精确匹配）
     */
    List<InterviewSessionEntity> findTop10ByUserIdAndResumeIdAndSkillIdOrderByCreatedAtDesc(Long userId, Long resumeId, String skillId);
}
