import { useRef, useState, useEffect } from 'react';
// @ts-ignore - vad 通过 script 标签加载
import { Mic, MicOff } from 'lucide-react';

// 声明全局 vad 对象（通过 index.html 中的 script 标签加载）
declare global {
  interface Window {
    vad: {
      MicVAD: {
        new: (config: any) => Promise<{
          start: () => Promise<void>;
          pause: () => void;
          destroy: () => void;
        }>;
      };
    };
  }
}

interface AudioRecorderProps {
  isRecording: boolean;
  disabled?: boolean;
  onRecordingChange: (isRecording: boolean) => void;
  onAudioData: (audioData: string) => void;
  onSpeechStart?: () => void;
  onSpeechEnd?: () => void;
}

export default function AudioRecorder({
  isRecording,
  disabled = false,
  onRecordingChange,
  onAudioData,
  onSpeechStart,
  onSpeechEnd,
}: AudioRecorderProps) {
  const [volume, setVolume] = useState(0);
  const mediaStreamRef = useRef<MediaStream | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const intervalRef = useRef<NodeJS.Timeout | null>(null);
  const vadRef = useRef<any>(null);
  const workletNodeRef = useRef<AudioWorkletNode | null>(null);
  const gainNodeRef = useRef<GainNode | null>(null);
  // 挂载标志，防止组件卸载后继续操作
  const mountedRef = useRef(true);
  // 录音生命周期标志，用于组件保持挂载时的异步回调。
  const recordingActiveRef = useRef(false);
  // 标志位，用于跟踪是否正处于启动过程中
  const startingRef = useRef(false);

  const TARGET_SAMPLE_RATE = 16000;

  const cleanupRecordingResources = (updateVolume = true) => {
    recordingActiveRef.current = false;

    if (vadRef.current) {
      try {
        vadRef.current.pause();
        vadRef.current.destroy?.();
      } catch (e) {
        // 忽略清理错误
      }
      vadRef.current = null;
    }

    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }

    if (workletNodeRef.current) {
      try {
        workletNodeRef.current.port.onmessage = null;
        workletNodeRef.current.disconnect();
      } catch (e) {
        // 忽略断开连接错误
      }
      workletNodeRef.current = null;
    }

    if (gainNodeRef.current) {
      try {
        gainNodeRef.current.disconnect();
      } catch (e) {
        // 忽略断开连接错误
      }
      gainNodeRef.current = null;
    }

    if (analyserRef.current) {
      try {
        analyserRef.current.disconnect();
      } catch (e) {
        // 忽略断开连接错误
      }
      analyserRef.current = null;
    }

    if (mediaStreamRef.current) {
      mediaStreamRef.current.getTracks().forEach(track => track.stop());
      mediaStreamRef.current = null;
    }

    if (audioContextRef.current) {
      try {
        audioContextRef.current.close();
      } catch (e) {
        // 忽略关闭错误
      }
      audioContextRef.current = null;
    }

    if (updateVolume) {
      setVolume(0);
    }
  };

  /**
   * 将 ArrayBuffer（Int16 PCM）转换为 Base64
   */
  const arrayBufferToBase64 = (buffer: ArrayBuffer): string => {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    const chunkSize = 0x8000;

    for (let i = 0; i < bytes.length; i += chunkSize) {
      const chunk = bytes.subarray(i, i + chunkSize);
      binary += String.fromCharCode(...chunk);
    }

    return btoa(binary);
  };

  const startRecording = async () => {
    // 防止并发重复启动
    if (startingRef.current) {
      return;
    }
    startingRef.current = true;

    try {
      if (!window.AudioContext) {
        throw new Error('当前浏览器不支持 AudioWorklet，请使用新版 Chrome/Edge');
      }

      // 第 1 步：获取麦克风流
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          sampleRate: TARGET_SAMPLE_RATE,
        },
      });

      // 检查异步操作期间组件是否已卸载
      if (!mountedRef.current) {
        stream.getTracks().forEach(track => track.stop());
        startingRef.current = false;
        return;
      }
      mediaStreamRef.current = stream;

      // 第 2 步：使用共享流初始化 VAD
      if (!window.vad || !window.vad.MicVAD) {
        throw new Error('VAD library not loaded. Please refresh the page.');
      }

      const vadInstance = await window.vad.MicVAD.new({
        getStream: async () => stream,
        onnxWASMBasePath: 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.22.0/dist/',
        baseAssetPath: 'https://cdn.jsdelivr.net/npm/@ricky0123/vad-web@0.0.29/dist/',
        onSpeechStart: () => {
          if (mountedRef.current) {
            onSpeechStart?.();
          }
        },
        onSpeechEnd: () => {
          if (mountedRef.current) {
            onSpeechEnd?.();
          }
        },
      });
      vadRef.current = vadInstance;
      await vadInstance.start();

      // 检查异步操作期间组件是否已卸载
      if (!mountedRef.current) {
        cleanupRecordingResources(false);
        startingRef.current = false;
        return;
      }

      // 第 3 步：创建 AudioContext
      const audioContext = new AudioContext({ sampleRate: TARGET_SAMPLE_RATE });
      if (!audioContext.audioWorklet) {
        await audioContext.close();
        throw new Error('当前浏览器不支持 AudioWorklet，请使用新版 Chrome/Edge');
      }
      const source = audioContext.createMediaStreamSource(stream);

      // 第 4 步：创建 Analyser 用于音量监测
      const analyser = audioContext.createAnalyser();
      analyser.fftSize = 256;
      source.connect(analyser);

      audioContextRef.current = audioContext;
      analyserRef.current = analyser;

      // 音量监测
      const dataArray = new Uint8Array(analyser.frequencyBinCount);
      intervalRef.current = setInterval(() => {
        if (!mountedRef.current || !analyserRef.current) {
          return;
        }
        analyser.getByteFrequencyData(dataArray);
        const average = dataArray.reduce((a, b) => a + b) / dataArray.length;
        setVolume(average);
      }, 100);

      // 第 5 步：加载 AudioWorklet 处理器
      const workletPath = '/audio-worklet/pcm-processor.js';
      await audioContext.audioWorklet.addModule(workletPath);

      // 检查异步操作期间组件是否已卸载
      if (!mountedRef.current) {
        cleanupRecordingResources(false);
        startingRef.current = false;
        return;
      }

      // 第 6 步：创建 AudioWorkletNode
      const workletNode = new AudioWorkletNode(audioContext, 'pcm-processor');
      workletNodeRef.current = workletNode;
      recordingActiveRef.current = true;

      // 处理来自 worklet 的音频数据块
      workletNode.port.onmessage = (event) => {
        if (!mountedRef.current || !recordingActiveRef.current || !workletNodeRef.current) {
          return;
        }
        const buffer = event.data as ArrayBuffer;
        const base64 = arrayBufferToBase64(buffer);
        onAudioData(base64);
      };

      // 第 7 步：创建增益节点以防止回声
      const gainNode = audioContext.createGain();
      gainNode.gain.value = 0; // 静音输出
      gainNodeRef.current = gainNode;

      // 第 8 步：连接音频处理链
      source.connect(workletNode);
      workletNode.connect(gainNode);
      gainNode.connect(audioContext.destination);

      startingRef.current = false;
      if (mountedRef.current) {
        onRecordingChange(true);
      }

    } catch (error) {
      startingRef.current = false;
      cleanupRecordingResources(mountedRef.current);
      if (!mountedRef.current) {
        return;
      }
      console.error('Error accessing microphone:', error);
      const message = error instanceof Error ? error.message : '无法访问麦克风，请检查权限设置';
      alert(message);
    }
  };

  const stopRecording = () => {
    // 防止在启动过程中执行停止
    startingRef.current = false;
    cleanupRecordingResources(mountedRef.current);
    // 仅在之前处于录音状态时才调用 onRecordingChange
    if (isRecording) {
      onRecordingChange(false);
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      stopRecording();
    };
  }, []);

  useEffect(() => {
    if ((disabled || !isRecording) && recordingActiveRef.current) {
      cleanupRecordingResources(mountedRef.current);
      if (isRecording) {
        onRecordingChange(false);
      }
    }
  }, [disabled, isRecording, onRecordingChange]);

  const toggleRecording = () => {
    if (disabled && !isRecording) {
      return;
    }
    if (isRecording) {
      stopRecording();
    } else {
      startRecording();
    }
  };

  return (
    <div className="relative flex items-center justify-center">
      {/* 录音时的音量波纹效果 */}
      {isRecording && (
        <div
          className="absolute rounded-full border border-primary-500/50 pointer-events-none transition-all duration-75"
          style={{
            width: `${100 + (volume / 255) * 100}%`,
            height: `${100 + (volume / 255) * 100}%`,
            opacity: Math.max(0, 1 - (volume / 255) * 1.5),
          }}
        />
      )}

      {/* 录音按钮 */}
      <button
        onClick={toggleRecording}
        disabled={disabled && !isRecording}
        className={`
          relative z-10 w-16 h-16 rounded-full flex items-center justify-center
          transition-all duration-300 shadow-xl
          ${disabled && !isRecording ? 'opacity-50 cursor-not-allowed shadow-none' : ''}
          ${isRecording
            ? 'bg-primary-500 hover:bg-primary-600 shadow-primary-500/40'
            : 'bg-slate-700 hover:bg-slate-600 shadow-slate-900/50'
          }
        `}
        title={disabled && !isRecording ? '语音识别准备中' : isRecording ? '停止录音' : '开始说话'}
      >
        {isRecording ? (
          <Mic className="w-7 h-7 text-white" />
        ) : (
          <MicOff className="w-7 h-7 text-slate-300" />
        )}
      </button>
    </div>
  );
}
