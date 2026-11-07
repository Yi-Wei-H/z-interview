package interview.guide.modules.user.dto;

import interview.guide.modules.user.model.UserEntity;

public record UserDTO(Long id, String userid, String username) {

    public static UserDTO from(UserEntity entity) {
        return new UserDTO(entity.getId(), entity.getUserid(), entity.getUsername());
    }
}
