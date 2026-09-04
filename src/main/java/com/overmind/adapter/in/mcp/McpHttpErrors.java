package com.overmind.adapter.in.mcp;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** HTTP boundary errors never serialize authentication exceptions or request data. */
public final class McpHttpErrors {
    private McpHttpErrors() {}

    public static void unauthenticated(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, 401, "UNAUTHENTICATED", "authentication required");
    }

    public static void forbidden(HttpServletResponse response) throws IOException {
        write(response, 403, "PERMISSION_DENIED", "permission denied");
    }

    static void invalidRequest(HttpServletResponse response) throws IOException {
        write(response, 400, "INVALID_ARGUMENT", "invalid request");
    }

    private static void write(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
