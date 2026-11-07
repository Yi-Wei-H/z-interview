package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话响应，包含 WebSocket URL
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponseDTO {
    private Long sessionId;
    private String roleType;
    private String currentPhase;
    private String status;
    private LocalDateTime startTime;
    private Integer plannedDuration;
    private String webSocketUrl;
}
