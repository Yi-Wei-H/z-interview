package interview.guide.modules.user.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.user.context.UserContext;
import interview.guide.modules.user.dto.ChangePasswordRequest;
import interview.guide.modules.user.dto.UpdateUsernameRequest;
import interview.guide.modules.user.dto.UserDTO;
import interview.guide.modules.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    public Result<UserDTO> profile() {
        return Result.success(userService.getCurrentUser(UserContext.requireUserId()));
    }

    @PutMapping("/username")
    public Result<UserDTO> updateUsername(@RequestBody @Valid UpdateUsernameRequest request) {
        return Result.success(userService.updateUsername(UserContext.requireUserId(), request.username()));
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        userService.changePassword(UserContext.requireUserId(), request.oldPassword(), request.newPassword());
        return Result.success();
    }
}
