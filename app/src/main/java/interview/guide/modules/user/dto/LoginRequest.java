package interview.guide.modules.user.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String userid, @NotBlank String password) {
}
