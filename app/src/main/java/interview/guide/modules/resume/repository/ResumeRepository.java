package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 简历Repository
 * 所有查询均按归属用户隔离
 */
@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {

    /**
     * 按用户查询简历列表（上传时间倒序）
     */
    List<ResumeEntity> findByUserIdOrderByUploadedAtDesc(Long userId);

    /**
     * 按用户和ID查找简历（跨用户访问返回空）
     */
    Optional<ResumeEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 检查某用户的文件哈希是否存在（用户内去重）
     */
    boolean existsByUserIdAndFileHash(Long userId, String fileHash);

    /**
     * 根据用户和文件哈希查找简历（用户内去重）
     */
    Optional<ResumeEntity> findByUserIdAndFileHash(Long userId, String fileHash);
}
