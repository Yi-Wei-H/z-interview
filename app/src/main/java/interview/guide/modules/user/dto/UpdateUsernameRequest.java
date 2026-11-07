package interview.guide.modules.user.dto;

import jakarta.validation.constraints.Size;

public record UpdateUsernameRequest(@Size(max = 64) String username) {
}
