package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.ApiPathResolver;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.model.VoiceAiSettingEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.repository.VoiceAiSettingRepository;
import interview.guide.modules.user.context.UserContext;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * LLM/向量 Provider 与语音 ASR/TTS 配置的页面读写入口。
 * 模型定义全部存数据库（llm_provider_config / voice_ai_setting），无配置文件通道。
 * 模型 Provider 按用户隔离（llm_provider_config.user_id）；语音 ASR/TTS 仍为全局配置。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmProviderConfigService {

  private final LlmProviderProperties properties;
  private final LlmProviderRegistry registry;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;
  private final VoiceInterviewProperties voiceProperties;
  private final VoiceAiSettingService voiceAiSettingService;
  private final VoiceAiSettingRepository voiceAiSettingRepository;
  private final QwenAsrService asrService;
  private final QwenTtsService ttsService;

  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

  private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
      "dashscope", "text-embedding-v3",
      "glm", "embedding-3",
      "zhipu", "embedding-3",
      "baidu", "Embedding-V1",
      "minimax", "embo-01"
  );

  // ===== 读操作（读锁）=====

  public List<ProviderDTO> listProviders() {
    Long userId = UserContext.requireUserId();
    rwLock.readLock().lock();
    try {
      LlmGlobalSettingEntity setting = getSettingOrNull(userId);
      return providerRepository.findByUserIdOrderByIdAsc(userId).stream()
          .map(provider -> ProviderDTO.builder()
              .id(provider.getId())
              .baseUrl(provider.getBaseUrl())
              .maskedApiKey(maskApiKey(decryptApiKey(provider)))
              .model(provider.getModel())
              .embeddingModel(provider.getEmbeddingModel())
              .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
              .supportsEmbedding(provider.isSupportsEmbedding())
              .temperature(provider.getTemperature())
              .defaultChatProvider(setting != null && provider.getId().equals(setting.getDefaultChatProviderId()))
              .defaultEmbeddingProvider(setting != null && provider.getId().equals(setting.getDefaultEmbeddingProviderId()))
              .build())
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderDTO getProvider(String id) {
    Long userId = UserContext.requireUserId();
    rwLock.readLock().lock();
    try {
      LlmGlobalSettingEntity setting = getSettingOrNull(userId);
      LlmProviderEntity provider = getProviderEntityOrThrow(userId, id);
      return ProviderDTO.builder()
          .id(id)
          .baseUrl(provider.getBaseUrl())
          .maskedApiKey(maskApiKey(decryptApiKey(provider)))
          .model(provider.getModel())
          .embeddingModel(provider.getEmbeddingModel())
          .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
          .supportsEmbedding(provider.isSupportsEmbedding())
          .temperature(provider.getTemperature())
          .defaultChatProvider(setting != null && id.equals(setting.getDefaultChatProviderId()))
          .defaultEmbeddingProvider(setting != null && id.equals(setting.getDefaultEmbeddingProviderId()))
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  /** 无设置行时返回空值 DTO（不抛异常），保证设置页可正常加载 */
  public DefaultProviderDTO getDefaultProvider() {
    Long userId = UserContext.requireUserId();
    rwLock.readLock().lock();
    try {
      LlmGlobalSettingEntity setting = getSettingOrNull(userId);
      if (setting == null) {
        return new DefaultProviderDTO(null, null);
      }
      return new DefaultProviderDTO(
          setting.getDefaultChatProviderId(),
          setting.getDefaultEmbeddingProviderId());
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public AsrConfigDTO getAsrConfig() {
    UserContext.requireUserId();
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      return AsrConfigDTO.builder()
          .url(asr.getUrl())
          .model(asr.getModel())
          .maskedApiKey(maskApiKey(asr.getApiKey()))
          .language(asr.getLanguage())
          .format(asr.getFormat())
          .sampleRate(asr.getSampleRate())
          .enableTurnDetection(asr.isEnableTurnDetection())
          .turnDetectionType(asr.getTurnDetectionType())
          .turnDetectionThreshold(asr.getTurnDetectionThreshold())
          .turnDetectionSilenceDurationMs(asr.getTurnDetectionSilenceDurationMs())
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public TtsConfigDTO getTtsConfig() {
    UserContext.requireUserId();
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
      return TtsConfigDTO.builder()
          .model(tts.getModel())
          .maskedApiKey(maskApiKey(tts.getApiKey()))
          .voice(tts.getVoice())
          .format(tts.getFormat())
          .sampleRate(tts.getSampleRate())
          .mode(tts.getMode())
          .languageType(tts.getLanguageType())
          .speechRate(tts.getSpeechRate())
          .volume(tts.getVolume())
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testProvider(String id) {
    Long userId = UserContext.requireUserId();
    rwLock.readLock().lock();
    try {
      return doTestProvider(getProviderRuntimeConfigOrThrow(userId, id), id);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testAsrConfig() {
    UserContext.requireUserId();
    rwLock.readLock().lock();
    try {
      // 提取host和port
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      try {
        java.net.URI wsUri = java.net.URI.create(asr.getUrl());
        String host = wsUri.getHost();
        int port = wsUri.getPort() > 0 ? wsUri.getPort() : (wsUri.getScheme().equals("wss") ? 443 : 80);
        java.net.InetSocketAddress address = new java.net.InetSocketAddress(host, port);
        // 建立socket连接
        java.net.Socket socket = new java.net.Socket();
        socket.connect(address, 5000);
        socket.close();
        return ProviderTestResult.builder()
            .success(true)
            .message("ASR WebSocket 连接成功: " + host)
            .model(asr.getModel())
            .build();
      } catch (Exception e) {
        return ProviderTestResult.builder()
            .success(false)
            .message("ASR 连接失败: " + e.getMessage())
            .model(asr.getModel())
            .build();
      }
    } finally {
      rwLock.readLock().unlock();
    }
  }

  // ===== 写操作（写锁）=====

  // TODO：(修改)只有提交事务后才能reload
  @Transactional
  public void createProvider(CreateProviderRequest request) {
    Long userId = UserContext.requireUserId();
    rwLock.writeLock().lock();
    try {
      String userInputId = request.id() == null ? "" : request.id().trim();
      if (userInputId.isEmpty() || userInputId.contains(":")) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Provider ID 不能为空且不能包含冒号");
      }
      String internalId = userId + ":" + userInputId;
      if (providerRepository.existsById(internalId)) {
        throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS);
      }
      String baseUrl = requireNonBlank(request.baseUrl(), "baseUrl");
      String model = requireNonBlank(request.model(), "model");
      String apiKey = requireNonBlank(request.apiKey(), "apiKey");
      String embeddingModel = trimOrNull(request.embeddingModel());
      Integer embeddingDimensions = resolveEmbeddingDimensions(request.embeddingDimensions());
      boolean supportsEmbedding = request.supportsEmbedding() != null
          ? request.supportsEmbedding()
          : embeddingModel != null;
      validateEmbeddingConfig(userInputId, supportsEmbedding, embeddingModel, embeddingDimensions);

      ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
      providerRepository.save(LlmProviderEntity.builder()
          .id(internalId)
          .userId(userId)
          .baseUrl(baseUrl)
          .apiKeyNonce(encrypted.nonce())
          .apiKeyCiphertext(encrypted.ciphertext())
          .model(model)
          .embeddingModel(embeddingModel)
          .embeddingDimensions(embeddingDimensions)
          .supportsEmbedding(supportsEmbedding)
          .temperature(request.temperature())
          .enabled(true)
          .builtin(false)
          .build());

      TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  registry.reloadUser(userId);  // 事务提交后才失效该用户缓存
                }
              }
      );

      log.info("已创建 Provider: user={}, id={}, baseUrl={}, model={}", userId, internalId, baseUrl, model);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateProvider(String id, UpdateProviderRequest request) {
    Long userId = UserContext.requireUserId();
    rwLock.writeLock().lock();
    try {
      LlmProviderEntity provider = getProviderEntityOrThrow(userId, id);

      String trimmedBaseUrl = trimOrNull(request.baseUrl());
      if (request.baseUrl() != null && trimmedBaseUrl == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
      }
      String trimmedModel = trimOrNull(request.model());
      if (request.model() != null && trimmedModel == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
      }
      String trimmedApiKey = trimOrNull(request.apiKey());
      if (request.apiKey() != null && trimmedApiKey == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
      }

      if (trimmedBaseUrl != null) provider.setBaseUrl(trimmedBaseUrl);
      if (trimmedModel != null) provider.setModel(trimmedModel);
      if (request.embeddingModel() != null) {
        provider.setEmbeddingModel(trimOrNull(request.embeddingModel()));
      }
      if (request.embeddingDimensions() != null) {
        provider.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
      }
      if (request.supportsEmbedding() != null) {
        provider.setSupportsEmbedding(request.supportsEmbedding());
      }
      validateEmbeddingConfig(
          id,
          provider.isSupportsEmbedding(),
          provider.getEmbeddingModel(),
          resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
      if (request.temperature() != null) {
        provider.setTemperature(request.temperature());
      }
      if (trimmedApiKey != null) {
        ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(trimmedApiKey);
        provider.setApiKeyNonce(encrypted.nonce());
        provider.setApiKeyCiphertext(encrypted.ciphertext());
      }

      providerRepository.save(provider);
      registry.reloadUser(userId);
      log.info("已更新 Provider: user={}, id={}", userId, id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void deleteProvider(String id) {
    Long userId = UserContext.requireUserId();
    rwLock.writeLock().lock();
    try {
      LlmGlobalSettingEntity setting = getSettingOrNull(userId);
      if (setting != null
          && (id.equals(setting.getDefaultChatProviderId()) || id.equals(setting.getDefaultEmbeddingProviderId()))) {
        throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
            "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
      }
      getProviderEntityOrThrow(userId, id);

      providerRepository.deleteById(id);
      registry.reloadUser(userId);
      log.info("已删除 Provider: user={}, id={}", userId, id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateDefaultProvider(DefaultProviderDTO request) {
    Long userId = UserContext.requireUserId();
    rwLock.writeLock().lock();
    try {
      String providerId = trimOrNull(request.defaultProvider());
      if (providerId == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
      }
      getProviderEntityOrThrow(userId, providerId);
      LlmGlobalSettingEntity setting = getSettingOrNew(userId);
      setting.setDefaultChatProviderId(providerId);
      globalSettingRepository.save(setting);
      reloadUserAfterCommit(userId);
      log.info("已更新默认 Provider: user={}, id={}", userId, providerId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateDefaultEmbeddingProvider(DefaultProviderDTO request) {
    Long userId = UserContext.requireUserId();
    rwLock.writeLock().lock();
    try {
      String providerId = trimOrNull(request.defaultEmbeddingProvider());
      if (providerId == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultEmbeddingProvider 不能为空");
      }
      LlmProviderEntity provider = getProviderEntityOrThrow(userId, providerId);
      String embeddingModel = trimOrNull(provider.getEmbeddingModel());
      if (!provider.isSupportsEmbedding() || embeddingModel == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "Provider '" + providerId + "' 不支持 Embedding，不能设为默认向量服务");
      }
      validateEmbeddingConfig(
          userInputPart(providerId),
          true,
          embeddingModel,
          resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
      LlmGlobalSettingEntity setting = getSettingOrNew(userId);
      setting.setDefaultEmbeddingProviderId(providerId);
      globalSettingRepository.save(setting);
      reloadUserAfterCommit(userId);
      log.info("已更新默认向量 Provider: user={}, id={}", userId, providerId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  /**
   * 页面修改 ASR 配置：更新 voice_ai_setting 行（API Key 加密，未传保留原密文）→
   * 同步内存 bean → reload ASR 服务（Key 变更时 TTS 一并 reload，两者共用同一把 Key）。
   */
  @Transactional
  public void updateAsrConfig(AsrConfigRequest request) {
    UserContext.requireUserId();
    rwLock.writeLock().lock();
    try {
      String apiKey = optionalApiKey(request.apiKey());
      VoiceAiSettingEntity setting = voiceAiSettingService.getSettingOrThrow();
      if (request.url() != null) setting.setAsrUrl(request.url());
      if (request.model() != null) setting.setAsrModel(request.model());
      if (request.language() != null) setting.setAsrLanguage(request.language());
      if (request.format() != null) setting.setAsrFormat(request.format());
      if (request.sampleRate() != null) setting.setAsrSampleRate(request.sampleRate());
      if (request.enableTurnDetection() != null) setting.setAsrEnableTurnDetection(request.enableTurnDetection());
      if (request.turnDetectionType() != null) setting.setAsrTurnDetectionType(request.turnDetectionType());
      if (request.turnDetectionThreshold() != null) {
        setting.setAsrTurnDetectionThreshold(request.turnDetectionThreshold().doubleValue());
      }
      if (request.turnDetectionSilenceDurationMs() != null) {
        setting.setAsrTurnDetectionSilenceMs(request.turnDetectionSilenceDurationMs());
      }
      if (apiKey != null) {
        voiceAiSettingService.applyApiKey(setting, apiKey);
      }

      voiceAiSettingRepository.save(setting);
      voiceAiSettingService.replayToMemory(setting);
      asrService.reload(voiceProperties);
      if (apiKey != null) {
        ttsService.reload(voiceProperties);
      }
      log.info("ASR 配置已更新（已落库）");
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  /** 页面修改 TTS 配置：同 {@link #updateAsrConfig}，落库 + 内存同步 + reload。 */
  @Transactional
  public void updateTtsConfig(TtsConfigRequest request) {
    UserContext.requireUserId();
    rwLock.writeLock().lock();
    try {
      String apiKey = optionalApiKey(request.apiKey());
      VoiceAiSettingEntity setting = voiceAiSettingService.getSettingOrThrow();
      if (request.model() != null) setting.setTtsModel(request.model());
      if (request.voice() != null) setting.setTtsVoice(request.voice());
      if (request.format() != null) setting.setTtsFormat(request.format());
      if (request.sampleRate() != null) setting.setTtsSampleRate(request.sampleRate());
      if (request.mode() != null) setting.setTtsMode(request.mode());
      if (request.languageType() != null) setting.setTtsLanguageType(request.languageType());
      if (request.speechRate() != null) setting.setTtsSpeechRate(request.speechRate().doubleValue());
      if (request.volume() != null) setting.setTtsVolume(request.volume());
      if (apiKey != null) {
        voiceAiSettingService.applyApiKey(setting, apiKey);
      }

      voiceAiSettingRepository.save(setting);
      voiceAiSettingService.replayToMemory(setting);
      ttsService.reload(voiceProperties);
      if (apiKey != null) {
        asrService.reload(voiceProperties);
      }
      log.info("TTS 配置已更新（已落库）");
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  public void reloadProviders() {
    registry.reload();
    log.info("已手动触发 Provider 重载");
  }

  // ===== 内部辅助方法 =====

  LlmProviderEntity getProviderEntityOrThrow(Long userId, String id) {
    return providerRepository.findByIdAndUserId(id, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
            "Provider '" + id + "' 不存在"));
  }

  /** 无设置行返回 null（调用方决定默认值语义），不再抛异常 */
  private LlmGlobalSettingEntity getSettingOrNull(Long userId) {
    return globalSettingRepository.findById(userId).orElse(null);
  }

  /** upsert：无设置行时懒创建（id 即用户 id，未设置字段以空串填充满足 NOT NULL） */
  private LlmGlobalSettingEntity getSettingOrNew(Long userId) {
    return globalSettingRepository.findById(userId)
        .orElseGet(() -> LlmGlobalSettingEntity.builder()
            .id(userId)
            .defaultChatProviderId("")
            .defaultEmbeddingProviderId("")
            .build());
  }

  /** 事务提交后只失效该用户的缓存 */
  private void reloadUserAfterCommit(Long userId) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            registry.reloadUser(userId);
          }
        });
  }

  private ProviderRuntimeConfig getProviderRuntimeConfigOrThrow(Long userId, String id) {
    LlmProviderEntity provider = getProviderEntityOrThrow(userId, id);
    return new ProviderRuntimeConfig(
        provider.getBaseUrl(),
        decryptApiKey(provider),
        provider.getModel(),
        provider.getEmbeddingModel(),
        resolveEmbeddingDimensions(provider.getEmbeddingDimensions()),
        provider.isSupportsEmbedding(),
        provider.getTemperature()
    );
  }

  // 空/未配置 Key 解密前短路：种子行密文为空串，直接进入解密会因空 nonce 失败
  private String decryptApiKey(LlmProviderEntity provider) {
    if (provider.getApiKeyCiphertext() == null || provider.getApiKeyCiphertext().isBlank()) {
      return "";
    }
    return encryptionService.decrypt(provider.getApiKeyNonce(), provider.getApiKeyCiphertext());
  }

  private String optionalApiKey(String apiKey) {
    if (apiKey == null) {
      return null;
    }
    String trimmed = apiKey.trim();
    if (trimmed.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
    }
    return trimmed;
  }

  // 对apiKey进行遮掩
  String maskApiKey(String apiKey) {
    if (apiKey == null || apiKey.length() <= 6) {
      return "***";
    }
    return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 3);
  }

  /** 内部 id 形如 "userId:用户输入ID"，取冒号后的用户输入部分用于厂商推荐提示匹配 */
  private String userInputPart(String internalId) {
    int idx = internalId.indexOf(':');
    return idx >= 0 ? internalId.substring(idx + 1) : internalId;
  }

  // 将文本缩写至200字
  private String abbreviate(String text) {
    if (text == null || text.isBlank()) {
      return "[no body]";
    }
    String normalized = text.replaceAll("\\s+", " ").trim();
    if (normalized.length() <= 200) {
      return normalized;
    }
    return normalized.substring(0, 200) + "...";
  }

  private List<String> buildConnectivityTestUrls(String baseUrl) {
    String normalizedBaseUrl = ApiPathResolver.stripTrailingSlashes(baseUrl);
    LinkedHashSet<String> candidateUrls = new LinkedHashSet<>();

    candidateUrls.add(normalizedBaseUrl + "/chat/completions");
    if (!ApiPathResolver.baseUrlContainsVersion(normalizedBaseUrl)) {
      candidateUrls.add(normalizedBaseUrl + "/v1/chat/completions");
    }

    return List.copyOf(candidateUrls);
  }

  private Map<String, Object> buildConnectivityTestRequestBody(String model) {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("model", model);
    requestBody.put("messages", List.of(Map.of(
        "role", "user",
        "content", "Reply with OK only."
    )));
    requestBody.put("max_tokens", 1);
    return requestBody;
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private String requireNonBlank(String value, String fieldName) {
    String normalized = trimOrNull(value);
    if (normalized == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
    }
    return normalized;
  }

  private void validateEmbeddingConfig(
      String providerId,
      boolean supportsEmbedding,
      String embeddingModel,
      Integer embeddingDimensions) {
    String normalizedModel = trimOrNull(embeddingModel);
    if (!supportsEmbedding) {
      return;
    }
    if (normalizedModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "支持 Embedding 的 Provider 必须填写 embeddingModel");
    }
    if (looksLikeChatModel(normalizedModel)) {
      String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
      String suffix = recommendation != null
          ? "，推荐填写 " + recommendation
          : "，请填写该厂商真实的 Embedding 模型名";
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "Embedding Model 不能填写聊天模型 '" + normalizedModel + "'" + suffix);
    }
    if (embeddingDimensions == null || embeddingDimensions <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "向量维度必须为正整数");
    }
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private boolean looksLikeChatModel(String model) {
    String lower = model.toLowerCase();
    return lower.startsWith("glm-")
        || lower.startsWith("deepseek")
        || lower.startsWith("kimi")
        || lower.startsWith("moonshot")
        || lower.startsWith("qwen")
        || lower.startsWith("ernie");
  }

  // ===== Provider 连通性测试逻辑（在读锁下调用）=====

  private ProviderTestResult doTestProvider(ProviderRuntimeConfig config, String id) {
    try {
      HttpClientSettings settings = HttpClientSettings.defaults()
          .withConnectTimeout(Duration.ofSeconds(5))
          .withReadTimeout(Duration.ofSeconds(10))
          .withInetAddressFilter(
              InetAddressFilter.externalAddresses()
                  .or(InetAddressFilter.adapt(InetAddress::isLoopbackAddress))
                  .or("198.18.0.0/15"));

      RestClient restClient = RestClient.builder()
          .defaultHeader("Authorization", "Bearer " + config.apiKey())
          .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
          .build();

      Map<String, Object> requestBody = buildConnectivityTestRequestBody(config.model());

      List<String> candidateUrls = buildConnectivityTestUrls(config.baseUrl());
      String lastFailureMessage = "Unknown error";

      for (String targetUrl : candidateUrls) {
        try {
          restClient.post()
              .uri(URI.create(targetUrl))
              .body(requestBody)
              .retrieve()
              .toEntity(String.class);
          log.info("Provider 连通性测试成功: providerId={}, baseUrl={}, targetUrl={}, model={}",
              id, config.baseUrl(), targetUrl, config.model());
          return ProviderTestResult.builder()
              .success(true)
              .message("连接成功")
              .model(config.model())
              .build();
        } catch (RestClientResponseException e) {
          String responseBody = abbreviate(e.getResponseBodyAsString());
          lastFailureMessage = String.format(
              "HTTP %s on %s, body=%s",
              e.getStatusCode().value(),
              targetUrl,
              responseBody
          );
          log.warn(
              "Provider 连通性测试失败（有响应）: providerId={}, baseUrl={}, targetUrl={}, model={}, status={}, body={}",
              id,
              config.baseUrl(),
              targetUrl,
              config.model(),
              e.getStatusCode().value(),
              responseBody,
              e
          );
        } catch (Exception e) {
          lastFailureMessage = String.format(
              "%s on %s: %s",
              e.getClass().getSimpleName(),
              targetUrl,
              e.getMessage()
          );
          log.warn(
              "Provider 连通性测试失败: providerId={}, baseUrl={}, targetUrl={}, model={}, error={}",
              id,
              config.baseUrl(),
              targetUrl,
              config.model(),
              e.getMessage(),
              e
          );
        }
      }
      return ProviderTestResult.builder()
          .success(false)
          .message("连接失败: " + lastFailureMessage)
          .model(config.model())
          .build();
    } catch (Exception e) {
      log.warn("Provider 连通性测试初始化失败: providerId={}, baseUrl={}, model={}, error={}",
          id, config.baseUrl(), config.model(), e.getMessage(), e);
      return ProviderTestResult.builder()
          .success(false)
          .message("连接失败: " + e.getMessage())
          .model(config.model())
          .build();
    }
  }

  private record ProviderRuntimeConfig(
      String baseUrl,
      String apiKey,
      String model,
      String embeddingModel,
      Integer embeddingDimensions,
      boolean supportsEmbedding,
      Double temperature
  ) {
  }
}
