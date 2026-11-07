package interview.guide.modules.user.interceptor;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.user.context.UserContext;
import interview.guide.modules.user.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock
    private TokenService tokenService;

    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // 字段初始化器会先于 Mockito 注入 @Mock 执行，必须在 @BeforeEach 中构造
        interceptor = new AuthInterceptor(tokenService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void optionsPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/resumes");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void missingTokenWritesNotLoggedIn() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resumes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("请先登录"));
        assertNull(UserContext.getUserId());
    }

    @Test
    void invalidTokenWritesNotLoggedIn() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resumes");
        request.addHeader("Authorization", "Bearer bad-token");
        when(tokenService.resolveUserId("bad-token")).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertTrue(response.getContentAsString().contains("请先登录"));
    }

    @Test
    void validTokenFillsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resumes");
        request.addHeader("Authorization", "Bearer good");
        when(tokenService.resolveUserId("good")).thenReturn(Optional.of(7L));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(7L, UserContext.getUserId());
    }

    @Test
    void afterCompletionClearsContext() {
        UserContext.setUserId(7L);
        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);
        assertNull(UserContext.getUserId());
    }
}
