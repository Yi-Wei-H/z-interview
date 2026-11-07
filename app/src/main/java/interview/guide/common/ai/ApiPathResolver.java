package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 统一处理baseUrl，保证请求URL正常
 */
public final class ApiPathResolver {

  private static final int DEFAULT_CONNECT_TIMEOUT = 10000;
  private static final int DEFAULT_READ_TIMEOUT = 300000;

  private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

  private ApiPathResolver() {}

  /* 建立默认AI连接：使用默认参数*/
  public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey) {
    return buildOpenAiClient(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
  }

  /* 建立AI连接 */
  public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey,
      int connectTimeout, int readTimeout) {
    Timeout timeout = Timeout.builder()
        .connect(Duration.ofMillis(connectTimeout))
        .read(Duration.ofMillis(readTimeout))
        .build();
    ClientOptions options = ClientOptions.Companion.builder()
        .apiKey(apiKey)
        .credential(BearerTokenCredential.create(apiKey))
        .baseUrl(resolveVersionedBaseUrl(baseUrl))
        .timeout(timeout)
        .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
        .build();
    return new OpenAIClientImpl(options);
  }

  /* */
  public static String resolveVersionedBaseUrl(String baseUrl) {
    String stripped = stripTrailingSlashes(baseUrl);
    if (baseUrlContainsVersion(stripped)) {
      return stripped;
    }
    return stripped + "/v1";
  }

  /* 判断URL是否以版本号结尾*/
  public static boolean baseUrlContainsVersion(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    String stripped = stripTrailingSlashes(baseUrl.trim());
    return TRAILING_VERSION.matcher(stripped).find();
  }

  /*去除尾部‘/’*/
  public static String stripTrailingSlashes(String value) {
    if (value == null) {
      return "";
    }
    String result = value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
