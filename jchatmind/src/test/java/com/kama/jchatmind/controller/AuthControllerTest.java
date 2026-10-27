package com.kama.jchatmind.controller;

import com.kama.jchatmind.config.AuthInterceptor;
import com.kama.jchatmind.mapper.UserMapper;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.User;
import com.kama.jchatmind.model.request.LoginRequest;
import com.kama.jchatmind.service.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    private UserMapper userMapper;
    private AuthController controller;
    private LoginRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        rateLimiter = new LoginRateLimiter();
        controller = new AuthController(userMapper, encoder, rateLimiter);
    }

    private User user(String hash) {
        return User.builder().id("u1").username("admin").passwordHash(hash)
                .enabled(true).createdAt(null).build();
    }

    @Test
    void correctCredentialsMarkSessionAsLoggedIn() {
        when(userMapper.selectByUsername("admin"))
                .thenReturn(user(new BCryptPasswordEncoder().encode("22374493")));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

        ApiResponse<?> response = controller.login(
                loginRequest("admin", "22374493"), request);

        assertEquals(200, response.getCode());
        assertEquals("admin", request.getSession().getAttribute(AuthInterceptor.SESSION_USER));
        assertEquals(0, rateLimiter.lockedSecondsRemaining("admin"));
    }

    @Test
    void wrongPasswordReturns401BodyCode() {
        when(userMapper.selectByUsername("admin"))
                .thenReturn(user(new BCryptPasswordEncoder().encode("22374493")));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

        ApiResponse<?> response = controller.login(loginRequest("admin", "wrong"), request);

        assertEquals(401, response.getCode());
        assertEquals("用户名或密码错误", response.getMessage());
        assertNull(request.getSession(false), "登录失败不应创建会话");
    }

    @Test
    void disabledUserCannotLogin() {
        User disabled = user(new BCryptPasswordEncoder().encode("22374493"));
        disabled.setEnabled(false);
        when(userMapper.selectByUsername("admin")).thenReturn(disabled);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

        ApiResponse<?> response = controller.login(loginRequest("admin", "22374493"), request);

        assertEquals(401, response.getCode());
    }

    @Test
    void repeatedFailuresTriggerLockoutMessage() {
        when(userMapper.selectByUsername("ghost")).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

        for (int i = 0; i < 4; i++) {  // 前 4 次失败仅计数
            assertEquals("用户名或密码错误",
                    controller.login(loginRequest("ghost", "x"), request).getMessage());
        }
        assertEquals("失败次数过多，账号已锁定 5 分钟",
                controller.login(loginRequest("ghost", "x"), request).getMessage());
        assertTrue(controller.login(loginRequest("ghost", "x"), request).getMessage()
                        .startsWith("失败次数过多，账号已锁定，请"),
                "锁定期内再次尝试应提示剩余秒数");
    }

    @Test
    void emptyInputIsRejectedWithoutTouchingDatabase() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

        assertEquals(401, controller.login(loginRequest("", ""), request).getCode());
        assertEquals(401, controller.login(loginRequest(null, null), request).getCode());
        verifyNoInteractions(userMapper);
    }

    @Test
    void meReflectsSessionState() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthInterceptor.SESSION_USER, "admin");
        assertEquals(200, controller.me(session).getCode());

        assertEquals(401, controller.me(new MockHttpSession()).getCode());
    }

    @Test
    void logoutInvalidatesSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthInterceptor.SESSION_USER, "admin");

        assertEquals(200, controller.logout(session).getCode());
        assertThrows(IllegalStateException.class,
                () -> session.getAttribute(AuthInterceptor.SESSION_USER),
                "会话销毁后访问属性应抛异常");
    }

    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
