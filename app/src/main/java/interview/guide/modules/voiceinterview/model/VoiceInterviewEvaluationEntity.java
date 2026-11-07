package interview.guide.modules.voiceinterview.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 语音面试评估实体（Voice Interview Evaluation Entity）
 * 语音面试评估实体
 * <p>
 * 以与文字面试对齐的格式存储评估结果：
 * 逐题评估、总体反馈、优点、改进点与参考答案。
 * 所有结构化数据（数组/对象）均以 JSON TEXT 列存储。
 * </p>
 */
@Entity
@Table(name = "voice_interview_evaluations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", unique = true)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "overall_score")
    private Integer overallScore;

    @Column(name = "overall_feedback", columnDefinition = "TEXT")
    private String overallFeedback;

    @Column(name = "question_evaluations_json", columnDefinition = "TEXT")
    private String questionEvaluationsJson;

    @Column(name = "strengths_json", columnDefinition = "TEXT")
    private String strengthsJson;

    @Column(name = "improvements_json", columnDefinition = "TEXT")
    private String improvementsJson;

    @Column(name = "reference_answers_json", columnDefinition = "TEXT")
    private String referenceAnswersJson;

    @Column(name = "interviewer_role")
    private String interviewerRole;

    @Column(name = "interview_date")
    private LocalDateTime interviewDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
