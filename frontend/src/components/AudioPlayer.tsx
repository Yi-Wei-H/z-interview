import React, { useRef, useEffect, useState } from 'react';
import { Play, Pause, Volume2, VolumeX } from 'lucide-react';

interface AudioPlayerProps {
  audioData: string; // Base64 编码的音频
  text?: string;
  onPlayEnd?: () => void;
}

/**
 * 音频播放器组件
 * 用于播放AI语音合成生成的MP3音频
 */
export default function AudioPlayer({ audioData, text, onPlayEnd }: AudioPlayerProps) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [isMuted, setIsMuted] = useState(false);
  const [volume, setVolume] = useState(1);

  useEffect(() => {
    if (audioData && audioRef.current) {
      audioRef.current.src = `data:audio/mp3;base64,${audioData}`;
      // 收到新音频数据时自动播放
      audioRef.current.play().then(() => {
        setIsPlaying(true);
      }).catch((error) => {
        console.error('Auto-play failed:', error);
        // 用户尚未与页面交互时，自动播放可能会失败
      });
    }
  }, [audioData]);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    const handleEnded = () => {
      setIsPlaying(false);
      onPlayEnd?.();
    };

    audio.addEventListener('ended', handleEnded);
    return () => audio.removeEventListener('ended', handleEnded);
  }, [onPlayEnd]);

  const togglePlay = () => {
    if (!audioRef.current) return;

    if (isPlaying) {
      audioRef.current.pause();
    } else {
      audioRef.current.play();
    }
    setIsPlaying(!isPlaying);
  };

  const toggleMute = () => {
    if (!audioRef.current) return;
    audioRef.current.muted = !isMuted;
    setIsMuted(!isMuted);
  };

  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newVolume = parseFloat(e.target.value);
    if (audioRef.current) {
      audioRef.current.volume = newVolume;
    }
    setVolume(newVolume);
  };

  if (!audioData) {
    return null;
  }

  return (
    <div className="flex flex-col gap-3">
      {/* 音频元素（隐藏） */}
      <audio ref={audioRef} />

      {/* 文本显示 */}
      {text && (
        <div className="p-4 bg-slate-50 rounded-lg border border-slate-200">
          <p className="text-slate-700">{text}</p>
        </div>
      )}

      {/* 控制区 */}
      <div className="flex items-center gap-4">
        {/* 播放/暂停按钮 */}
        <button
          onClick={togglePlay}
          className="w-12 h-12 rounded-full bg-primary-500 hover:bg-primary-600
                     flex items-center justify-center text-white transition-colors"
        >
          {isPlaying ? (
            <Pause className="w-6 h-6" />
          ) : (
            <Play className="w-6 h-6" />
          )}
        </button>

        {/* 音量控制 */}
        <div className="flex items-center gap-2">
          <button onClick={toggleMute} className="text-slate-400 hover:text-slate-600">
            {isMuted ? (
              <VolumeX className="w-5 h-5" />
            ) : (
              <Volume2 className="w-5 h-5" />
            )}
          </button>
          <input
            type="range"
            min="0"
            max="1"
            step="0.1"
            value={volume}
            onChange={handleVolumeChange}
            className="w-24"
          />
        </div>
      </div>
    </div>
  );
}
