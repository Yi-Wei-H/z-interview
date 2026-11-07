package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderConfigService 测试")
class LlmProviderConfigServiceTest {

    private static final Long USER_ID = 1L;

    @Mock private LlmProviderProperties properties;
    @Mock private LlmProviderRegistry registry;
    @Mock private LlmProviderRepository providerRepository;
    @Mock private LlmGlobalSettingRepository globalSettingRepository;
    @Mock private ApiKeyEncryptionService encryptionService;
    @Mock private VoiceInterviewProperties voiceProperties;
    @Mock private VoiceAiSettingService voiceAiSettingService;
    @Mock private VoiceAiSettingRepository voiceAiSettingRepository;
    @Mock private QwenAsrService asrService;
    @Mock private QwenTtsService ttsService;

    @InjectMocks
    private LlmProviderConfigService service;

    @BeforeEach
    void setUpUserContext() {
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void clearUserContext() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        UserContext.clear();
    }

    private LlmProviderEntity providerEntity(String id, String model, String embeddingModel) {
        LlmProviderEntity entity = LlmProviderEntity.builder()
            .id(id)
            .userId(USER_ID)
            .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
            .apiKeyCiphertext("cipher")
            .apiKeyNonce("nonce")
            .model(model)
            .embeddingModel(embeddingModel)
            .supportsEmbedding(embeddingModel != null)
            .enabled(true)
            .builtin(false)
            .build();
        lenient().when(providerRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(entity));
        return entity;
    }

    private void stubGlobalSetting(String chatProviderId, String embeddingProviderId) {
        LlmGlobalSettingEntity setting = LlmGlobalSettingEntity.builder()
            .id(USER_ID)
            .defaultChatProviderId(chatProviderId)
            .defaultEmbeddingProviderId(embeddingProviderId)
            .build();
        lenient().when(globalSettingRepository.findById(USER_ID))
            .thenReturn(Optional.of(setting));
    }

    /** 手动触发事务 afterCommit（单元测试无真实事务），用于验证 reloadUser 时机 */
    private void fireAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    @Nested
    @DisplayName("基础行为")
    class BasicBehavior {

        @Test
        @DisplayName("maskApiKey 返回脱敏值")
        void maskApiKeyReturnsMaskedValue() {
            assertEquals("sk-***xyz", service.maskApiKey("sk-abcdefxyz"));
            assertEquals("***", service.maskApiKey("ab"));
            assertEquals("abc***fgh", service.maskApiKey("abcdefgh"));
        }

        @Test
        @DisplayName("未配置 Key 的 provider 脱敏为 ***（解密前短路）")
        void blankCiphertextMasksWithoutDecrypt() {
            LlmProviderEntity seed = providerEntity("1:dashscope", null, "text-embedding-v3");
            seed.setApiKeyCiphertext("");
            stubGlobalSetting("1:deepseek", "1:dashscope");
            when(providerRepository.findByUserIdOrderByIdAsc(USER_ID)).thenReturn(List.of(seed));

            List<interview.guide.modules.llmprovider.dto.ProviderDTO> providers = service.listProviders();

            assertEquals(1, providers.size());
            assertEquals("***", providers.get(0).maskedApiKey());
            verify(encryptionService, never()).decrypt(any(), any());
        }

        @Test
        @DisplayName("getProvider 对未知 provider 抛出异常")
        void getProviderThrowsWhenProviderMissing() {
            stubGlobalSetting("1:deepseek", "1:dashscope");
            when(providerRepository.findByIdAndUserId("unknown", USER_ID)).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> service.getProvider("unknown"));
        }

