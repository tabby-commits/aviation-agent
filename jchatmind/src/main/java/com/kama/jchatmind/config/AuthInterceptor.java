package com.kama.jchatmind.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 会话登录态拦截器：session 无登录标记返回 401（JSON 体与 ApiResponse 结构一致）
 * 拦截范围与放行清单见 AuthWebConfig
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** session 中存放已登录用户名的属性名 */
    public static final String SESSION_USER = "LOGIN_USER";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // CORS 预检请求不携带 Cookie，直接放行（CorsFilter 已处理）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_USER) != null) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
        return false;
    }
}
