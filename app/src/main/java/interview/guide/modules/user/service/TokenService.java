package interview.guide.modules.user.service;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.user.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TokenService {

    private static final String KEY_PREFIX = "auth:token:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RedisService redisService;
    private final AuthProperties authProperties;

    /** 生成 32 字节随机 token 并存入 Redis，返回 64 位 hex */
    public String create(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        redisService.set(KEY_PREFIX + token, String.valueOf(userId), tokenTtl());
        return token;
    }

    public Optional<Long> resolveUserId(String token) {
        String value = redisService.get(KEY_PREFIX + token);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(Long.valueOf(value));
    }

    /** 退出登录：立即失效 */
    public void remove(String token) {
        redisService.delete(KEY_PREFIX + token);
    }

    private Duration tokenTtl() {
        Duration ttl = authProperties.getTokenTtl();
        return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofDays(7) : ttl;
    }
}