        @Test
        @DisplayName("getProvider 只能访问自己的 provider")
        void getProviderIsScopedToCurrentUser() {
            when(providerRepository.findByIdAndUserId("2:other", USER_ID)).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> service.getProvider("2:other"));
        }

        @Test
        @DisplayName("GLM base-url 测试连接不应重复拼接 /v1")
        void buildConnectivityUrlsAvoidsDoubleVersionForGlm() throws Exception {
            List<String> urls = invokeConnectivityUrls("https://open.bigmodel.cn/api/coding/paas/v4");

            assertEquals(List.of("https://open.bigmodel.cn/api/coding/paas/v4/chat/completions"), urls);
        }

        @Test
        @DisplayName("测试连接请求体不再强制携带 temperature")
        void connectivityRequestBodyOmitsTemperature() throws Exception {
            Map<String, Object> body = invokeConnectivityRequestBody("kimi-latest");

            assertEquals("kimi-latest", body.get("model"));
            assertEquals(1, body.get("max_tokens"));
            assertTrue(body.containsKey("messages"));
            assertFalse(body.containsKey("temperature"));
        }
    }

    @Nested
    @DisplayName("Provider 管理")
    class ProviderManagement {

        @Test
        @DisplayName("createProvider 对重复 id 抛出异常（内部 id = userId:输入ID）")
        void createProviderThrowsForDuplicateId() {
            when(providerRepository.existsById(USER_ID + ":existing")).thenReturn(true);

            CreateProviderRequest request = new CreateProviderRequest(
                "existing",
                "http://localhost:1234",
                "key",
                "model",
                null,
                null
            );

            assertThrows(BusinessException.class, () -> service.createProvider(request));
        }

        @Test
        @DisplayName("createProvider 拒绝包含冒号的 provider ID")
        void createProviderRejectsColonInId() {
            CreateProviderRequest request = new CreateProviderRequest(
                "a:b",
                "http://localhost:1234",
                "key",
                "model",
                null,
                null
            );

            BusinessException ex = assertThrows(BusinessException.class, () -> service.createProvider(request));
            assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("deleteProvider 删除默认 provider 时抛出异常")
        void deleteProviderThrowsForDefaultProvider() {
            stubGlobalSetting("1:dashscope", "1:dashscope");

            assertThrows(BusinessException.class, () -> service.deleteProvider("1:dashscope"));
        }

        @Test
        @DisplayName("updateProvider 清空 embedding model 需同时关闭 supportsEmbedding")
        void updateProviderAllowsClearingEmbeddingModelWhenSupportsDisabled() {
            LlmProviderEntity entity = providerEntity("1:dashscope", "qwen-plus", "text-embedding-v3");

            service.updateProvider("1:dashscope",
                new UpdateProviderRequest(null, null, null, "", null, false, null));

            assertNull(entity.getEmbeddingModel());
            assertFalse(entity.isSupportsEmbedding());
            verify(providerRepository).save(entity);
            verify(registry).reloadUser(USER_ID);
        }

        @Test
        @DisplayName("updateProvider 在 supportsEmbedding 开启时拒绝清空 embedding model")
        void updateProviderRejectsClearingEmbeddingModelWhileSupportsEnabled() {
            providerEntity("1:dashscope", "qwen-plus", "text-embedding-v3");

            assertThrows(BusinessException.class, () ->
                service.updateProvider("1:dashscope",
                    new UpdateProviderRequest(null, null, null, "", null, null, null)));
            verify(providerRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateProvider 拒绝空串 baseUrl / model / apiKey")
        void updateProviderRejectsBlankRequiredFields() {
            providerEntity("1:dashscope", "qwen-plus", null);

            assertThrows(BusinessException.class, () ->
                service.updateProvider("1:dashscope",
                    new UpdateProviderRequest("", null, null, null, null, null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("1:dashscope",
                    new UpdateProviderRequest("   ", null, null, null, null, null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("1:dashscope",
                    new UpdateProviderRequest(null, null, "", null, null, null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("1:dashscope",
                    new UpdateProviderRequest(null, "  ", null, null, null, null, null)));
        }
    }

    @Nested
    @DisplayName("全局默认 Provider")
    class DefaultProviderBehavior {

        @Test
        @DisplayName("updateDefaultProvider 拒绝不属于自己的 provider")
        void updateDefaultProviderRejectsForeignProvider() {
            when(providerRepository.findByIdAndUserId("2:glm", USER_ID)).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.updateDefaultProvider(new DefaultProviderDTO("2:glm"))
            );

            assertEquals(ErrorCode.PROVIDER_NOT_FOUND.getCode(), exception.getCode());
            verify(globalSettingRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateDefaultProvider 写入新的默认 provider，事务提交后失效该用户缓存")
        void updateDefaultProviderPersistsValue() {
            TransactionSynchronizationManager.initSynchronization();
            stubGlobalSetting("1:dashscope", "1:dashscope");
            providerEntity("1:glm", "glm-4-flash", null);

            service.updateDefaultProvider(new DefaultProviderDTO("1:glm"));
            fireAfterCommit();

            verify(globalSettingRepository).save(org.mockito.ArgumentMatchers.argThat(
                setting -> "1:glm".equals(setting.getDefaultChatProviderId())));
            verify(registry).reloadUser(USER_ID);
        }

        @Test
        @DisplayName("无设置行时 getDefaultProvider 返回空值 DTO 而不抛异常")
        void getDefaultProviderReturnsEmptyDtoWhenNoSettingRow() {
            when(globalSettingRepository.findById(USER_ID)).thenReturn(Optional.empty());

            DefaultProviderDTO dto = service.getDefaultProvider();

            assertNull(dto.defaultProvider());
            assertNull(dto.defaultEmbeddingProvider());
        }
    }

    @Nested
    @DisplayName("语音 ASR/TTS 配置入库")
    class VoiceConfigPersistence {

        private VoiceAiSettingEntity setting;

        @BeforeEach
        void stubSetting() {
            setting = VoiceAiSettingEntity.builder()
                .id(VoiceAiSettingEntity.SINGLETON_ID)
                .asrUrl("wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
                .asrModel("qwen3-asr-flash-realtime")
                .asrTurnDetectionSilenceMs(2000)
                .ttsModel("qwen3-tts-flash-realtime")
                .apiKeyCiphertext("")
                .apiKeyNonce("")
                .build();
            lenient().when(voiceAiSettingService.getSettingOrThrow()).thenReturn(setting);
        }

        @Test
        @DisplayName("updateAsrConfig 落库并同步内存、reload ASR")
        void updateAsrConfigPersistsAndSyncs() {
            service.updateAsrConfig(new AsrConfigRequest(
                null, "qwen3-asr-flash", null, null, null, null, null, null, null, 3000));

            assertEquals("qwen3-asr-flash", setting.getAsrModel());
            assertEquals(3000, setting.getAsrTurnDetectionSilenceMs());
            verify(voiceAiSettingRepository).save(setting);
            verify(voiceAiSettingService).replayToMemory(setting);
            verify(asrService).reload(voiceProperties);
            // 未传 apiKey 时不加密、不 reload TTS
            verify(voiceAiSettingService, never()).applyApiKey(any(), any());
            verify(ttsService, never()).reload(voiceProperties);
        }

        @Test
        @DisplayName("updateAsrConfig 携带 apiKey 时加密入库并双向 reload")
        void updateAsrConfigWithApiKeyReloadsBoth() {
            service.updateAsrConfig(new AsrConfigRequest(
                null, null, "sk-new-key", null, null, null, null, null, null, null));

            verify(voiceAiSettingService).applyApiKey(setting, "sk-new-key");
            verify(voiceAiSettingRepository).save(setting);
            verify(asrService).reload(voiceProperties);
            verify(ttsService).reload(voiceProperties);
        }

        @Test
        @DisplayName("updateTtsConfig 落库并同步内存、reload TTS")
        void updateTtsConfigPersistsAndSyncs() {
            service.updateTtsConfig(new TtsConfigRequest(
                "qwen3-tts-flash", null, "siyue", null, null, null, null, null, 80));

            assertEquals("qwen3-tts-flash", setting.getTtsModel());
            assertEquals("siyue", setting.getTtsVoice());
            assertEquals(80, setting.getTtsVolume());
            verify(voiceAiSettingRepository).save(setting);
            verify(voiceAiSettingService).replayToMemory(setting);
            verify(ttsService).reload(voiceProperties);
            verify(asrService, never()).reload(voiceProperties);
        }

        @Test
        @DisplayName("updateTtsConfig 拒绝空串 apiKey")
        void updateTtsConfigRejectsBlankApiKey() {
            assertThrows(BusinessException.class, () ->
                service.updateTtsConfig(new TtsConfigRequest(null, "  ", null, null, null, null, null, null, null)));
            verify(voiceAiSettingRepository, never()).save(any());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeConnectivityUrls(String baseUrl)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = LlmProviderConfigService.class.getDeclaredMethod(
            "buildConnectivityTestUrls",
            String.class
        );
        method.setAccessible(true);
        return (List<String>) method.invoke(service, baseUrl);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeConnectivityRequestBody(String model)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = LlmProviderConfigService.class.getDeclaredMethod(
            "buildConnectivityTestRequestBody",
            String.class
        );
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, model);
    }
}
