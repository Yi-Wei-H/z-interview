package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LLM Provider Registry Test")
class LlmProviderRegistryTest {

    private static final Long USER_ID = 1L;

    @Mock
    private LlmProviderProperties properties;

    @Mock
    private LlmProviderRepository providerRepository;

    @Mock
    private LlmGlobalSettingRepository globalSettingRepository;

    @Mock
    private ApiKeyEncryptionService encryptionService;

    @Mock
    private org.springframework.ai.model.tool.ToolCallingManager toolCallingManager;

    @Mock
    private io.micrometer.observation.ObservationRegistry observationRegistry;

    @InjectMocks
    private LlmProviderRegistry registry;

    private static final String DECRYPTED_KEY = "test-key";

    private LlmProviderEntity entity(String id, String baseUrl, String model) {
        return LlmProviderEntity.builder()
            .id(id)
            .userId(USER_ID)
            .baseUrl(baseUrl)
            .apiKeyCiphertext("cipher")
            .apiKeyNonce("nonce")
            .model(model)
            .supportsEmbedding(false)
            .enabled(true)
            .builtin(false)
            .build();
    }

    private void stubProvider(LlmProviderEntity entity) {
        lenient().when(providerRepository.findByIdAndUserId(entity.getId(), USER_ID)).thenReturn(Optional.of(entity));
        lenient().when(encryptionService.decrypt(any(), any())).thenReturn(DECRYPTED_KEY);
    }

    private void stubDefaultGlobalSetting(String chatProviderId, String embeddingProviderId) {
        LlmGlobalSettingEntity setting = LlmGlobalSettingEntity.builder()
            .id(USER_ID)
            .defaultChatProviderId(chatProviderId)
            .defaultEmbeddingProviderId(embeddingProviderId)
            .build();
        lenient().when(globalSettingRepository.findById(USER_ID))
            .thenReturn(Optional.of(setting));
    }

    @Test
    @DisplayName("Successfully get ChatClient for a valid provider")
    void testGetChatClient_Success() {
        stubProvider(entity("1:test-provider", "http://localhost:1234/v1", "test-model"));

        ChatClient client = registry.getChatClient(USER_ID, "1:test-provider");

        assertNotNull(client);
    }

    @Test
    @DisplayName("Verify that ChatClients are cached")
    void testGetChatClient_Caching() {
        stubProvider(entity("1:test-provider", "http://localhost:1234/v1", "test-model"));

        ChatClient client1 = registry.getChatClient(USER_ID, "1:test-provider");
        ChatClient client2 = registry.getChatClient(USER_ID, "1:test-provider");

        assertSame(client1, client2, "Clients should be cached and returned as the same instance");
        verify(providerRepository, times(1)).findByIdAndUserId("1:test-provider", USER_ID);
    }

    @Test
    @DisplayName("不同 ChatClient 配方共享同一个 provider 级 ChatModel")
    void chatClientVariantsShareProviderChatModel() {
        stubProvider(entity("1:test-provider", "http://localhost:1234/v1", "test-model"));

        ChatClient defaultClient = registry.getChatClient(USER_ID, "1:test-provider");
        ChatClient plainClient = registry.getPlainChatClient(USER_ID, "1:test-provider");
        ChatClient voiceClient = registry.getVoiceChatClient(USER_ID, "1:test-provider");

        assertNotNull(defaultClient);
        assertNotNull(plainClient);
        assertNotNull(voiceClient);
        assertNotSame(defaultClient, plainClient, "Default and plain clients should keep advisor recipes isolated");
        assertNotSame(defaultClient, voiceClient, "Default and voice clients should keep advisor recipes isolated");
        // ChatModel 按 provider 缓存共享，因此只应查询一次 DB
        verify(providerRepository, times(1)).findByIdAndUserId("1:test-provider", USER_ID);
    }

