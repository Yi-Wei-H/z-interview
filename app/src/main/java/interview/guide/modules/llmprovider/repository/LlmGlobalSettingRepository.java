package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户在网页上改的
 * “动态配置中心”的数据库查询接口，负责读取用户通过 UI 界面选择的默认 Provider，并作为 YAML 静态配置的“覆盖层”。
 * 该表只有一条记录，用于记录用户选择的默认模型
 */
public interface LlmGlobalSettingRepository extends JpaRepository<LlmGlobalSettingEntity, Long> {
}
