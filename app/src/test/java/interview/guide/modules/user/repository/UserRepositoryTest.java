package interview.guide.modules.user.repository;

import interview.guide.modules.user.model.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void saveAndFindByUserid() {
        UserEntity entity = userRepository.save(UserEntity.builder()
                .userid("ABCD2345").username("张三").password("bcrypt-hash")
                .status(1).build());
        assertNotNull(entity.getId());
        assertNotNull(entity.getCreatetime());

        Optional<UserEntity> found = userRepository.findByUserid("ABCD2345");
        assertTrue(found.isPresent());
        assertEquals("张三", found.get().getUsername());
        assertTrue(userRepository.existsByUserid("ABCD2345"));
        assertTrue(userRepository.findByUserid("NOPE1234").isEmpty());
    }
}
