package interview.guide.modules.user.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.dto.ChangePasswordRequest;
import interview.guide.modules.user.dto.LoginResponse;
import interview.guide.modules.user.dto.RegisterRequest;
import interview.guide.modules.user.dto.UserDTO;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    /** 大写字母+数字，剔除易混淆的 0/O/1/I */
    public static final String USERID_CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int USERID_LENGTH = 8;
    private static final int MAX_GENERATE_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    @Transactional
    public UserDTO register(RegisterRequest request) {
        if (request.password() == null || request.password().length() < 8) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_SHORT);
        }
        UserEntity entity = UserEntity.builder()
                .userid(generateUniqueUserid())
                .username(normalizeUsername(request.username()))
                .password(passwordEncoder.encode(request.password()))
                .status(1)
                .build();
        userRepository.save(entity);
        log.info("新用户注册: userid={}", entity.getUserid());
        return UserDTO.from(entity);
    }

    public LoginResponse login(String userid, String password) {
        UserEntity user = userRepository.findByUserid(userid)
                .orElseThrow(() -> new BusinessException(ErrorCode.USERID_OR_PASSWORD_ERROR));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.USERID_OR_PASSWORD_ERROR);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        return new LoginResponse(tokenService.create(user.getId()), UserDTO.from(user));
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            tokenService.remove(token);
        }
    }

    public UserDTO getCurrentUser(Long userId) {
        return UserDTO.from(userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGGED_IN)));
    }

    @Transactional
    public UserDTO updateUsername(Long userId, String username) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGGED_IN));
        user.setUsername(normalizeUsername(username));
        return UserDTO.from(userRepository.save(user));
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_SHORT);
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGGED_IN));
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_MISMATCH);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private String generateUniqueUserid() {
        for (int i = 0; i < MAX_GENERATE_ATTEMPTS; i++) {
            StringBuilder sb = new StringBuilder(USERID_LENGTH);
            for (int j = 0; j < USERID_LENGTH; j++) {
                sb.append(USERID_CHARSET.charAt(RANDOM.nextInt(USERID_CHARSET.length())));
            }
            if (!userRepository.existsByUserid(sb.toString())) {
                return sb.toString();
            }
        }
        throw new BusinessException(ErrorCode.USERID_GENERATION_FAILED);
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String trimmed = username.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
