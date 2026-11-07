package interview.guide.modules.user.repository;

import interview.guide.modules.user.model.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

  Optional<UserEntity> findByUserid(String userid);

  boolean existsByUserid(String userid);
}
