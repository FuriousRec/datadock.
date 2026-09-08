package io.github.furiousrec.datadock.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final byte[] expectedKey;

    public ApiKeyFilter(@Value("${app.api-key}") String key) {
        if (key.length() < 32) throw new IllegalArgumentException("APP_API_KEY must be at least 32 characters.");
        expectedKey = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String suppliedKey = request.getHeader("X-API-Key");
        if (suppliedKey == null || !MessageDigest.isEqual(expectedKey,
                suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/problem+json");
            response.getWriter().write(
                    "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"A valid X-API-Key is required.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
