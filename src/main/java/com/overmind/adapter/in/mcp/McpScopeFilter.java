package com.overmind.adapter.in.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks tool scopes before the SDK opens a response stream or invokes a callback. */
public final class McpScopeFilter extends OncePerRequestFilter {
    private final JsonMapper mapper;

    public McpScopeFilter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!"POST".equals(request.getMethod()) || !"/mcp".equals(request.getServletPath())) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        JsonNode rpc;
        try {
            rpc = mapper.readTree(body);
        } catch (JacksonException failure) {
            McpHttpErrors.invalidRequest(response);
            return;
        }
        if (rpc == null || !rpc.isObject()) {
            McpHttpErrors.invalidRequest(response);
            return;
        }
        if ("tools/call".equals(rpc.path("method").asString(""))) {
            String authority = switch (rpc.path("params").path("name").asString("")) {
                case "remember_memory" -> "SCOPE_memory:write";
                case "recall_memory" -> "SCOPE_memory:read";
                default -> null;
            };
            Authentication caller = SecurityContextHolder.getContext().getAuthentication();
            if (authority == null || caller == null || !caller.isAuthenticated()
                    || caller.getAuthorities().stream().noneMatch(granted -> authority.equals(granted.getAuthority()))) {
                McpHttpErrors.forbidden(response);
                return;
            }
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    /** Preserve the original bytes for the SDK; ContentCachingRequestWrapper alone cannot replay. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("MCP request bodies use blocking reads");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
