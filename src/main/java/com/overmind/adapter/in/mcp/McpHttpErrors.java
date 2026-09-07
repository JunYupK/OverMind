package com.overmind.adapter.in.mcp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** HTTP boundary errors never serialize authentication exceptions or request data. */
public final class McpHttpErrors {
    private McpHttpErrors() {}

    /**
     * MCP 클라이언트는 이 헤더의 {@code resource_metadata}를 보고 인가 서버를 찾는다.
     * 그 URL 외에는 아무것도 싣지 않는다 — {@code error}나 {@code error_description}은
     * 실패 사유를 흘리므로 넣지 않는다.
     */
    public static void unauthenticated(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setHeader("WWW-Authenticate",
                "Bearer resource_metadata=\"" + ResourceIdentity.metadataUrl(request) + "\"");
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
