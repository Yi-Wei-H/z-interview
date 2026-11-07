/**
 * 用于 PCM 音频采集的 AudioWorkletProcessor
 *
 * 职责：
 * - 接收麦克风输入（Float32）
 * - 重采样到 16kHz 目标采样率
 * - 将 Float32 转换为 Int16 PCM
 * - 累积采样点，并通过 postMessage 发送 200ms 数据块（3200 个采样点）
 */
class PcmProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.targetSampleRate = 16000;
    this.pending = [];
    this.pendingLength = 0;
    this.samplesPerChunk = 3200; // 16kHz 采样率下的 200ms
  }

  /**
   * @param {Float32Array[]} inputs
   * @param {Float32Array[]} outputs
   * @param {Record<string, unknown>} parameters
   * @returns {boolean}
   */
  process(inputs, outputs, parameters) {
    const input = inputs[0]?.[0];
    if (!input || input.length === 0) {
      return true;
    }

    const resampled = this.resample(input, sampleRate, this.targetSampleRate);
    const pcm = this.float32ToInt16(resampled);
    this.enqueue(pcm);
    this.flushChunks();

    return true;
  }

  /**
   * 线性插值重采样
   * @param {Float32Array} input
   * @param {number} sourceRate
   * @param {number} targetRate
   * @returns {Float32Array}
   */
  resample(input, sourceRate, targetRate) {
    if (sourceRate === targetRate) {
      return input;
    }

    const ratio = sourceRate / targetRate;
    const outputLength = Math.max(1, Math.round(input.length / ratio));
    const output = new Float32Array(outputLength);

    for (let i = 0; i < outputLength; i++) {
      const sourceIndex = i * ratio;
      const lower = Math.floor(sourceIndex);
      const upper = Math.min(lower + 1, input.length - 1);
      const weight = sourceIndex - lower;
      output[i] = input[lower] * (1 - weight) + input[upper] * weight;
    }

    return output;
  }

  /**
   * 将 Float32 转换为 Int16 PCM
   * @param {Float32Array} input
   * @returns {Int16Array}
   */
  float32ToInt16(input) {
    const output = new Int16Array(input.length);
    for (let i = 0; i < input.length; i++) {
      const s = Math.max(-1, Math.min(1, input[i]));
      output[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
    }
    return output;
  }

  /**
   * 将 PCM 数据块加入待处理缓冲区
   * @param {Int16Array} pcm
   */
  enqueue(pcm) {
    this.pending.push(pcm);
    this.pendingLength += pcm.length;
  }

  /**
   * 将完整的数据块（每块 200ms）刷新发送到主线程
   */
  flushChunks() {
    while (this.pendingLength >= this.samplesPerChunk && this.pending.length > 0) {
      const chunk = new Int16Array(this.samplesPerChunk);
      let offset = 0;

      while (offset < this.samplesPerChunk && this.pending.length > 0) {
        const head = this.pending[0];
        if (!head || head.length === 0) {
          this.pending.shift();
          continue;
        }

        const take = Math.min(head.length, this.samplesPerChunk - offset);
        chunk.set(head.subarray(0, take), offset);

        if (take === head.length) {
          this.pending.shift();
        } else {
          this.pending[0] = head.subarray(take);
        }

        offset += take;
        this.pendingLength -= take;
      }

      // 发送数据块（采用克隆模式，以避免 Transferable 相关问题）
      try {
        this.port.postMessage(chunk.buffer);
      } catch (e) {
        // 忽略关闭过程中的 postMessage 错误
      }
    }

    // 如果缓冲区已空但长度不一致，则重置
    if (this.pending.length === 0) {
      this.pendingLength = 0;
    }
  }
}

registerProcessor('pcm-processor', PcmProcessor);
