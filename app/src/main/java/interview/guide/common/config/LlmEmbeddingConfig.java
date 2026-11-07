package interview.guide.common.config;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.user.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class LlmEmbeddingConfig {

  @Bean
  public EmbeddingModel embeddingModel(LlmProviderRegistry registry) {
    log.info("EmbeddingModel bean 已初始化为 registry 委托");
    return new EmbeddingModel() {
      @Override
      public EmbeddingResponse call(EmbeddingRequest request) {
        return registry.getDefaultEmbeddingModel(UserContext.requireUserId()).call(request);
      }

      @Override
      public float[] embed(Document document) {
        return registry.getDefaultEmbeddingModel(UserContext.requireUserId()).embed(document);
      }
    };
  }
}
