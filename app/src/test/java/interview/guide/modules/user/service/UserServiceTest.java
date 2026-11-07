package interview.guide.modules.user.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.dto.*;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private TokenService tokenService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, new BCryptPasswordEncoder(), tokenService);
    }

    @Test
    void registerGeneratesRandomUseridAndBcryptPassword() {
        when(userRepository.existsByUserid(anyString())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserDTO dto = userService.register(new RegisterRequest("password123", "  张三  "));

        assertEquals(8, dto.userid().length());
        assertEquals("张三", dto.username());
        verify(userRepository).save(argThat(e -> e.getPassword().startsWith("$2") && e.getStatus() == 1));
    }

    @Test
    void registerRejectsShortPassword() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register(new RegisterRequest("short", null)));
        assertEquals(ErrorCode.PASSWORD_TOO_SHORT.getCode(), ex.getCode());
    }

    @Test
    void registerBlankUsernameBecomesNull() {
        when(userRepository.existsByUserid(anyString())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserDTO dto = userService.register(new RegisterRequest("password123", "   "));
        assertNull(dto.username());
    }

    @Test
    void loginSuccessReturnsToken() {
        UserEntity entity = UserEntity.builder().userid("ABCD2345")
                .password(new BCryptPasswordEncoder().encode("password123")).status(1).build();
        when(userRepository.findByUserid("ABCD2345")).thenReturn(Optional.of(entity));
        when(tokenService.create(entity.getId())).thenReturn("token-1");

        LoginResponse response = userService.login("ABCD2345", "password123");
        assertEquals("token-1", response.token());
        assertEquals("ABCD2345", response.user().userid());
    }

    @Test
    void loginWrongPasswordAndUnknownUserSameMessage() {
        UserEntity entity = UserEntity.builder().userid("ABCD2345")
                .password(new BCryptPasswordEncoder().encode("password123")).status(1).build();
        when(userRepository.findByUserid("ABCD2345")).thenReturn(Optional.of(entity));
        when(userRepository.findByUserid("NOPE9999")).thenReturn(Optional.empty());

        assertEquals(ErrorCode.USERID_OR_PASSWORD_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> userService.login("ABCD2345", "wrong")).getCode());
        assertEquals(ErrorCode.USERID_OR_PASSWORD_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> userService.login("NOPE9999", "whatever")).getCode());
    }

    @Test
    void loginDisabledUserRejected() {
        UserEntity entity = UserEntity.builder().userid("ABCD2345")
                .password(new BCryptPasswordEncoder().encode("password123")).status(0).build();
        when(userRepository.findByUserid("ABCD2345")).thenReturn(Optional.of(entity));
        assertEquals(ErrorCode.USER_DISABLED.getCode(),
                assertThrows(BusinessException.class, () -> userService.login("ABCD2345", "password123")).getCode());
    }

    @Test
    void changePasswordRequiresCorrectOldPassword() {
        UserEntity entity = UserEntity.builder().id(1L).userid("ABCD2345")
                .password(new BCryptPasswordEncoder().encode("password123")).status(1).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertEquals(ErrorCode.OLD_PASSWORD_MISMATCH.getCode(), assertThrows(BusinessException.class,
                () -> userService.changePassword(1L, "wrong-old", "newpassword9")).getCode());
        userService.changePassword(1L, "password123", "newpassword9");
        verify(userRepository).save(argThat(e -> new BCryptPasswordEncoder()
                .matches("newpassword9", e.getPassword())));
    }
}
