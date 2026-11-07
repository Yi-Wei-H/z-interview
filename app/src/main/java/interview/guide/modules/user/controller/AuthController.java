package interview.guide.modules.user.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.user.context.UserContext;
import interview.guide.modules.user.dto.LoginRequest;
import interview.guide.modules.user.dto.LoginResponse;
import interview.guide.modules.user.dto.RegisterRequest;
import interview.guide.modules.user.dto.UserDTO;
import interview.guide.modules.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<UserDTO> register(@RequestBody @Valid RegisterRequest request) {
        return Result.success(userService.register(request));
    }

    @PostMapping("/login")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return Result.success(userService.login(request.userid(), request.password()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        userService.logout(token);
        return Result.success();
    }

    @GetMapping("/me")
    public Result<UserDTO> me() {
        return Result.success(userService.getCurrentUser(UserContext.requireUserId()));
    }
}
