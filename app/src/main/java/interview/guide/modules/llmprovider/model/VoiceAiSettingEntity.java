package interview.guide.modules.llmprovider.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语音面试 ASR/TTS 全局配置（单例行，主键固定为 1）。
 * 页面修改后落库；启动时由 VoiceAiSettingService 回放到内存 bean。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "voice_ai_setting")
public class VoiceAiSettingEntity {

  public static final Long SINGLETON_ID = 1L;

  /** 主键，固定为 1（单例配置行） */
  @Id
  private Long id;

  /** Qwen Realtime ASR 的 WebSocket 服务地址（如 wss://dashscope.aliyuncs.com/api-ws/v1/realtime） */
  @Column(name = "asr_url", length = 512)
  private String asrUrl;

  /** 实时语音识别模型名（如 qwen3-asr-flash-realtime） */
  @Column(name = "asr_model", length = 128)
  private String asrModel;

  /** 识别语言代码（如 zh 中文） */
  @Column(name = "asr_language", length = 16)
  private String asrLanguage;

  /** 上传音频格式（如 pcm） */
  @Column(name = "asr_format", length = 16)
  private String asrFormat;

  /** 识别采样率，单位 Hz（如 16000） */
  @Column(name = "asr_sample_rate")
  private Integer asrSampleRate;

  /** 是否启用服务端 VAD 自动断句 */
  @Column(name = "asr_enable_turn_detection")
  private Boolean asrEnableTurnDetection;

  /** 断句方式（如 server_vad） */
  @Column(name = "asr_turn_detection_type", length = 32)
  private String asrTurnDetectionType;

  /** VAD 静音灵敏度阈值（0.0-1.0，越小越灵敏） */
  @Column(name = "asr_turn_detection_threshold")
  private Double asrTurnDetectionThreshold;

  /** 静音持续多少毫秒判定一句话结束（如 2000） */
  @Column(name = "asr_turn_detection_silence_ms")
  private Integer asrTurnDetectionSilenceMs;

  /** 语音合成模型名（如 qwen3-tts-flash-realtime） */
  @Column(name = "tts_model", length = 128)
  private String ttsModel;

  /** 合成音色（如 Cherry、siyue） */
  @Column(name = "tts_voice", length = 64)
  private String ttsVoice;

  /** 合成音频格式（如 pcm） */
  @Column(name = "tts_format", length = 16)
  private String ttsFormat;

  /** 合成采样率，单位 Hz（如 24000） */
  @Column(name = "tts_sample_rate")
  private Integer ttsSampleRate;

  /** 合成提交模式（如 commit） */
  @Column(name = "tts_mode", length = 32)
  private String ttsMode;

  /** 合成语言类型（如 Chinese） */
  @Column(name = "tts_language_type", length = 32)
  private String ttsLanguageType;

  /** 语速倍率（1.0 为正常） */
  @Column(name = "tts_speech_rate")
  private Double ttsSpeechRate;

  /** 音量（0-100） */
  @Column(name = "tts_volume")
  private Integer ttsVolume;

  /** 加密后的 DashScope API Key（空串表示未配置，运行时回退环境变量 AI_BAILIAN_API_KEY） */
  @Column(name = "api_key_ciphertext", nullable = false, length = 4096)
  private String apiKeyCiphertext;

  /** API Key 加密随机数（nonce），与密文配对 */
  @Column(name = "api_key_nonce", nullable = false, length = 64)
  private String apiKeyNonce;

  /** 创建时间 */
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  /** 最后更新时间 */
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