    @Test
    @DisplayName("Throw exception for unknown provider")
    void testGetChatClient_UnknownProvider() {
        when(providerRepository.findByIdAndUserId("unknown", USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> registry.getChatClient(USER_ID, "unknown"));
        assertEquals(ErrorCode.PROVIDER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("尚未配置 API Key 的 provider 调用时报业务错误")
    void testGetChatClient_BlankApiKey() {
        LlmProviderEntity noKey = entity("1:seed-provider", "https://api.deepseek.com", "deepseek-v4-flash");
        noKey.setApiKeyCiphertext("");
        when(providerRepository.findByIdAndUserId("1:seed-provider", USER_ID)).thenReturn(Optional.of(noKey));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> registry.getChatClient(USER_ID, "1:seed-provider"));
        assertEquals("Provider '1:seed-provider' 尚未配置 API Key，请在页面\"模型配置\"中填写",
            ex.getMessage());
        // 未配置 Key 不应触发解密
        verify(encryptionService, never()).decrypt(any(), any());
    }

    @Test
    @DisplayName("禁用的 provider 不可获取 ChatClient")
    void testGetChatClient_DisabledProvider() {
        LlmProviderEntity disabled = entity("1:disabled-provider", "http://localhost:1234/v1", "test-model");
        disabled.setEnabled(false);
        when(providerRepository.findByIdAndUserId("1:disabled-provider", USER_ID)).thenReturn(Optional.of(disabled));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> registry.getChatClient(USER_ID, "1:disabled-provider"));
        assertEquals(ErrorCode.PROVIDER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("Successfully get default ChatClient")
    void testGetDefaultChatClient() {
        stubDefaultGlobalSetting("1:dashscope", "1:dashscope");
        stubProvider(entity("1:dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.5-flash"));

        ChatClient client = registry.getDefaultChatClient(USER_ID);

        assertNotNull(client);
    }

    @Test
    @DisplayName("reload clears cache and allows re-creation")
    void testReload() {
        stubProvider(entity("1:test-provider", "http://localhost:1234/v1", "test-model"));

        ChatClient client1 = registry.getChatClient(USER_ID, "1:test-provider");
        registry.reload();
        ChatClient client2 = registry.getChatClient(USER_ID, "1:test-provider");

        assertNotNull(client1);
        assertNotNull(client2);
        assertNotSame(client1, client2, "After reload, new client should be created");
    }

    @Test
    @DisplayName("reloadUser 只清空该用户缓存，不影响其他用户")
    void testReloadUserOnlyAffectsTargetUser() {
        Long otherUserId = 2L;
        stubProvider(entity("1:test-provider", "http://localhost:1234/v1", "test-model"));
        lenient().when(providerRepository.findByIdAndUserId("1:test-provider", otherUserId))
            .thenReturn(Optional.of(entity("1:test-provider", "http://localhost:1234/v1", "test-model")));

        ChatClient userClient = registry.getChatClient(USER_ID, "1:test-provider");
        ChatClient otherClient = registry.getChatClient(otherUserId, "1:test-provider");

        registry.reloadUser(USER_ID);

        // 其他用户命中缓存（同一实例），目标用户重建（新实例）
        assertSame(otherClient, registry.getChatClient(otherUserId, "1:test-provider"));
        assertNotSame(userClient, registry.getChatClient(USER_ID, "1:test-provider"));
    }

    @Nested
    @DisplayName("getChatClientOrDefault 回退逻辑")
    class GetChatClientOrDefault {

        @BeforeEach
        void setUpProviders() {
            stubDefaultGlobalSetting("1:dashscope", "1:dashscope");
            stubProvider(entity("1:dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.5-flash"));
            stubProvider(entity("1:kimi", "https://api.moonshot.cn/v1", "kimi-latest"));
        }

        @Test
        @DisplayName("非 null providerId 委托给 getChatClient")
        void explicitProvider() {
            ChatClient client = registry.getChatClientOrDefault(USER_ID, "1:kimi");

            assertNotNull(client);
            // 应缓存到该 provider 键下，而非默认键
            assertSame(client, registry.getChatClient(USER_ID, "1:kimi"));
        }

        @Test
        @DisplayName("null providerId 回退到默认 provider")
        void nullProviderFallsBackToDefault() {
            ChatClient client = registry.getChatClientOrDefault(USER_ID, null);

            assertNotNull(client);
            assertSame(client, registry.getChatClient(USER_ID, "1:dashscope"));
        }

        @Test
        @DisplayName("空白 providerId 回退到默认 provider")
        void blankProviderFallsBackToDefault() {
            ChatClient client = registry.getChatClientOrDefault(USER_ID, "   ");

            assertNotNull(client);
            assertSame(client, registry.getChatClient(USER_ID, "1:dashscope"));
        }
    }
}
