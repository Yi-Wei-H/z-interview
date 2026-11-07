package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用户管理和缓存大型语言模型提供者的注册表。支持基于该用户 provider 配置动态创建聊天客户端。
 * 缓存键：userId + ":" + providerId [+ ":plain" | ":voice"]。
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    private final LlmProviderProperties properties;
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final ToolCallback interviewSkillsToolCallback;
    private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
        "dashscope", "text-embedding-v3",
        "glm", "embedding-3",
        "zhipu", "embedding-3",
        "baidu", "Embedding-V1",
        "minimax", "embo-01"
    );

    @Autowired
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
    }

    /**
     * 获取指定用户、指定 Provider ID 的 ChatClient。
     * 若客户端不在缓存中，将根据该 Provider 的配置创建。
     *
     * @param userId 归属用户（users.id）
     * @param providerId Provider 内部 ID（userId + ":" + 用户输入 ID）
     * @return ChatClient 实例
     * @throws BusinessException 如果 provider 未知、禁用或未配置 API Key
     */
    public ChatClient getChatClient(Long userId, String providerId) {
        return clientCache.computeIfAbsent(cacheKey(userId, providerId), key -> {
            log.info("[LlmProviderRegistry] 为用户 {} 创建新 ChatClient: provider={}", userId, providerId);
            return createChatClient(userId, providerId);
        });
    }

    /**
     * 获取指定用户设置的默认 ChatClient。
     */
    public ChatClient getDefaultChatClient(Long userId) {
        return getChatClient(userId, resolveDefaultChatProviderId(userId));
    }

    /**
     * 获取指定 Provider 的 ChatClient，providerId 为 null 或空白时回退到该用户默认 Provider。
     */
    public ChatClient getChatClientOrDefault(Long userId, String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            return getChatClient(userId, providerId);
        }
        return getDefaultChatClient(userId);
    }

    /**
     * 获取不带 SkillsTool 的 ChatClient，用于结构化输出场景（出题、简历评分等）。
     * 这些场景要求模型一次性返回可解析 JSON，不应混入工具调用消息。
     */
    public ChatClient getPlainChatClient(Long userId, String providerId) {
        String id = resolveProviderId(userId, providerId);
        return clientCache.computeIfAbsent(cacheKey(userId, id) + ":plain", key -> createPlainChatClient(userId, id));
    }

    /**
     * 获取语音面试专用 ChatClient：SkillsTool + ToolCallAdvisor（流式）。
     * 不加 Memory Advisor（语音面试手动管理对话历史）。
     */
    public ChatClient getVoiceChatClient(Long userId, String providerId) {
        String id = resolveProviderId(userId, providerId);
        return clientCache.computeIfAbsent(cacheKey(userId, id) + ":voice", key -> createVoiceChatClient(userId, id));
    }

    /**
     * 清空缓存，重新加载所有用户的 provider。
     */
    public void reload() {
        int size = clientCache.size() + chatModelCache.size() + embeddingModelCache.size();
        clientCache.clear();
        chatModelCache.clear();
        embeddingModelCache.clear();
        log.info("[LlmProviderRegistry] 缓存已清空（{} 条），下次访问将重新创建客户端", size);
    }

    /**
     * 只失效单个用户的缓存（该用户 provider 变更时调用）。
     */
    public void reloadUser(Long userId) {
        String prefix = userId + ":";
        int size = countOwned(prefix);
        clientCache.keySet().removeIf(k -> k.startsWith(prefix));
        chatModelCache.keySet().removeIf(k -> k.startsWith(prefix));
        embeddingModelCache.keySet().removeIf(k -> k.startsWith(prefix));
        log.info("[LlmProviderRegistry] 用户 {} 的缓存已清空（{} 条）", userId, size);
    }

    public EmbeddingModel getEmbeddingModel(Long userId, String providerId) {
        return embeddingModelCache.computeIfAbsent(cacheKey(userId, providerId), key -> {
            log.info("[LlmProviderRegistry] 为用户 {} 创建新 EmbeddingModel: provider={}", userId, providerId);
            return createEmbeddingModel(userId, providerId);
        });
    }

    public EmbeddingModel getDefaultEmbeddingModel(Long userId) {
        return getEmbeddingModel(userId, resolveDefaultEmbeddingProviderId(userId));
    }

    private static String cacheKey(Long userId, String providerId) {
        return userId + ":" + providerId;
    }

    private int countOwned(String prefix) {
        return (int) (clientCache.keySet().stream().filter(k -> k.startsWith(prefix)).count()
            + chatModelCache.keySet().stream().filter(k -> k.startsWith(prefix)).count()
            + embeddingModelCache.keySet().stream().filter(k -> k.startsWith(prefix)).count());
    }

    private ChatClient createChatClient(Long userId, String providerId) {
        OpenAiChatModel chatModel = getChatModel(userId, providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors(providerId);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
            log.info("[LlmProviderRegistry] 已应用 {} 个 advisor: provider={}", advisors.size(), providerId);
        }

        return builder.build();
    }

    private ChatClient createPlainChatClient(Long userId, String providerId) {
        OpenAiChatModel chatModel = getChatModel(userId, providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(List.of(advisor)));
        log.info("[LlmProviderRegistry] 已创建 plain ChatClient（无工具）: provider={}", providerId);
        return builder.build();
    }

    private ChatClient createVoiceChatClient(Long userId, String providerId) {
        OpenAiChatModel chatModel = getChatModel(userId, providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true));
        }
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        log.info("[LlmProviderRegistry] 已创建语音 ChatClient（SkillsTool + 流式 ToolCall）: provider={}", providerId);
        return builder.build();
    }

    private OpenAiChatModel getChatModel(Long userId, String providerId) {
        return chatModelCache.computeIfAbsent(cacheKey(userId, providerId), key -> {
            log.info("[LlmProviderRegistry] 为用户 {} 创建新 ChatModel: provider={}", userId, providerId);
            return buildChatModel(userId, providerId);
        });
    }

    private OpenAiChatModel buildChatModel(Long userId, String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(userId, providerId);
        log.info("[LlmProviderRegistry] 构建 ChatModel: provider={}, baseUrl={}, model={}",
                 providerId, config.baseUrl(), config.model());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                .temperature(config.temperature() != null ? config.temperature() : 0.2)
                .build();

        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    private EmbeddingModel createEmbeddingModel(Long userId, String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(userId, providerId);
        if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }
        if (looksLikeChatModel(config.embeddingModel())) {
            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(userInputPart(providerId).toLowerCase());
            String suffix = recommendation != null
                ? "，推荐填写 " + recommendation
                : "，请填写该厂商真实的 Embedding 模型名";
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 的 Embedding Model 配成了聊天模型 '"
                    + config.embeddingModel() + "'" + suffix);
        }
        log.info("[LlmProviderRegistry] 构建 EmbeddingModel: provider={}, baseUrl={}, model={}",
            providerId, config.baseUrl(), config.embeddingModel());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(config.embeddingModel())
            .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
            .build();

        return OpenAiEmbeddingModel.builder()
            .openAiClient(openAiClient)
            .metadataMode(MetadataMode.EMBED)
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    private List<Advisor> buildDefaultAdvisors(String providerId) {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();

        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                advisors.add(buildToolCallAdvisor(config.isToolCallConversationHistoryEnabled()));
            } else {
                log.warn("[LlmProviderRegistry] 已跳过 ToolCallAdvisor: ToolCallingManager 不可用, provider={}", providerId);
            }
        }

        if (config.isMessageChatMemoryEnabled()) {
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                    .maxMessages(maxMessages)
                    .build()
            ).build();
            advisors.add(memoryAdvisor);
        }

        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        buildSafeGuardAdvisor().ifPresent(advisors::add);

        return advisors;
    }

    private ToolCallingAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled) {
        return ToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .conversationHistoryEnabled(conversationHistoryEnabled)
            .build();
    }

    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
            .sensitiveWords(config.getSafeguardWords())
            .failureResponse("抱歉，我只能协助面试相关的任务。")
            .order(100)
            .build();
        return Optional.of(advisor);
    }

    private String resolveProviderId(Long userId, String providerId) {
        return (providerId != null && !providerId.isBlank())
            ? providerId : resolveDefaultChatProviderId(userId);
    }

    /* 解析默认聊天提供商ID */
    private String resolveDefaultChatProviderId(Long userId) {
        String defaultId = globalSettingRepository.findById(userId)
            .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
            .filter(id -> id != null && !id.isBlank())
            .orElse(null);
        if (defaultId == null) {
            throwNoProviderConfiguredOr(userId, ErrorCode.NO_DEFAULT_CHAT_MODEL);
        }
        return providerRepository.findByIdAndUserId(defaultId, userId)
            .filter(LlmProviderEntity::isEnabled)
            .orElseThrow(() -> new BusinessException(ErrorCode.NO_DEFAULT_CHAT_MODEL))
            .getId();
    }

    private String resolveDefaultEmbeddingProviderId(Long userId) {
        String defaultId = globalSettingRepository.findById(userId)
            .map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
            .filter(id -> id != null && !id.isBlank())
            .orElse(null);
        if (defaultId == null) {
            throwNoProviderConfiguredOr(userId, ErrorCode.NO_DEFAULT_EMBEDDING_MODEL);
        }
        return providerRepository.findByIdAndUserId(defaultId, userId)
            .filter(e -> e.isEnabled() && e.isSupportsEmbedding())
            .orElseThrow(() -> new BusinessException(ErrorCode.NO_DEFAULT_EMBEDDING_MODEL))
            .getId();
    }

    /** 有 provider 但没设默认 → 12011/12012；一个都没有 → 12010 */
    private void throwNoProviderConfiguredOr(Long userId, ErrorCode hasProviderError) {
        if (providerRepository.existsByUserId(userId)) {
            throw new BusinessException(hasProviderError);
        }
        throw new BusinessException(ErrorCode.NO_PROVIDER_CONFIGURED);
    }

    private ProviderSnapshot loadProviderOrThrow(Long userId, String providerId) {
        LlmProviderEntity entity = providerRepository.findByIdAndUserId(providerId, userId)
            .filter(LlmProviderEntity::isEnabled)
            .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND));
        if (isBlank(entity.getApiKeyCiphertext())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 尚未配置 API Key，请在页面\"模型配置\"中填写");
        }
        return new ProviderSnapshot(
            entity.getId(),
            entity.getBaseUrl(),
            encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
            entity.getModel(),
            entity.getEmbeddingModel(),
            entity.getEmbeddingDimensions(),
            entity.isSupportsEmbedding(),
            entity.getTemperature()
        );
    }

    /** 内部 id 形如 "userId:用户输入ID"，取冒号后的用户输入部分用于厂商推荐提示匹配 */
    private String userInputPart(String internalId) {
        int idx = internalId.indexOf(':');
        return idx >= 0 ? internalId.substring(idx + 1) : internalId;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    private record ProviderSnapshot(
        String id,
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
