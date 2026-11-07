package interview.guide.modules.llmprovider.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.model.VoiceAiSettingEntity;
import interview.guide.modules.llmprovider.repository.VoiceAiSettingRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 语音 ASR/TTS 全局配置（voice_ai_setting 单例行）的启动回放与运行时读写辅助。
 *
 * 启动时（@PostConstruct，先于 QwenAsrService/QwenTtsService 初始化，见二者 @DependsOn）：
 * 行存在 → 回放 DB 值到 voiceProperties 内存 bean；行不存在（未执行 03-seed.sql）→ 用内存
 * 默认值自愈建行并回放。页面修改时由 LlmProviderConfigService 更新 DB 行后调用 replayToMemory 同步内存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceAiSettingService {

  public static final String BEAN_NAME = "voiceAiSettingService";

  /** voice_ai_setting 密文为空时回退的环境变量（与 interview.env 保留项一致，不落库） */
  static final String VOICE_API_KEY_ENV = "AI_BAILIAN_API_KEY";

  private final VoiceAiSettingRepository repository;
  private final ApiKeyEncryptionService encryptionService;
  private final VoiceInterviewProperties voiceProperties;

  @PostConstruct
  @Transactional
  public void init() {
    VoiceAiSettingEntity setting = repository.findById(VoiceAiSettingEntity.SINGLETON_ID)
        .orElseGet(this::selfHealFromProperties);
    replayToMemory(setting);
    log.info("语音 AI 配置已加载: asrModel={}, ttsModel={}, apiKeySource={}",
        setting.getAsrModel(), setting.getTtsModel(), apiKeySource(setting));
  }

  public VoiceAiSettingEntity getSettingOrThrow() {
    return repository.findById(VoiceAiSettingEntity.SINGLETON_ID)
        .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_CONFIG_READ_FAILED,
            "语音 ASR/TTS 配置未初始化"));
  }

  /**
   * 将 DB 行回放到 voiceProperties 内存 bean（yml qwen 节删除后，内存模型参数的唯一来源）。
   * null 列跳过不覆盖；API Key 三级解析：库中密文 → 环境变量 → 保持现值（测试 profile 注入的 key）。
   */
  public void replayToMemory(VoiceAiSettingEntity setting) {
    VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
    VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();

    if (setting.getAsrUrl() != null) asr.setUrl(setting.getAsrUrl());
    if (setting.getAsrModel() != null) asr.setModel(setting.getAsrModel());
    if (setting.getAsrLanguage() != null) asr.setLanguage(setting.getAsrLanguage());
    if (setting.getAsrFormat() != null) asr.setFormat(setting.getAsrFormat());
    if (setting.getAsrSampleRate() != null) asr.setSampleRate(setting.getAsrSampleRate());
    if (setting.getAsrEnableTurnDetection() != null) asr.setEnableTurnDetection(setting.getAsrEnableTurnDetection());
    if (setting.getAsrTurnDetectionType() != null) asr.setTurnDetectionType(setting.getAsrTurnDetectionType());
    if (setting.getAsrTurnDetectionThreshold() != null) asr.setTurnDetectionThreshold(setting.getAsrTurnDetectionThreshold().floatValue());
    if (setting.getAsrTurnDetectionSilenceMs() != null) asr.setTurnDetectionSilenceDurationMs(setting.getAsrTurnDetectionSilenceMs());

    if (setting.getTtsModel() != null) tts.setModel(setting.getTtsModel());
    if (setting.getTtsVoice() != null) tts.setVoice(setting.getTtsVoice());
    if (setting.getTtsFormat() != null) tts.setFormat(setting.getTtsFormat());
    if (setting.getTtsSampleRate() != null) tts.setSampleRate(setting.getTtsSampleRate());
    if (setting.getTtsMode() != null) tts.setMode(setting.getTtsMode());
    if (setting.getTtsLanguageType() != null) tts.setLanguageType(setting.getTtsLanguageType());
    if (setting.getTtsSpeechRate() != null) tts.setSpeechRate(setting.getTtsSpeechRate().floatValue());
    if (setting.getTtsVolume() != null) tts.setVolume(setting.getTtsVolume());

    String apiKey = resolveApiKey(setting);
    if (apiKey != null) {
      // ASR/TTS 共用同一把 DashScope Key（页面任一处填写即双向生效）
      asr.setApiKey(apiKey);
      tts.setApiKey(apiKey);
    }
  }

  /**
   * 解析当前生效的 API Key：库中密文非空则解密，否则回退环境变量 AI_BAILIAN_API_KEY；
   * 均未配置返回 null（由调用方决定是否保留内存现值）。
   */
  public String resolveApiKey(VoiceAiSettingEntity setting) {
    if (setting.getApiKeyCiphertext() != null && !setting.getApiKeyCiphertext().isBlank()) {
      return encryptionService.decrypt(setting.getApiKeyNonce(), setting.getApiKeyCiphertext());
    }
    String envKey = System.getenv(VOICE_API_KEY_ENV);
    return envKey == null || envKey.isBlank() ? null : envKey;
  }

  /** 页面填写/修改 Key 时加密写入实体（ASR/TTS 共用，nonce 与密文成对更新） */
  public void applyApiKey(VoiceAiSettingEntity setting, String plainApiKey) {
    ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(plainApiKey);
    setting.setApiKeyNonce(encrypted.nonce());
    setting.setApiKeyCiphertext(encrypted.ciphertext());
  }

  private String apiKeySource(VoiceAiSettingEntity setting) {
    if (setting.getApiKeyCiphertext() != null && !setting.getApiKeyCiphertext().isBlank()) {
      return "database";
    }
    return resolveApiKey(setting) != null ? VOICE_API_KEY_ENV : "unset";
  }

  // 未执行 03-seed.sql 的部署：用 VoiceInterviewProperties 的 Java 默认值建行（API Key 留空）
  private VoiceAiSettingEntity selfHealFromProperties() {
    VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
    VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
    VoiceAiSettingEntity entity = VoiceAiSettingEntity.builder()
        .id(VoiceAiSettingEntity.SINGLETON_ID)
        .asrUrl(asr.getUrl())
        .asrModel(asr.getModel())
        .asrLanguage(asr.getLanguage())
        .asrFormat(asr.getFormat())
        .asrSampleRate(asr.getSampleRate())
        .asrEnableTurnDetection(asr.isEnableTurnDetection())
        .asrTurnDetectionType(asr.getTurnDetectionType())
        .asrTurnDetectionThreshold((double) asr.getTurnDetectionThreshold())
        .asrTurnDetectionSilenceMs(asr.getTurnDetectionSilenceDurationMs())
        .ttsModel(tts.getModel())
        .ttsVoice(tts.getVoice())
        .ttsFormat(tts.getFormat())
        .ttsSampleRate(tts.getSampleRate())
        .ttsMode(tts.getMode())
        .ttsLanguageType(tts.getLanguageType())
        .ttsSpeechRate((double) tts.getSpeechRate())
        .ttsVolume(tts.getVolume())
        .apiKeyCiphertext("")
        .apiKeyNonce("")
        .build();
    log.warn("voice_ai_setting 行缺失（可能未执行 03-seed.sql），已用默认值自愈建行");
    return repository.save(entity);
  }
}
