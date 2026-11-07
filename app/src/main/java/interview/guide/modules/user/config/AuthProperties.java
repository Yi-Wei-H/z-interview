package interview.guide.modules.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 登录 token 有效期，默认 7 天 */
    private Duration tokenTtl = Duration.ofDays(7);
}
