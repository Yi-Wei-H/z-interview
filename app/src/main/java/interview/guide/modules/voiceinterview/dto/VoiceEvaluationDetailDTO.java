package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语音评估详情 DTO —— 与文字面试的 InterviewDetailDTO 字段对齐，
 * 便于前端复用 InterviewDetailPanel 进行渲染。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationDetailDTO {

    private Long sessionId;
    private int totalQuestions;
    private int overallScore;
    private String overallFeedback;
    private List<String> strengths;
    private List<String> improvements;
    private List<AnswerDetail> answers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerDetail {
        private int questionIndex;
        private String question;
        private String category;
        private String userAnswer;
        private int score;
        private String feedback;
        private String referenceAnswer;
        private List<String> keyPoints;
    }
}
