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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderRegistry 用户隔离与未配置文案")
class LlmProviderRegistryUserScopeTest {

    @Mock private LlmProviderRepository providerRepository;
    @Mock private LlmGlobalSettingRepository globalSettingRepository;

    private LlmProviderRegistry registry;

    @BeforeEach
    void setUp() {
        LlmProviderProperties properties = new LlmProviderProperties();
        // 仅走错误路径，不会触发 decrypt，无需 init()
        registry = new LlmProviderRegistry(properties, providerRepository, globalSettingRepository,
                new ApiKeyEncryptionService(properties), null, null, null);
    }

    private LlmProviderEntity provider(Long userId, String name, boolean enabled) {
        return LlmProviderEntity.builder().id(userId + ":" + name)
                .baseUrl("https://api.example.com").apiKeyCiphertext("enc").apiKeyNonce("nonce")
                .model("m").supportsEmbedding(false).enabled(enabled).builtin(false).userId(userId).build();
    }

    @Test
    @DisplayName("无任何 provider 时 getDefaultChatClient 抛 12010")
    void noProviderAtAll_ThrowsNoProviderConfigured() {
        when(globalSettingRepository.findById(1L)).thenReturn(Optional.empty());
        when(providerRepository.existsByUserId(1L)).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, () -> registry.getDefaultChatClient(1L));
        assertEquals(ErrorCode.NO_PROVIDER_CONFIGURED.getCode(), ex.getCode());
        assertEquals("您还未配置默认模型，请先配置", ex.getMessage());
    }

    @Test
    @DisplayName("有 provider 但未设默认时 getDefaultChatClient 抛 12011")
    void hasProviderButNoDefault_ThrowsNoDefaultChat() {
        when(globalSettingRepository.findById(1L)).thenReturn(Optional.empty());
        when(providerRepository.existsByUserId(1L)).thenReturn(true);
        BusinessException ex = assertThrows(BusinessException.class, () -> registry.getDefaultChatClient(1L));
        assertEquals(ErrorCode.NO_DEFAULT_CHAT_MODEL.getCode(), ex.getCode());
        assertEquals("请先前往设置配置默认聊天模型", ex.getMessage());
    }

    @Test
    @DisplayName("有 provider 但未设默认时 getDefaultEmbeddingModel 抛 12012")
    void embeddingVariantThrowsNoDefaultEmbedding() {
        when(globalSettingRepository.findById(1L)).thenReturn(Optional.empty());
        when(providerRepository.existsByUserId(1L)).thenReturn(true);
        BusinessException ex = assertThrows(BusinessException.class, () -> registry.getDefaultEmbeddingModel(1L));
        assertEquals(ErrorCode.NO_DEFAULT_EMBEDDING_MODEL.getCode(), ex.getCode());
        assertEquals("请先前往设置配置默认向量模型", ex.getMessage());
    }

    @Test
    @DisplayName("默认指向不存在/禁用 provider 时抛 12011")
    void defaultPointsToMissingProvider_ThrowsNoDefaultChat() {
        LlmGlobalSettingEntity setting = LlmGlobalSettingEntity.builder()
                .id(1L).defaultChatProviderId("1:gone").defaultEmbeddingProviderId("").build();
        when(globalSettingRepository.findById(1L)).thenReturn(Optional.of(setting));
        when(providerRepository.findByIdAndUserId("1:gone", 1L)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class, () -> registry.getDefaultChatClient(1L));
        assertEquals(ErrorCode.NO_DEFAULT_CHAT_MODEL.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("跨用户访问 provider 抛 12013")
    void crossUserProviderAccessThrowsProviderNotFound() {
        when(providerRepository.findByIdAndUserId("1:deepseek", 2L)).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class, () -> registry.getChatClient(2L, "1:deepseek"));
        assertEquals(ErrorCode.PROVIDER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("禁用 provider 等同不存在，抛 12013")
    void disabledProviderThrowsProviderNotFound() {
        when(providerRepository.findByIdAndUserId("1:off", 1L))
                .thenReturn(Optional.of(provider(1L, "off", false)));
        BusinessException ex = assertThrows(BusinessException.class, () -> registry.getChatClient(1L, "1:off"));
        assertEquals(ErrorCode.PROVIDER_NOT_FOUND.getCode(), ex.getCode());
    }
}
