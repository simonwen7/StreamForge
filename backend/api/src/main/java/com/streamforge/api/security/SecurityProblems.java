package com.streamforge.api.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class SecurityProblems {

    private SecurityProblems() {
    }

    static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        String body = "{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\"}";
        response.getWriter().write(body);
    }
}
