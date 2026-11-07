package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语音面试评估状态响应 DTO
 * 语音面试评估状态响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationStatusDTO {

    /**
     * 评估任务状态：PENDING / PROCESSING / COMPLETED / FAILED
     */
    private String evaluateStatus;

    /**
     * 状态为 FAILED 时的错误信息
     */
    private String evaluateError;

    /**
     * 完整评估结果，仅状态为 COMPLETED 时存在
     */
    private VoiceEvaluationDetailDTO evaluation;
}
