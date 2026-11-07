package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;
import interview.guide.modules.llmprovider.service.VoiceAiSettingService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Qwen TTS Realtime 服务（基于 WebSocket）
 *
 * 通过 WebSocket API 使用阿里云 DashScope 的 qwen-tts-realtime 模型
 * 提供实时文本转语音合成。
 *
 * 主要特性：
 * - 基于 WebSocket 的实时 TTS 合成
 * - user-commit 模式，支持手动控制
 * - 同步合成 API，带 30 秒超时保护
 * - 通过 response.audio.delta 事件自动收集音频分片
 * - 支持中文，音色、语速、音量均可配置
 *
 * 配置：
 * - 模型：qwen-tts-realtime
 * - 音色：可配置（Cherry、Serena、Ethan 等）
 * - 音频格式：PCM，24kHz 采样率
 * - 模式：commit（由用户控制）
 *
 * @see QwenTtsRealtime
 * @see QwenTtsRealtimeCallback
 */
@Slf4j
@Service
@DependsOn(VoiceAiSettingService.BEAN_NAME)
public class QwenTtsService {

    // 运行时配置值（从 VoiceInterviewProperties 加载；保留 setter 供测试使用）
    private String model;

    private String apiKey;

    private String voice;

    private String format;

    private Integer sampleRate;

    private String mode;

    private String languageType;

    private Float speechRate;

    private Integer volume;

