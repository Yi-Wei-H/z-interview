package interview.guide.modules.user.interceptor;

import interview.guide.modules.user.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * WS 握手鉴权拦截器单元测试
 *
 * <p>验证握手阶段 token 参数校验：
 * <ul>
 *   <li>有效 token → userId 写入 attributes，握手放行</li>
 *   <li>无效/缺失 token → 拒绝握手并返回 401</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WsAuthHandshakeInterceptorTest {

    @Mock
    private TokenService tokenService;

    private WsAuthHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // 字段初始化器会先于 Mockito @Mock 注入执行，必须在 setUp 中构造
        interceptor = new WsAuthHandshakeInterceptor(tokenService);
    }

    @Test
    void validTokenPutsUserIdAndPasses() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/voice-interview/1");
        servletRequest.setParameter("token", "good");
        when(tokenService.resolveUserId("good")).thenReturn(Optional.of(7L));

        var attributes = new HashMap<String, Object>();
        assertTrue(interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest), new ServletServerHttpResponse(new MockHttpServletResponse()), null, attributes));
        assertEquals(7L, attributes.get(WsAuthHandshakeInterceptor.USER_ID_ATTR));
    }

    @Test
    void invalidTokenRejected() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/voice-interview/1");
        servletRequest.setParameter("token", "bad");
        when(tokenService.resolveUserId("bad")).thenReturn(Optional.empty());

        var response = new MockHttpServletResponse();
        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest), new ServletServerHttpResponse(response), null, new HashMap<>()));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
    }

    @Test
    void missingTokenRejected() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/voice-interview/1");

        var response = new MockHttpServletResponse();
        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest), new ServletServerHttpResponse(response), null, new HashMap<>()));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
    }
}
