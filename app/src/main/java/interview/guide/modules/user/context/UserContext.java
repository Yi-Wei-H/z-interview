package interview.guide.modules.user.context;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;

/** 当前登录用户上下文（MVC 由 AuthInterceptor 填充；异步消费线程手动 set/clear） */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    /** 未登录直接抛 12001「请先登录」 */
    public static Long requireUserId() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw new BusinessException(ErrorCode.NOT_LOGGED_IN);
        }
        return userId;
    }

    public static void clear() {
        USER_ID.remove();
    }
}
