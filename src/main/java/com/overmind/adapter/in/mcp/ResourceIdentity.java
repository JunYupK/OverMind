package com.overmind.adapter.in.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 이 서버의 공개 신원을 요청에서 만든다.
 *
 * <p>Spring Security의 {@code OAuth2ProtectedResourceMetadataFilter}가 {@code resource}
 * 클레임을 만드는 방식과 **같은 규칙**을 반대 방향으로 적용한다. 둘이 어긋나면
 * 클라이언트가 401 헤더를 따라간 문서에서 다른 {@code resource}를 보게 되고,
 * RFC 8707 {@code resource} 파라미터가 audience와 맞지 않게 된다.
 *
 * <p><b>요청 URL을 그대로 쓴다.</b> 리버스 프록시 뒤에서는 프록시가 보낸 forwarded 헤더를
 * 서블릿 컨테이너가 반영해야 올바른 값이 나온다. 그 설정은 {@code application.yml}의
 * {@code server.forward-headers-strategy}이며, 신뢰 경계는 거기서 정한다.
 */
public final class ResourceIdentity {

    /** 프레임워크 필터의 매처와 같은 접두사. 둘 중 하나만 바뀌면 체인이 끊어진다. */
    static final String METADATA_PREFIX = "/.well-known/oauth-protected-resource";

    private ResourceIdentity() {}

    /** 이 요청이 향한 리소스에 대응하는 RFC 9728 메타데이터 문서의 절대 URL. */
    public static String metadataUrl(HttpServletRequest request) {
        String path = request.getRequestURI();
        return UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
                .replacePath(METADATA_PREFIX + path)
                .replaceQuery(null)
                .fragment(null)
                .build()
                .toUriString();
    }
}
