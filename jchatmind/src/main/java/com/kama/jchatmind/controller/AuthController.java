package com.kama.jchatmind.controller;

import com.kama.jchatmind.config.AuthInterceptor;
import com.kama.jchatmind.mapper.UserMapper;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.User;
import com.kama.jchatmind.model.request.LoginRequest;
import com.kama.jchatmind.service.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录/登出/登录态查询；会话标记见 AuthInterceptor.SESSION_USER
 */
@RestController
@RequestMapping("/api/auth")
@AllArgsConstructor
public class AuthController {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@RequestBody LoginRequest request,
                                                  HttpServletRequest httpRequest) {
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();
        if (username.isEmpty() || password.isEmpty()) {
            return ApiResponse.error(ApiResponse.ApiCode.UNAUTHORIZED, "用户名或密码不能为空");
        }
        long lockSeconds = rateLimiter.lockedSecondsRemaining(username);
        if (lockSeconds > 0) {
            return ApiResponse.error(ApiResponse.ApiCode.UNAUTHORIZED,
                    "失败次数过多，账号已锁定，请 " + lockSeconds + " 秒后重试");
        }
        User user = userMapper.selectByUsername(username);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            boolean locked = rateLimiter.recordFailure(username);
            return ApiResponse.error(ApiResponse.ApiCode.UNAUTHORIZED, locked
                    ? "失败次数过多，账号已锁定 5 分钟" : "用户名或密码错误");
        }
        rateLimiter.reset(username);
        // 换 sessionId 防会话固定攻击，再写入登录标记
        httpRequest.getSession(true);
        httpRequest.changeSessionId();
        httpRequest.getSession().setAttribute(AuthInterceptor.SESSION_USER, user.getUsername());
        return ApiResponse.success(Map.of("username", user.getUsername()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpSession session) {
        session.invalidate();
        return ApiResponse.success();
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me(HttpSession session) {
        Object username = session.getAttribute(AuthInterceptor.SESSION_USER);
        if (username == null) {
            return ApiResponse.error(ApiResponse.ApiCode.UNAUTHORIZED, "未登录");
        }
        return ApiResponse.success(Map.of("username", username.toString()));
    }
}
