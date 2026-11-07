package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 知识库Repository
 * 所有列表/统计/查重方法均按用户隔离（userId 维度）
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    // ==================== 用户隔离查询 ====================

    /**
     * 按上传时间倒序查找当前用户的所有知识库
     */
    List<KnowledgeBaseEntity> findByUserIdOrderByUploadedAtDesc(Long userId);

    /**
     * 按ID + 用户查找知识库（属主校验，跨用户不可见）
     */
    Optional<KnowledgeBaseEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 检查当前用户内文件哈希是否存在（用户内去重）
     */
    boolean existsByUserIdAndFileHash(Long userId, String fileHash);

    /**
     * 根据文件哈希查找当前用户的知识库（用于去重）
     */
    Optional<KnowledgeBaseEntity> findByUserIdAndFileHash(Long userId, String fileHash);

    /**
     * 按ID集合 + 用户查找知识库（批量属主校验）
     */
    List<KnowledgeBaseEntity> findByIdInAndUserId(Collection<Long> ids, Long userId);

    /**
     * 按向量化状态查找当前用户的知识库（按上传时间倒序）
     */
    List<KnowledgeBaseEntity> findByUserIdAndVectorStatusOrderByUploadedAtDesc(Long userId, VectorStatus vectorStatus);

    // ==================== 分类 ====================

    /**
     * 获取当前用户的所有不同分类
     */
    @Query("SELECT DISTINCT k.category FROM KnowledgeBaseEntity k WHERE k.userId = :userId AND k.category IS NOT NULL ORDER BY k.category")
    List<String> findCategoriesByUserId(@Param("userId") Long userId);

    /**
     * 根据分类查找当前用户的知识库
     */
    List<KnowledgeBaseEntity> findByUserIdAndCategoryOrderByUploadedAtDesc(Long userId, String category);

    /**
     * 查找当前用户未分类的知识库
     */
    List<KnowledgeBaseEntity> findByUserIdAndCategoryIsNullOrderByUploadedAtDesc(Long userId);

    // ==================== 搜索 ====================

    /**
     * 按名称或文件名模糊搜索当前用户的知识库（不区分大小写）
     */
    @Query("SELECT k FROM KnowledgeBaseEntity k WHERE k.userId = :userId AND (LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(k.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%'))) ORDER BY k.uploadedAt DESC")
    List<KnowledgeBaseEntity> searchByKeyword(@Param("userId") Long userId, @Param("keyword") String keyword);

    // ==================== 批量更新 ====================

    /**
     * 批量增加知识库提问计数（调用方需先完成属主校验）
     * @param ids 知识库ID列表
     * @return 更新的行数
     */
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity k SET k.questionCount = k.questionCount + 1 WHERE k.id IN :ids")
    int incrementQuestionCountBatch(@Param("ids") List<Long> ids);

    // ==================== 统计查询（按用户） ====================

    /**
     * 统计当前用户知识库总数
     */
    long countByUserId(Long userId);

    /**
     * 统计当前用户总访问次数
     */
    @Query("SELECT COALESCE(SUM(k.accessCount), 0) FROM KnowledgeBaseEntity k WHERE k.userId = :userId")
    long sumAccessCountByUserId(@Param("userId") Long userId);

    /**
     * 按向量化状态统计当前用户的知识库数量
     */
    long countByUserIdAndVectorStatus(Long userId, VectorStatus vectorStatus);
}
