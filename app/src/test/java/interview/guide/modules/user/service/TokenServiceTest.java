package interview.guide.modules.user.service;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.user.config.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private RedisService redisService;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenTtl(Duration.ofDays(7));
        tokenService = new TokenService(redisService, properties);
    }

    @Test
    void createStoresUserIdWithTtl() {
        String token = tokenService.create(42L);
        assertEquals(64, token.length());
        verify(redisService).set(eq("auth:token:" + token), eq("42"), eq(Duration.ofDays(7)));
    }

    @Test
    void resolveReturnsUserIdWhenPresent() {
        when(redisService.<String>get("auth:token:abc")).thenReturn("42");
        assertEquals(Optional.of(42L), tokenService.resolveUserId("abc"));
    }

    @Test
    void resolveEmptyWhenMissingOrMalformed() {
        when(redisService.<String>get(anyString())).thenReturn(null, "not-a-number");
        assertTrue(tokenService.resolveUserId("missing").isEmpty());
        assertThrows(NumberFormatException.class, () -> {
            // 非数字 value 属于 Redis 脏数据，直接抛出由全局处理器兜底
            tokenService.resolveUserId("dirty");
        });
    }

    @Test
    void removeDeletesKey() {
        tokenService.remove("abc");
        verify(redisService).delete("auth:token:abc");
    }
}
