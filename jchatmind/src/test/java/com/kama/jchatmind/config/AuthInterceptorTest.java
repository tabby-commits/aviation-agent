package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class AuthInterceptorTest {

    @Test
    void requestWithoutSessionIsRejectedWith401Json() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat-sessions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":401"));
    }

    @Test
    void sessionWithLoginFlagPasses() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat-sessions");
        request.getSession(true).setAttribute(AuthInterceptor.SESSION_USER, "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void corsPreflightIsAlwaysAllowed() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/chat-sessions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }
}
