package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, String> {

  List<LlmProviderEntity> findByEnabledTrueOrderByIdAsc();

  List<LlmProviderEntity> findByUserIdOrderByIdAsc(Long userId);

  Optional<LlmProviderEntity> findByIdAndUserId(String id, Long userId);

  boolean existsByUserId(Long userId);
}