    public QwenTtsService(VoiceInterviewProperties voiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
    }

    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
        log.info("QwenTtsService 已重载: model={}, voice={}", model, voice);
    }

    private void applyTtsConfig(VoiceInterviewProperties.QwenTtsConfig tts) {
        this.model = tts.getModel();
        this.apiKey = tts.getApiKey();
        this.voice = tts.getVoice();
        this.format = tts.getFormat();
        this.sampleRate = tts.getSampleRate();
        this.mode = tts.getMode();
        this.languageType = tts.getLanguageType();
        this.speechRate = tts.getSpeechRate();
        this.volume = tts.getVolume();
    }

    /**
     * 将文本合成为语音音频。
     *
     * 该方法通过 DashScope 基于 WebSocket 的 TTS API 同步地将文本转换为 PCM 音频数据。
     * 它会建立 WebSocket 连接、发送待合成文本、收集音频分片，并返回完整音频数据。
     *
     * 方法使用 CountDownLatch 等待合成完成，并设置 30 秒超时以避免无限阻塞。
     *
     * @param text 待合成的文本（null、空串或纯空白文本返回空数组）
     * @return 配置采样率下的 PCM 音频数据，合成失败时返回空数组
     */
    public byte[] synthesize(String text) {
        // 处理 null、空串或纯空白文本
        if (text == null || text.trim().isEmpty()) {
            log.debug("文本为 null 或为空，返回空音频数组");
            return new byte[0];
        }

        log.debug("开始 TTS 合成: 文本长度 {} 字符", text.length());

        // Latch for synchronous waiting；等待异步 WebSocket 回调完成（初始计数 1）
        CountDownLatch synthesisLatch = new CountDownLatch(1);

        // Container for collected audio data；收集所有音频分片
        ByteArrayContainer audioContainer = new ByteArrayContainer();

        // Error container；捕获异步回调中的异常
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Response ID container for tracking；记录响应 ID（用于日志追踪）
        AtomicReference<String> responseIdRef = new AtomicReference<>();

        try {
            // 基于连接配置构建 QwenTtsRealtimeParam
            QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                    .model(model)
                    .apikey(apiKey)
                    .build();

            // 创建 WebSocket 事件的回调处理器
            QwenTtsRealtimeCallback callback = new QwenTtsRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("TTS WebSocket 连接已建立");
                }

                // 收到服务器消息时触发
                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(message, audioContainer, synthesisLatch, errorRef, responseIdRef);
                }

                @Override
                public void onClose(int code, String reason) {
                    log.debug("TTS WebSocket 已关闭: code={}, reason={}", code, reason);
                    synthesisLatch.countDown();// 防止TTL侧出错白等30s
                }
            };

            // 创建 QwenTtsRealtime 实例
            QwenTtsRealtime qwenTtsRealtime = new QwenTtsRealtime(param, callback);

            try {
                // 阻塞直到连接建立
                // 连接服务端（阻塞）
                qwenTtsRealtime.connect();

                // 用 TTS 参数配置会话
                QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
                        .voice(voice)
                        .responseFormat(getAudioFormat())
                        .mode(mode)  // "commit" 模式
                        .languageType(languageType)
                        .speechRate(speechRate)
                        .volume(volume)
                        .build();

                // 将配置更新到会话
                qwenTtsRealtime.updateSession(config);

                log.info("[TTS] 会话已配置音色 {}，开始合成文本（长度: {}）",
                         voice, text.length());

                // 以 commit 模式发送文本进行合成
                qwenTtsRealtime.appendText(text);// 发送文本
                qwenTtsRealtime.commit(); // 开始合成

                log.info("[TTS] 文本已发送至 TTS 服务，等待音频响应...");

                // 带超时地等待合成完成
                boolean completed = synthesisLatch.await(30, TimeUnit.SECONDS);

                if (!completed) {
                    log.error("TTS 合成超时（30 秒）");
                    return new byte[0];
                }

                // 检查是否发生了错误
                Throwable error = errorRef.get();
                if (error != null) {
                    log.error("TTS 合成失败", error);
                    return new byte[0];
                }

                // 返回收集到的音频数据
                byte[] audioData = audioContainer.toByteArray();
                log.info("[TTS] 合成成功: 音频数据 {} 字节, responseId={}",
                         audioData.length, responseIdRef.get());

                return audioData;

            } finally {
                // 确保连接被关闭
                try {
                    qwenTtsRealtime.close();
                } catch (Exception e) {
                    log.error("关闭 TTS 连接出错", e);
                }
            }

        } catch (InterruptedException e) {
            log.error("TTS 合成被中断", e);
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (Exception e) {
            log.error("文本合成失败", e);
            return new byte[0];
        }
    }

    /**
     * 获取 Qwen TTS Realtime 的音频格式。
     * 当前支持 24kHz PCM 格式。
     *
     * @return QwenTtsRealtimeAudioFormat 枚举值
     */
    private QwenTtsRealtimeAudioFormat getAudioFormat() {
        // Qwen TTS Realtime 默认使用 24kHz
        return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
    }

    /**
     * 销毁服务并清理资源。
     *
     * 该方法在 Spring 容器关闭时自动调用。
     * 当前无需清理持久化资源：每次合成都会创建自己的临时连接。
     */
    @PreDestroy
    public void destroy() {
        log.info("QwenTtsService 已销毁");
    }

    /**
     * 处理 DashScope TTS 服务下发的服务端事件。
     *
     * 该方法处理以下事件类型：
     * - session.created：会话创建成功
     * - session.updated：会话配置已更新
     * - response.audio.delta：收到音频分片
     * - response.done：响应完成
     * - error：发生错误
     *
     * @param message 服务端下发的 JSON 事件消息
     * @param audioContainer 收集音频分片的容器
     * @param synthesisLatch 用于通知完成的闭锁
     * @param errorRef 错误记录容器
     * @param responseIdRef 响应 ID 记录容器
     */
    private void handleServerEvent(JsonObject message, ByteArrayContainer audioContainer,
                                    CountDownLatch synthesisLatch, AtomicReference<Throwable> errorRef,
                                    AtomicReference<String> responseIdRef) {
        try {
            String eventType = message.get("type").getAsString();

            if (log.isTraceEnabled()) {
                log.trace("收到 TTS 事件: {}, 完整消息: {}", eventType, message);
            } else {
                log.debug("收到 TTS 事件: {}", eventType);
            }

            switch (eventType) {
                case "session.created":
                    String sessionId = message.has("session") && message.get("session").isJsonObject()
                            ? message.get("session").getAsJsonObject().get("id").getAsString()
                            : "unknown";
                    log.debug("TTS 会话已创建: {}", sessionId);
                    break;

                case "session.updated":
                    log.debug("TTS 会话配置已更新");
                    break;

                // 收到音频分片（Base64 编码）
                case "response.audio.delta":
                    // 收到音频分片 - delta 直接就是 base64 字符串
                    if (message.has("delta")) {
                        String audioBase64 = message.get("delta").getAsString(); // Base64 编码的音频数据
                        if (audioBase64 != null && !audioBase64.isEmpty()) {
                            byte[] audioChunk = Base64.getDecoder().decode(audioBase64);
                            audioContainer.append(audioChunk);
                            log.trace("收到音频分片: {} 字节", audioChunk.length);
                        }
                    }
                    break;

                case "response.done":
                    // 响应完成 - 这是 Qwen TTS API 的最后一个事件
                    String responseId = responseIdRef.get();
                    log.debug("TTS 响应完成: responseId={}", responseId);
                    synthesisLatch.countDown();
                    break;

                case "error":
                    // 错误事件
                    if (message.has("error")) {
                        var errorElement = message.get("error");
                        String errorType = "unknown";
                        String errorCode = "unknown";
                        String errorMessage = "Unknown error";

                        if (errorElement.isJsonObject()) {
                            JsonObject errorObj = errorElement.getAsJsonObject();
                            errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                            errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                            errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";
                        } else {
                            errorMessage = errorElement.toString();
                        }

                        String fullErrorMessage = String.format("TTS 错误 [%s/%s]: %s", errorType, errorCode, errorMessage);
                        log.error("{}", fullErrorMessage);

                        errorRef.set(new IllegalStateException(fullErrorMessage));
                        synthesisLatch.countDown();
                    }
                    break;

                default:
                    log.trace("未处理的 TTS 事件类型: {}", eventType);
            }

        } catch (Exception e) {
            log.error("处理 TTS 服务端事件出错", e);
            errorRef.set(e);
            synthesisLatch.countDown();
        }
    }

    /**
     * 内部类，用于高效收集音频分片。
     * 使用 ByteArrayOutputStream 获得均摊 O(1) 的追加性能，
     * 避免手动扩容数组带来的 O(n²) 拷贝。
     */
    private static class ByteArrayContainer {
        private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        public synchronized void append(byte[] chunk) {
            baos.write(chunk, 0, chunk.length);
        }

        public synchronized byte[] toByteArray() {
            return baos.toByteArray();
        }
    }

    // 配置 setter（供 Spring @Value 注入或测试使用）

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setLanguageType(String languageType) {
        this.languageType = languageType;
    }

    public void setSpeechRate(Float speechRate) {
        this.speechRate = speechRate;
    }

    public void setVolume(Integer volume) {
        this.volume = volume;
    }
}
