package com.streamforge.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Stateless bearer authentication. The raw keys are reduced to SHA-256 digests and are not logged.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String DEVICE_ADMIN = "DEVICE_ADMIN";
    static final String INGESTION = "INGESTION";

    private final byte[] adminDigest;
    private final byte[] ingestionDigest;

    public ApiKeyAuthenticationFilter(
            @Value("${streamforge.security.device-admin-api-key}") String adminKey,
            @Value("${streamforge.security.ingestion-api-key}") String ingestionKey) {
        this.adminDigest = sha256(adminKey);
        this.ingestionDigest = sha256(ingestionKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = bearerToken(header);
        if (token == null) {
            SecurityProblems.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Authentication is required.");
            return;
        }
        byte[] presented = sha256(token);
        boolean admin = MessageDigest.isEqual(presented, adminDigest);
        boolean ingestion = MessageDigest.isEqual(presented, ingestionDigest);
        if (!admin && !ingestion) {
            SecurityProblems.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "Authentication is required.");
            return;
        }
        String authority = admin ? DEVICE_ADMIN : INGESTION;
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                authority,
                "",
                List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    /**
     * HTTP auth schemes are case-insensitive. The token itself is case-sensitive.
     * Returns null when the header is not a single bearer token.
     */
    static String bearerToken(String header) {
        int space = header.indexOf(' ');
        if (space < 1 || space != header.lastIndexOf(' ')) {
            return null;
        }
        if (!header.substring(0, space).equalsIgnoreCase("Bearer")) {
            return null;
        }
        String token = header.substring(space + 1);
        if (token.isBlank()) {
            return null;
        }
        return token;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.");
        }
    }
}
