# OverMind 배포 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** M0를 사용자의 기존 Oracle Cloud 인스턴스에서 실제로 돌게 만든다 — MCP 클라이언트가 인가 서버를 발견할 수 있고, 이미지가 CI에서 나오고, 백업과 검증 절차가 있는 상태로.

**Architecture:** 앱과 PostgreSQL을 단일 docker compose로 호스트에 올리고, 기존 Caddy가 `overmind.<도메인>`을 `127.0.0.1:8080`으로 리버스 프록시한다. 인가 서버는 Auth0(관리형)이고 OverMind는 순수 리소스 서버로 남는다. 이미지는 GitHub Actions가 만들어 GHCR에 올리고 박스는 pull만 한다.

**Tech Stack:** Java 21 · Spring Boot 4.1.1 · Spring Security 7.1.1 · Docker Compose · pgvector/pgvector:pg16 · Caddy · GHCR · Auth0

**Spec:** `docs/superpowers/specs/2026-09-04-overmind-deploy-design.md`

## Global Constraints

스펙 §3의 C-1~C-9이 모든 태스크에 적용된다. 매번 다시 쓰지 않으니 여기서 한 번 읽는다.

- **C-1** 애플리케이션 포트를 외부에 직접 노출하지 않는다. compose 포트 게시는 반드시 `127.0.0.1:` 접두사를 붙인다.
- **C-2** PostgreSQL은 포트를 게시하지 않는다.
- **C-3** 인가 서버를 만들지 않는다. Auth0를 가리키기만 한다.
- **C-4** 단일 애플리케이션 + PostgreSQL. 쿠버네티스·마이크로서비스 없음.
- **C-5** 전송 중 TLS, 암호화된 백업, 배포 계층이 시크릿 관리.
- **C-6** 로그와 오류 응답에 content·source id·idempotency key·project key·토큰·claim 값·원시 커서·스택 트레이스가 없다.
- **C-7** scope는 `memory:read`, `memory:write` 둘뿐이다. `memory:delete`는 M6.
- **C-8** `cursor-secret`을 로그·티켓·PR 본문 어디에도 붙여 넣지 않는다.
- **C-9** 고엔트로피 문자열을 커밋하지 않는다. `.gitleaks.toml` allowlist로 게이트를 무력화하지 않는다.

**추가로 이 저장소의 절대 규칙 (`AGENTS.md`):**

- 감시 경로를 건드리는 브랜치는 `log.md`를 반드시 갱신한다. 이 플랜의 모든 태스크가 감시 경로를 건드린다.
- 커밋 전에 `./gradlew verify`와 `./gradlew guardrails`가 **둘 다** 통과해야 한다.
- `master`에 `git push --force`, `git reset --hard`, 마이그레이션 밖의 `DROP`/`TRUNCATE`, 프로덕션 DB 직접 접속은 금지.

**환경 주의 (`log.md`에 기록된 함정):**

- Maven Central이 HTTP 429를 낸 적이 두 번 있다. baseline이 빨개지면 **코드를 의심하기 전에 429를 확인한다.** 지수 백오프로 재시도.
- 원격 컨테이너 환경에는 Docker가 없을 수 있다 — 그러면 L2(`@Tag("integration")`)를 못 돌린다. **그 경우 "통과했다"고 말하지 않고 CI 판정을 기다린다.**

## File Structure

| 파일 | 책임 | 태스크 |
|---|---|---|
| `src/main/java/com/overmind/config/SecurityConfig.java` | 보안 체인. 디스커버리 엔드포인트 활성과 permitAll 매처 | 1, 2 |
| `src/main/java/com/overmind/adapter/in/mcp/McpHttpErrors.java` | HTTP 경계 오류. `WWW-Authenticate`에 `resource_metadata` 포함 | 2 |
| `src/main/java/com/overmind/config/ResourceIdentity.java` | 신규. 요청에서 공개 URL을 만드는 단일 지점 | 2, 3 |
| `src/main/resources/application.yml` | forwarded 헤더 전략 | 3 |
| `src/test/java/com/overmind/config/ProtectedResourceMetadataTest.java` | 신규 L1. 디스커버리 체인 전체 | 1, 2, 3 |
| `src/test/java/com/overmind/guardrail/LogUpdatedGuardTest.java` | 감시 경로의 진실의 원천 | 4 |
| `AGENTS.md`, `docs/harness/40-guardrails.md` | 감시 경로 사본 | 4 |
| `Dockerfile` | jar를 담는 이미지. 빌드하지 않는다 | 5 |
| `deploy/compose.yaml` | 앱 + DB 배치 | 5 |
| `deploy/initdb/01-vector.sql` | superuser로 pgvector 확장 선생성 | 5 |
| `deploy/overmind.env.example` | 환경변수 목록. 값은 전부 빈칸 | 5 |
| `deploy/Caddyfile.example` | 리버스 프록시 블록 | 5 |
| `.github/workflows/ci.yml` | `publish` 잡 추가 | 6 |
| `deploy/backup/overmind-backup.sh` | pg_dump + gpg | 7 |
| `deploy/backup/overmind-backup.service` / `.timer` | systemd 유닛 | 7 |
| `docs/harness/70-m0-smoke.md` | 배포 검증 11항목 | 8 |
| `docs/arch/decisions.md` | D-H~D-M 등재 | 9 |

## 태스크 순서의 이유

1~3은 코드다. **2는 1에 의존한다** — `resource_metadata` URL을 만들려면 1에서 확인한 엔드포인트 경로가 필요하다. **3은 1·2 둘 다에 의존한다** — forwarded 헤더가 `resource`와 `resource_metadata` **둘 다**를 바꾸기 때문이다.

**4를 5보다 먼저 한다.** 감시 경로를 먼저 넓혀야 `deploy/`의 첫 커밋부터 `log.md` 가드가 덮는다. 순서를 뒤집으면 배포 자산을 통째로 만드는 커밋이 로그에 흔적 없이 지나간다 — 그게 이 가드가 존재하는 이유다.

6~9는 서로 독립이다.

---

### Task 1: Protected resource metadata 엔드포인트

**Files:**
- Modify: `src/main/java/com/overmind/config/SecurityConfig.java` (`securityFilterChain`)
- Create: `src/test/java/com/overmind/config/ProtectedResourceMetadataTest.java`

**Interfaces:**
- Consumes: `RequiredSettings`(issuer/audience/allowedSubject/cursorSecret), `SecurityConfig.securityFilterChain(HttpSecurity, JwtDecoder, JsonMapper)`
- Produces: `GET /.well-known/oauth-protected-resource/mcp` → JSON. 이후 태스크가 이 경로 문자열을 쓴다.

**배경 — 프레임워크가 이미 갖고 있는 것.** `spring-security-oauth2-resource-server-7.1.1`의 `OAuth2ProtectedResourceMetadataFilter`를 바이트코드로 확인한 결과:

- 매처: `PathPatternRequestMatcher.matcher(GET, "/.well-known/oauth-protected-resource" + "/**")`
- 설치 위치: `addFilterBefore(filter, AbstractPreAuthenticatedProcessingFilter.class)` — **`AuthorizationFilter`보다 앞이다**
- 기본 클레임: `resource` = 요청 URL에서 `/.well-known/oauth-protected-resource`를 제거하고 query·fragment를 뗀 것, `bearer_methods_supported` = `["header"]`, **`tls_client_certificate_bound_access_tokens` = `true`**
- `authorization_servers`와 `scopes_supported`는 **기본값이 없다.** 커스터마이저가 넣어야 한다

**결과를 모르는 것이 하나 있다.** 스펙 §7.2는 `anyRequest().denyAll()`이 이 엔드포인트를 삼킨다(G-1)고 단정했는데, 필터가 `AuthorizationFilter`보다 앞에 있으므로 **닿지 않을 가능성이 크다.** Step 2가 이걸 실측한다. 어느 쪽이 나오든 그 결과가 Step 3의 내용을 정한다 — 추측으로 `permitAll`을 먼저 넣지 않는다.

**`tls_client_certificate_bound_access_tokens: true`는 반드시 꺼야 한다.** OverMind는 mTLS를 쓰지 않는다. 켜둔 채로 두면 하지 않는 보안 속성을 광고하는 것이고, 그것을 신뢰하는 클라이언트를 오도한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/overmind/config/ProtectedResourceMetadataTest.java`:

```java
package com.overmind.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.overmind.support.SignedJwtFixture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/** L1. RFC 9728 메타데이터가 인증 없이 열리고 MCP 클라이언트가 필요한 값을 담는다. */
class ProtectedResourceMetadataTest {

    private static final String METADATA = "/.well-known/oauth-protected-resource/mcp";
    private static final String RPC = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(SecurityConfig.class, Fixture.class)
            .withPropertyValues(
                    "overmind.security.issuer=" + SignedJwtFixture.ISSUER,
                    "overmind.security.audience=overmind",
                    "overmind.security.allowed-subject=" + SignedJwtFixture.SUBJECT,
                    "overmind.security.cursor-secret=" + "test-cursor-key-".repeat(3));

    @Test
    void the_metadata_document_is_served_without_a_token() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

            var response = mvc.perform(get(METADATA).servletPath(METADATA)).andReturn().getResponse();

            assertThat(response.getStatus())
                    .as("MCP 클라이언트는 토큰 없이 이 문서를 읽어 인가 서버를 찾는다")
                    .isEqualTo(200);
            assertThat(response.getContentAsString())
                    .contains("\"authorization_servers\"")
                    .contains(SignedJwtFixture.ISSUER)
                    .contains("memory:read")
                    .contains("memory:write");
        });
    }

    @Test
    void the_metadata_does_not_advertise_security_properties_we_do_not_have() {
        runner.run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            var body = mvc.perform(get(METADATA).servletPath(METADATA))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("mTLS를 쓰지 않는데 tls_client_certificate_bound_access_tokens=true를 "
                            + "광고하면 그것을 신뢰하는 클라이언트를 오도한다. 프레임워크 기본값이 true다")
                    .doesNotContain("\"tls_client_certificate_bound_access_tokens\":true");
            assertThat(body)
                    .as("M0에 delete scope는 없다 (C-7). M6까지 광고하지 않는다")
                    .doesNotContain("memory:delete");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class Fixture {
        @Bean @Primary JwtDecoder fixtureDecoder() throws Exception {
            return SignedJwtFixture.decoder(SignedJwtFixture.SETTINGS);
        }
        @Bean(name = "mcpServerJsonMapper") JsonMapper mapper() { return JsonMapper.builder().build(); }
    }
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인하고, denyAll이 실제로 무엇을 하는지 관찰한다**

```bash
./gradlew test --tests 'com.overmind.config.ProtectedResourceMetadataTest' -i
```

기대: 두 테스트 모두 FAIL. **실패 상태 코드를 기록한다:**

- **403이면** `anyRequest().denyAll()`이 실제로 막고 있다 → 스펙 §7.2의 G-1이 맞다 → Step 3에서 `permitAll` 매처가 필요하다.
- **404면** 필터가 아직 설치되지 않은 것뿐이다 → **G-1은 틀렸다** → Step 3에서 `permitAll`이 필요 없을 수 있고, Step 4가 그것을 확인한다.

**이 관찰을 커밋 메시지에 남긴다.** 스펙이 단정한 것이 틀렸다면 그 사실 자체가 결과다.

- [ ] **Step 3: 디스커버리를 활성화한다**

`SecurityConfig.securityFilterChain`을 고친다. `oauth2ResourceServer` 블록 안에 `protectedResourceMetadata`를 추가한다. `settings`가 필요하므로 메서드 시그니처에 `RequiredSettings`를 받는다:

```java
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtDecoder decoder, RequiredSettings settings,
            @Qualifier("mcpServerJsonMapper") JsonMapper mapper) throws Exception {
        DefaultBearerTokenResolver tokens = new DefaultBearerTokenResolver();
        tokens.setAllowUriQueryParameter(false);
        tokens.setAllowFormEncodedBodyParameter(false);
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/mcp").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> McpHttpErrors.unauthenticated(response))
                        .accessDeniedHandler((request, response, failure) -> McpHttpErrors.forbidden(response)))
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(tokens)
                        .jwt(jwt -> jwt.decoder(decoder))
                        // RFC 9728. MCP 클라이언트는 이 문서로 인가 서버를 찾는다.
                        // authorization_servers와 scopes_supported에는 프레임워크 기본값이 없다.
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(document -> document
                                        .authorizationServer(settings.issuer())
                                        .scope("memory:read")
                                        .scope("memory:write")
                                        .resourceName("OverMind")
                                        // 기본값이 true다. mTLS를 쓰지 않으므로 끈다 —
                                        // 하지 않는 보안 속성을 광고하면 안 된다.
                                        .tlsClientCertificateBoundAccessTokens(false)))
                        .authenticationEntryPoint((request, response, failure) -> McpHttpErrors.unauthenticated(response))
                        .accessDeniedHandler((request, response, failure) -> McpHttpErrors.forbidden(response)))
                .addFilterAfter(new McpScopeFilter(mapper), AuthorizationFilter.class)
                .build();
    }
```

**Step 2가 403이었다면** `authorizeHttpRequests` 블록도 함께 고친다:

```java
                .authorizeHttpRequests(requests -> requests
                        // 필터가 응답을 직접 쓰지만, 인가 규칙이 먼저 걸리면 도달하지 못한다.
                        .requestMatchers(HttpMethod.GET, "/.well-known/oauth-protected-resource/**").permitAll()
                        .requestMatchers("/mcp").authenticated()
                        .anyRequest().denyAll())
```

`org.springframework.http.HttpMethod` import를 추가한다.

**Step 2가 404였다면 이 블록을 건드리지 않는다.** 필요 없는 `permitAll`은 노출면만 넓힌다.

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

```bash
./gradlew test --tests 'com.overmind.config.ProtectedResourceMetadataTest' -i
```

기대: 두 테스트 모두 PASS. 실패하면 응답 본문을 출력해 어떤 클레임이 빠졌는지 본다.

- [ ] **Step 5: 썩힘 확인 — 커스터마이저를 지우고 테스트가 실패하는지 본다**

`.authorizationServer(settings.issuer())` 줄을 임시로 지우고 다시 돌린다. `the_metadata_document_is_served_without_a_token`이 **반드시 실패해야 한다.** 실패하지 않으면 그 단언은 아무것도 검사하지 않는 것이다. 확인 후 되돌린다.

같은 방식으로 `.tlsClientCertificateBoundAccessTokens(false)`를 지워 두 번째 테스트가 실패하는 것도 확인한다.

- [ ] **Step 6: 전체 게이트를 돌린다**

```bash
./gradlew verify
./gradlew guardrails
```

Docker가 없는 환경이면 L2가 안 돈다. **그 경우 "통과했다"고 쓰지 않고 무엇이 돌지 않았는지 명시한다.**

- [ ] **Step 7: log.md를 갱신하고 커밋한다**

`log.md` HEAD의 "진행 중"에 Step 2의 관찰 결과를 적는다 — 403이었는지 404였는지, 그래서 `permitAll`을 넣었는지.

```bash
git add src/main/java/com/overmind/config/SecurityConfig.java \
        src/test/java/com/overmind/config/ProtectedResourceMetadataTest.java log.md
git commit -m "feat: RFC 9728 protected resource metadata를 연다

MCP 클라이언트는 이 문서로 인가 서버를 찾는다. Spring Security 7.1.1이
필터를 이미 갖고 있어 엔드포인트를 만들 필요는 없었고, authorization_servers와
scopes_supported만 커스터마이저로 채웠다.

프레임워크 기본값 tls_client_certificate_bound_access_tokens=true를 껐다.
mTLS를 쓰지 않으므로 켜둔 채로는 거짓 메타데이터를 광고하게 된다."
```

---

### Task 2: `WWW-Authenticate`에 `resource_metadata`를 싣는다

**Files:**
- Create: `src/main/java/com/overmind/config/ResourceIdentity.java`
- Modify: `src/main/java/com/overmind/adapter/in/mcp/McpHttpErrors.java`
- Modify: `src/main/java/com/overmind/config/SecurityConfig.java` (엔트리포인트에 request 전달)
- Modify: `src/test/java/com/overmind/config/ProtectedResourceMetadataTest.java`
- Modify: `src/test/java/com/overmind/config/McpAuthorizationTest.java:71-74`

**Interfaces:**
- Consumes: Task 1의 경로 `/.well-known/oauth-protected-resource` + 요청 경로
- Produces: `ResourceIdentity.metadataUrl(HttpServletRequest)` → `String`. Task 3이 같은 클래스를 고친다.
- Produces: `McpHttpErrors.unauthenticated(HttpServletRequest, HttpServletResponse)` — **시그니처가 바뀐다.** 기존 1-인자 버전을 호출하는 곳이 전부 바뀐다.

**왜 필요한가.** MCP 클라이언트는 401 응답의 `WWW-Authenticate` 헤더에 담긴 `resource_metadata` 파라미터를 보고 Task 1의 문서를 찾아간다. 지금 `McpHttpErrors.unauthenticated`는 헤더를 `"Bearer"`로 통째로 덮어써서 그 파라미터가 없다. **이것이 디스커023버리 체인의 실질적 차단 지점이다.**

**C-6을 지킨다.** 헤더에 `error`, `error_description`, 실패 사유를 넣지 않는다. 메타데이터 URL만 추가한다 — 그 URL은 이미 공개 정보다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ProtectedResourceMetadataTest`에 추가한다:

```java
    @Test
    void an_unauthenticated_call_points_the_client_at_the_metadata_document() {
        runner.run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

            var response = mvc.perform(post("/mcp").servletPath("/mcp")
                    .contentType(MediaType.APPLICATION_JSON).content(RPC))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader("WWW-Authenticate"))
                    .as("이 파라미터가 없으면 Claude 웹은 어디서 로그인해야 하는지 알 수 없다")
                    .contains("resource_metadata=")
                    .contains("/.well-known/oauth-protected-resource/mcp");
        });
    }

    @Test
    void the_challenge_header_leaks_nothing_about_why_authentication_failed() {
        runner.run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

            var response = mvc.perform(post("/mcp").servletPath("/mcp")
                    .header("Authorization", "Bearer not-a-real-token")
                    .contentType(MediaType.APPLICATION_JSON).content(RPC))
                    .andReturn().getResponse();

            assertThat(response.getHeader("WWW-Authenticate"))
                    .as("C-6 — 실패 사유도 토큰도 헤더에 싣지 않는다")
                    .doesNotContain("error", "invalid_token", "not-a-real-token", "Jwt", "Exception");
        });
    }
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
./gradlew test --tests 'com.overmind.config.ProtectedResourceMetadataTest' -i
```

기대: `an_unauthenticated_call_points_the_client_at_the_metadata_document`가 FAIL — 헤더가 `"Bearer"`뿐이라 `resource_metadata=`를 못 찾는다. 두 번째 테스트는 이미 PASS일 것이다 (지금 헤더가 `"Bearer"`뿐이므로). **두 번째는 회귀 방지용이다** — 첫 번째를 고치면서 사유를 흘리지 않게 잡아준다.

- [ ] **Step 3: `ResourceIdentity`를 만든다**

`src/main/java/com/overmind/config/ResourceIdentity.java`:

```java
package com.overmind.config;

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
```

- [ ] **Step 4: `McpHttpErrors`가 헤더에 URL을 싣게 한다**

`src/main/java/com/overmind/adapter/in/mcp/McpHttpErrors.java`의 `unauthenticated`를 바꾼다. 나머지 메서드는 그대로 둔다:

```java
package com.overmind.adapter.in.mcp;

import com.overmind.config.ResourceIdentity;
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
```

- [ ] **Step 5: 호출부를 고친다**

`SecurityConfig`에서 `unauthenticated`를 부르는 곳이 두 군데다. 둘 다 `request`를 넘긴다:

```java
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> McpHttpErrors.unauthenticated(request, response))
                        .accessDeniedHandler((request, response, failure) -> McpHttpErrors.forbidden(response)))
```

`oauth2ResourceServer` 블록 안의 `.authenticationEntryPoint(...)`도 같은 형태로 바꾼다.

컴파일 오류가 남으면 다른 호출부가 있는 것이다:

```bash
grep -rn "McpHttpErrors.unauthenticated" src/
```

- [ ] **Step 6: 테스트를 돌려 통과를 확인한다**

```bash
./gradlew test --tests 'com.overmind.config.ProtectedResourceMetadataTest' -i
```

기대: 네 테스트 모두 PASS.

- [ ] **Step 7: 기존 L2 단언을 강화한다**

`src/test/java/com/overmind/config/McpAuthorizationTest.java`의 `an_unauthenticated_call_is_rejected`가 지금 `"Bearer"`만 확인한다. 그 단언은 `"Bearer"`만 있어도 통과하므로 이번 변경을 검증하지 못한다:

```java
    @Test
    void an_unauthenticated_call_is_rejected() throws Exception {
        HttpResponse<String> result = post("/mcp", null, null, recall());
        assertError(result, 401, "UNAUTHENTICATED");
        assertThat(result.headers().firstValue("WWW-Authenticate").orElseThrow())
                .startsWith("Bearer ")
                .contains("resource_metadata=\"")
                .contains("/.well-known/oauth-protected-resource/mcp");
    }
```

- [ ] **Step 8: 썩힘 확인**

`McpHttpErrors.unauthenticated`의 `setHeader` 줄을 `response.setHeader("WWW-Authenticate", "Bearer");`로 되돌리고 돌린다. **L1과 L2 양쪽이 실패해야 한다.** 한쪽만 실패하면 나머지 한쪽은 이 변경을 검증하지 않는 것이다. 확인 후 되돌린다.

- [ ] **Step 9: 전체 게이트와 커밋**

```bash
./gradlew verify && ./gradlew guardrails
```

L2가 도는 환경이면 `McpAuthorizationTest`까지 확인한다. 안 도는 환경이면 그 사실을 log.md에 쓴다.

```bash
git add src/main/java/com/overmind/config/ResourceIdentity.java \
        src/main/java/com/overmind/adapter/in/mcp/McpHttpErrors.java \
        src/main/java/com/overmind/config/SecurityConfig.java \
        src/test/java/com/overmind/config/ProtectedResourceMetadataTest.java \
        src/test/java/com/overmind/config/McpAuthorizationTest.java log.md
git commit -m "feat: 401 응답이 클라이언트에게 메타데이터 문서 위치를 알려준다

McpHttpErrors가 WWW-Authenticate를 'Bearer'로 덮어써서 프레임워크가 붙였을
resource_metadata 파라미터가 사라지고 있었다. 이것이 디스커버리 체인의 실질적
차단 지점이었다.

ResourceIdentity가 URL 생성을 한 곳에 모은다 — 프레임워크 필터가 resource
클레임을 만드는 규칙과 같은 규칙을 반대로 적용한다. 둘이 어긋나면 클라이언트가
따라간 문서에서 다른 resource를 보게 된다.

헤더에는 URL만 싣는다. error도 error_description도 넣지 않는다 (C-6).
기존 L2 단언이 'Bearer' 존재만 확인해 이 변경을 검증하지 못했으므로 강화했다."
```

---

### Task 3: 리버스 프록시 뒤에서 공개 URL을 올바르게 만든다

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/overmind/config/ProtectedResourceMetadataTest.java`

**Interfaces:**
- Consumes: Task 1의 `resource` 클레임, Task 2의 `ResourceIdentity.metadataUrl`
- Produces: 없음 (설정 변경)

**왜 필요한가 — 브레인스토밍에서 나온 새 결함.** 프레임워크의 `resolveResourceIdentifier`와 Task 2의 `ResourceIdentity` 둘 다 `UrlUtils.buildFullRequestUrl(request)`을 쓴다. Caddy 뒤에서 앱은 `http://127.0.0.1:8080`으로 오는 요청을 본다. 그대로 두면:

```json
{ "resource": "http://127.0.0.1:8080/mcp" }
```

를 광고하고, 401 헤더도 `resource_metadata="http://127.0.0.1:8080/..."`가 된다. **디스커버리가 통째로 깨진다.** 게다가 `http`라서 클라이언트가 평문 URL을 따라가려 한다.

**아무 헤더나 믿으면 안 된다.** `X-Forwarded-Host`를 무조건 신뢰하면 공격자가 임의 호스트를 주입해 메타데이터를 오염시킬 수 있다. `70-m0-smoke.md`가 이미 "forwarded 헤더는 지정한 프록시에서만 신뢰한다"고 못 박고 있다.

Tomcat의 `RemoteIpValve`가 `internalProxies` 정규식에 맞는 원격 주소에서 온 요청의 forwarded 헤더만 반영한다. 앱은 `127.0.0.1:8080`에만 바인딩되고 Caddy도 루프백에서 붙으므로, 루프백만 신뢰하면 충분하다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ProtectedResourceMetadataTest`에 추가한다. `MockMvc`는 서블릿 컨테이너를 안 거치므로 `RemoteIpValve`가 없다 — 그래서 이 테스트는 **`ResourceIdentity`가 요청이 말하는 대로 URL을 만든다**는 성질만 고정한다. 밸브가 실제로 헤더를 반영하는지는 배포 검증(스펙 §12-5)에서 확인한다:

```java
    @Test
    void the_public_url_follows_what_the_container_reports_not_a_hardcoded_host() {
        runner.run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

            var response = mvc.perform(post("/mcp").servletPath("/mcp")
                    // scheme을 명시적으로 세운다. secure(true)는 request.isSecure()만 바꾸고
                    // getScheme()은 "http"로 남는데, UrlUtils.buildFullRequestUrl은 scheme을 본다.
                    .with(request -> {
                        request.setScheme("https");
                        request.setSecure(true);
                        request.setServerName("overmind.example.test");
                        request.setServerPort(443);
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON).content(RPC))
                    .andReturn().getResponse();

            assertThat(response.getHeader("WWW-Authenticate"))
                    .as("프록시가 반영된 scheme/host를 그대로 따라야 한다. "
                            + "루프백 주소를 광고하면 클라이언트가 서버를 찾지 못한다")
                    .contains("https://overmind.example.test/.well-known/oauth-protected-resource/mcp")
                    .doesNotContain("127.0.0.1", "http://");
        });
    }
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
./gradlew test --tests 'com.overmind.config.ProtectedResourceMetadataTest' -i
```

기대: FAIL. 실패 메시지에 실제로 어떤 URL이 나왔는지 적혀 있다. **PASS가 나오면 `ResourceIdentity`가 이미 올바른 것이므로, 이 테스트는 회귀 방지로 남기고 Step 3만 진행한다.**

- [ ] **Step 3: forwarded 헤더 전략을 켠다**

`src/main/resources/application.yml`의 `spring:` 블록과 같은 레벨에 `server:`를 추가한다. `logging:` 바로 앞에 넣는다:

```yaml
server:
  # 리버스 프록시 뒤에서 공개 URL(scheme·host·port)을 올바르게 만든다.
  # 이 값이 없으면 RFC 9728 메타데이터가 resource: "http://127.0.0.1:8080/mcp"를
  # 광고하고 MCP 디스커버리가 통째로 깨진다.
  #
  # NATIVE는 Tomcat RemoteIpValve를 쓴다. FRAMEWORK(ForwardedHeaderFilter)와 달리
  # 신뢰할 원격 주소를 정규식으로 제한할 수 있다 — 아무 클라이언트나 보낸
  # X-Forwarded-Host를 반영하면 메타데이터를 오염시킬 수 있다.
  forward-headers-strategy: native
  tomcat:
    remoteip:
      # 앱은 127.0.0.1에만 바인딩되고 Caddy도 루프백에서 붙는다.
      # 그 밖의 출처가 보낸 forwarded 헤더는 무시된다.
      internal-proxies: "127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1"
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

```bash
./gradlew test --tests 'com.overmind.config.ProtectedResourceMetadataTest' -i
```

기대: 다섯 테스트 모두 PASS.

- [ ] **Step 5: 썩힘 확인 — 신뢰 경계가 실제로 좁은지 본다**

`internal-proxies` 값을 `.*`(전부 신뢰)로 바꾸고 `./gradlew verify`를 돌린다. **테스트는 통과할 것이다** — MockMvc가 밸브를 거치지 않기 때문이다.

**이것이 이 설정의 한계이고, 숨기지 않는다.** 자동 테스트로는 신뢰 경계를 확인할 수 없다. 그래서 스펙 §12에 배포 시 실측 항목으로 들어가 있고, Task 8이 그것을 `70-m0-smoke.md`에 넣는다. 값을 원래대로 되돌리고, **"L1이 이 설정을 검증하지 못한다"는 사실을 `log.md`의 이월 결함에 등재한다.**

- [ ] **Step 6: 전체 게이트와 커밋**

```bash
./gradlew verify && ./gradlew guardrails
```

```bash
git add src/main/resources/application.yml \
        src/test/java/com/overmind/config/ProtectedResourceMetadataTest.java log.md
git commit -m "fix: 리버스 프록시 뒤에서 공개 URL을 올바르게 만든다

메타데이터의 resource 클레임과 401 헤더의 resource_metadata가 둘 다
요청 URL에서 나온다. Caddy 뒤에서 앱은 http://127.0.0.1:8080을 보므로
그대로 두면 루프백 주소를 광고하고 디스커버리가 깨진다.

NATIVE(RemoteIpValve)를 쓰고 internal-proxies를 루프백으로 제한한다.
FRAMEWORK과 달리 신뢰할 원격 주소를 정규식으로 좁힐 수 있다 — 아무나 보낸
X-Forwarded-Host를 반영하면 메타데이터를 오염시킬 수 있다.

신뢰 경계 자체는 MockMvc가 밸브를 거치지 않아 L1으로 검증되지 않는다.
이월 결함에 등재하고 배포 스모크 항목으로 넘긴다."
```

---

### Task 4: 감시 경로에 `deploy/`와 `Dockerfile`을 넣는다

**Files:**
- Modify: `src/test/java/com/overmind/guardrail/LogUpdatedGuardTest.java:96-106`
- Modify: `AGENTS.md:23-27`
- Modify: `docs/harness/40-guardrails.md:16-24`

**Interfaces:**
- Consumes: 없음
- Produces: `deploy/` 접두사와 `Dockerfile` 파일이 `log.md` 가드의 감시 대상이 된다. Task 5~7이 그 경로에 파일을 만든다.

**왜 5보다 먼저인가.** 감시 경로를 먼저 넓혀야 `deploy/`의 **첫 커밋부터** 가드가 덮는다. 순서를 뒤집으면 배포 자산을 통째로 만드는 커밋이 `log.md`에 흔적 없이 지나간다 — 그게 이 가드가 존재하는 이유다.

**목록이 세 곳에 있다.** `LogUpdatedGuardTest`의 상수가 진실의 원천이고, `AGENTS.md` 절대 규칙 3과 `40-guardrails.md`의 표는 사본이다. `WatchedPathSyncGuardTest`가 마커 블록 안의 백틱 토큰을 모아 기계 대조하므로 **한 곳만 고치면 CI가 막는다.** 세 곳을 같은 커밋에서 고친다.

- [ ] **Step 1: 진실의 원천을 고친다**

`src/test/java/com/overmind/guardrail/LogUpdatedGuardTest.java`:

```java
    static final List<String> WATCHED_PREFIXES =
            List.of(
                    "src/",
                    ".github/",
                    "docs/harness/",
                    "docs/superpowers/",
                    "docs/arch/",
                    "docs/requirements/",
                    "deploy/");

    static final List<String> WATCHED_FILES =
            List.of("build.gradle.kts", "settings.gradle.kts", "AGENTS.md", "CLAUDE.md", "Dockerfile");
```

(기존 리스트에 `"deploy/"`와 `"Dockerfile"`을 추가한다. 나머지 원소와 순서는 건드리지 않는다.)

- [ ] **Step 2: 사본을 고치지 않은 채로 돌려 대조 검사가 잡는지 확인한다**

```bash
./gradlew guardrailTest -PbaseRef=origin/master
```

기대: **FAIL.** `WatchedPathSyncGuardTest`의 `agents_md_copy_matches_the_guard`와 `guardrails_doc_copy_matches_the_guard`가 둘 다 실패해야 한다.

**이 실패를 반드시 눈으로 본다.** 이 대조 검사는 세 목록이 갈라지는 것을 막으려고 만든 것인데, 실제로 갈라졌을 때 잡는 걸 확인하지 않으면 그것이 동작한다는 증거가 없다.

- [ ] **Step 3: `AGENTS.md`의 사본을 고친다**

`AGENTS.md` 23~27줄, 마커 블록 안:

```
   <!-- watched-paths:begin — 가드 코드가 진실이다. WatchedPathSyncGuardTest가 대조한다 -->
   제품 코드(`src/`), 게이트 기계(`build.gradle.kts`, `settings.gradle.kts`,
   `.github/`, `docs/harness/`), 배포 자산(`deploy/`, `Dockerfile`),
   설계·결정 문서(`docs/superpowers/`, `docs/arch/`, `docs/requirements/`,
   `AGENTS.md`, `CLAUDE.md`)
   <!-- watched-paths:end -->
```

**백틱 토큰만 센다.** 산문은 자유롭게 써도 되지만 백틱 안의 문자열은 코드와 정확히 같아야 한다 (뒤의 glob `*`는 떼고 비교한다).

`AGENTS.md`는 40줄 제한이 아니라 120줄 제한이다. 현재 64줄이므로 여유가 있다.

- [ ] **Step 4: `40-guardrails.md`의 표를 고친다**

`docs/harness/40-guardrails.md` 16~24줄:

```markdown
<!-- watched-paths:begin — 가드 코드가 진실이다. WatchedPathSyncGuardTest가 대조한다 -->

| 부류 | 경로 |
|---|---|
| 제품 코드 | `src/**` |
| 게이트 기계 | `build.gradle.kts`, `settings.gradle.kts`, `.github/**`, `docs/harness/**` |
| 배포 자산 | `deploy/**`, `Dockerfile` |
| 설계와 결정 | `docs/superpowers/**`, `docs/arch/**`, `docs/requirements/**`, `AGENTS.md`, `CLAUDE.md` |

<!-- watched-paths:end -->
```

바로 아래 산문에 배포 자산을 넣은 이유를 한 문장 추가한다:

```markdown
배포 자산을 넣은 이유는 설계 문서와 같다. compose 파일의 포트 바인딩 하나, CI의
이미지 태그 하나가 운영 보안을 바꾸는데, git diff는 무엇이 바뀌었는지만 보여주고
왜 바꿨는지는 보여주지 않는다.
```

- [ ] **Step 5: 다시 돌려 통과를 확인한다**

```bash
./gradlew guardrailTest -PbaseRef=origin/master
```

기대: PASS, 11건 실행. `[floor] guardrailTest — 테스트 11건 실행 확인`이 나와야 한다.

- [ ] **Step 6: 커밋**

```bash
git add src/test/java/com/overmind/guardrail/LogUpdatedGuardTest.java \
        AGENTS.md docs/harness/40-guardrails.md log.md
git commit -m "feat: deploy/와 Dockerfile을 감시 경로에 넣는다

배포 자산이 새 경로에 들어가는데 감시하지 않으면 compose 포트 바인딩이나
CI 이미지 태그를 바꿔도 log.md 갱신이 강제되지 않는다.

세 목록을 같은 커밋에서 고쳤다. 사본을 고치기 전에 한 번 돌려
WatchedPathSyncGuardTest가 실제로 불일치를 잡는 것을 확인했다."
```

---

### Task 5: 배포 자산

**Files:**
- Create: `Dockerfile`
- Create: `deploy/compose.yaml`
- Create: `deploy/initdb/01-vector.sql`
- Create: `deploy/overmind.env.example`
- Create: `deploy/Caddyfile.example`
- Create: `deploy/README.md`

**Interfaces:**
- Consumes: Task 4의 감시 경로
- Produces: `ghcr.io/junyupk/overmind:${OVERMIND_TAG}`를 소비하는 compose. Task 6이 그 이미지를 만든다.

**미확정 값이 있다.** `mem_limit`을 정하려면 `nproc`과 `free -m`이 필요하다 (스펙 §부록 B). **값을 아직 모르면 `mem_limit` 줄을 주석으로 두고 `deploy/README.md`에 "이 값을 채우기 전에는 운영에 쓰지 않는다"고 쓴다.** 추측한 숫자를 넣지 않는다 — JVM 힙과 짝이라 틀리면 OOM killer가 PG를 죽인다.

- [ ] **Step 1: Dockerfile**

레포 루트에 `Dockerfile`:

```dockerfile
# 이 이미지는 빌드하지 않는다. CI가 만든 jar를 담기만 한다 (D-H).
#
# 이유 셋:
#  1. 이 프로젝트에는 의존성 잠금이 없다 (gradle.lockfile도 verification-metadata도
#     dependencyLocking도 없다). 박스에서 재빌드하면 CI가 verify로 검증한 바이트와
#     다른 전이 의존성이 섞일 수 있다.
#  2. 박스가 앱과 PG를 같이 돌리기에도 빠듯하다. 거기에 Gradle 데몬을 얹지 않는다.
#  3. Maven Central이 HTTP 429를 낸 적이 두 번 있다. 업스트림 레이트 리밋으로
#     배포가 실패할 수 있는 경로를 만들지 않는다.
#
# RUN이 하나도 없다. 실행되는 명령이 없어서 buildx가 QEMU 없이 멀티아치를 만든다.
FROM eclipse-temurin:21-jre

# useradd 대신 숫자 UID. /etc/passwd 항목이 없어도 JVM은 동작하고,
# RUN이 없어야 크로스 아키텍처 빌드에 에뮬레이션이 필요 없다.
COPY --chown=10001:10001 build/libs/overmind-*.jar /app/app.jar
USER 10001:10001

EXPOSE 8080

# MaxRAMPercentage는 compose의 mem_limit과 짝이다. 한도가 없으면 JVM이 호스트
# 메모리 기준으로 힙을 잡고, 메모리 압박 때 OOM killer가 앱보다 PG를 먼저 죽인다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
```

- [ ] **Step 2: pgvector 확장 선생성 스크립트**

`deploy/initdb/01-vector.sql`:

```sql
-- pgvector는 trusted 확장이 아니다. vector.control에 trusted = true가 없어서
-- CREATE EXTENSION vector는 superuser만 실행할 수 있다.
--
-- 그런데 V1__enable_pgvector.sql은 Flyway가 앱 계정으로 실행한다. 앱 계정을
-- superuser로 만드는 대신, 컨테이너 초기화 시점에 postgres superuser로 미리
-- 만들어 둔다. 그러면 Flyway의 CREATE EXTENSION IF NOT EXISTS는 이미 존재하는
-- 확장을 보고 NOTICE만 내고 통과한다 -- PostgreSQL이 존재 검사를 권한 검사보다
-- 먼저 하기 때문이다.
--
-- L2 테스트는 이 문제를 구조적으로 잡을 수 없다. PostgreSQLContainer의 기본
-- 계정이 컨테이너 안에서 superuser라 항상 통과한다. 스펙 §12-3이 배포 시
-- initdb 없이 한 번 띄워 Flyway가 실패하는 것을 실측한다.
CREATE EXTENSION IF NOT EXISTS vector;
```

- [ ] **Step 3: compose 파일**

`deploy/compose.yaml`:

```yaml
services:
  db:
    image: pgvector/pgvector:pg16   # L2의 PostgresTestBase와 같은 태그 — 버전 parity
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - overmind-pgdata:/var/lib/postgresql/data
      - ./initdb:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
      interval: 5s
      timeout: 5s
      retries: 12
    networks: [overmind-net]
    # mem_limit: <nproc/free -m 확인 후 채운다. README 참조>

  app:
    image: ghcr.io/junyupk/overmind:${OVERMIND_TAG}
    restart: unless-stopped
    depends_on:
      db:
        condition: service_healthy   # Flyway가 연결 가능한 DB를 만나야 한다
    # 127.0.0.1: 접두사는 필수다 (C-1). 없으면 Docker가 iptables DOCKER 체인에
    # 규칙을 직접 넣어 firewalld를 우회하고 0.0.0.0:8080이 인터넷에 열린다.
    ports: ["127.0.0.1:8080:8080"]
    env_file: /etc/overmind/overmind.env
    environment:
      SPRING_PROFILES_ACTIVE: production
    networks: [overmind-net]
    # mem_limit: <위와 같음>

volumes:
  overmind-pgdata:
    # external이면 docker compose down -v로도 삭제되지 않는다.
    # 최초 1회: docker volume create overmind-pgdata
    external: true

networks:
  overmind-net:
```

**db에는 `ports`가 없다.** 의도적이다 (C-2). 호스트에서 접근할 일이 있으면 `docker compose exec db psql`을 쓴다.

- [ ] **Step 4: 환경변수 예시 — 값은 전부 빈칸**

`deploy/overmind.env.example`:

```
# 이 파일을 /etc/overmind/overmind.env 로 복사하고 값을 채운다.
#   sudo install -d -m 0700 -o root -g root /etc/overmind
#   sudo install -m 0600 -o root -g root deploy/overmind.env.example /etc/overmind/overmind.env
#
# 값을 이 파일에 채워서 커밋하지 않는다. 고엔트로피 문자열은 gitleaks가 잡는다.

# --- 데이터베이스 (compose의 db 서비스와 같은 값을 써야 한다) ---
POSTGRES_DB=
POSTGRES_USER=
POSTGRES_PASSWORD=
OVERMIND_DB_URL=
OVERMIND_DB_USER=
OVERMIND_DB_PASSWORD=

# --- Auth0 ---
# issuer: Auth0 Domain. HTTPS 절대 URI, 끝에 / 포함.
# audience: Auth0 API의 Identifier. 테넌트 Default Audience와 같아야 한다.
#   Claude는 OAuth 요청에 resource만 보내고 audience를 보내지 않는다.
#   Default Audience가 없으면 Auth0가 opaque 토큰을 발급하고 JWT 파싱이 실패한다.
# allowed-subject: 허용할 Auth0 user_id 정확히 하나.
OVERMIND_OIDC_ISSUER=
OVERMIND_OIDC_AUDIENCE=
OVERMIND_ALLOWED_SUBJECT=

# --- 커서 서명 키 ---
# UTF-8 32바이트 이상. 아래 명령으로 만든다 -- 값이 셸 히스토리에 남지 않는다.
#   printf 'OVERMIND_CURSOR_SECRET=%s\n' "$(openssl rand -hex 32)" \
#     | sudo tee -a /etc/overmind/overmind.env >/dev/null
#
# 이 값을 로그·티켓·PR 본문 어디에도 붙여 넣지 않는다.
# 바꾸면 발급된 모든 커서가 INVALID_CURSOR가 되고 클라이언트는 페이지네이션을
# 처음부터 시작한다. 데이터 손실은 없다.
OVERMIND_CURSOR_SECRET=
```

**모든 값이 `=` 뒤에 비어 있어야 한다.** 샘플이라도 고엔트로피 문자열을 넣으면 gitleaks `generic-api-key`에 걸린다 (C-9). PR #5에서 두 번 막혔고, gitleaks가 워킹 트리가 아니라 **커밋 범위**를 스캔하기 때문에 파일 수정만으로는 통과하지 못하고 히스토리에서 제거해야 했다.

- [ ] **Step 5: Caddy 블록 예시**

`deploy/Caddyfile.example`:

```
# 기존 Caddyfile의 flight-friend 블록을 이것으로 교체한다.
# TLS는 Caddy가 자동으로 발급·갱신한다 (ACME HTTP-01, 80번 포트 필요).
#
# 응답이 text/event-stream이면 Caddy가 버퍼링을 자동으로 끄므로 MCP Streamable
# HTTP의 장수명 스트림에 추가 설정이 필요 없다.
#
# 사전 조건: overmind.<도메인>의 A 레코드가 이 인스턴스의 공인 IP를 가리킬 것.

overmind.<도메인> {
    reverse_proxy 127.0.0.1:8080
}
```

- [ ] **Step 6: 배포 README**

`deploy/README.md`에 최초 1회 절차와 반복 배포 절차를 쓴다:

````markdown
# 배포

설계 근거는 `docs/superpowers/specs/2026-09-04-overmind-deploy-design.md`에 있다.
여기에는 손 순서만 있다.

## 먼저 채워야 할 값

`mem_limit`이 `compose.yaml`에 주석으로 남아 있다. **채우기 전에는 운영에 쓰지 않는다.**
JVM의 `-XX:MaxRAMPercentage=60`과 짝이라, 한도가 없으면 메모리 압박 때 OOM killer가
앱보다 PostgreSQL을 먼저 죽인다.

인스턴스에서 확인한다:

```bash
nproc
free -m
docker compose version    # v2 플러그인인지 확인
df -h && docker system df # 디스크 여유
```

## 최초 1회

```bash
sudo mkdir -p /opt/overmind
sudo cp deploy/compose.yaml /opt/overmind/
sudo cp -r deploy/initdb /opt/overmind/
sudo docker volume create overmind-pgdata      # external 볼륨. compose가 만들지 않는다

sudo install -d -m 0700 -o root -g root /etc/overmind
sudo install -m 0600 -o root -g root deploy/overmind.env.example /etc/overmind/overmind.env
sudo "${EDITOR:-vi}" /etc/overmind/overmind.env
printf 'OVERMIND_CURSOR_SECRET=%s\n' "$(openssl rand -hex 32)" \
  | sudo tee -a /etc/overmind/overmind.env >/dev/null

echo "OVERMIND_TAG=<master의 커밋 sha>" | sudo tee /opt/overmind/.env
```

`.env`와 `env_file:`은 다른 기구다. `/opt/overmind/.env`는 compose 파일 안의
`${...}` **치환**에 쓰이고, `env_file:`은 **컨테이너 안으로 주입**된다.
`OVERMIND_TAG`를 `env_file` 쪽에 두면 이미지 태그가 치환되지 않아 pull이 실패한다.

## 배포와 롤백

```bash
cd /opt/overmind
sudo sed -i "s/^OVERMIND_TAG=.*/OVERMIND_TAG=<새 sha>/" .env
sudo docker compose pull && sudo docker compose up -d
```

롤백은 sha를 되돌리고 같은 두 줄이다. `latest`로 배포하지 않는다 — 무엇이 돌고
있는지 알 수 없고 롤백 대상도 사라진다.

## 절대 하지 않는 것

- `docker compose down -v` — 볼륨이 external이라 삭제되지 않지만, 습관으로 만들지 않는다
- `ports: "8080:8080"` — 접두사를 빼면 Docker가 firewalld를 우회해 인터넷에 연다
````

- [ ] **Step 7: 게이트를 돌린다**

```bash
./gradlew guardrails
```

**gitleaks가 `deploy/`를 스캔한다.** `overmind.env.example`에 값이 남아 있으면 여기서 걸린다. 걸리면 `.gitleaks.toml` allowlist로 우회하지 말고 값을 지운다 (C-9).

로컬에 gitleaks가 없으면 `gitleaksScan`이 건너뛴다. **그 경우 "통과했다"고 쓰지 않는다** — CI가 판정한다.

- [ ] **Step 8: 커밋**

```bash
git add Dockerfile deploy/ log.md
git commit -m "feat: 배포 자산 — Dockerfile, compose, initdb, Caddy 예시

Dockerfile에 RUN이 하나도 없다. 숫자 UID를 직접 써서 useradd를 없앴고,
그 결과 buildx가 QEMU 없이 멀티아치 이미지를 만든다 (박스가 aarch64다).

initdb/01-vector.sql이 pgvector 확장을 postgres superuser로 미리 만든다.
pgvector는 trusted 확장이 아니라 CREATE EXTENSION이 superuser를 요구하는데
Flyway V1은 앱 계정으로 돈다. L2는 컨테이너 기본 계정이 superuser라
이 문제를 구조적으로 잡을 수 없다.

볼륨은 external이다 — docker compose down -v로도 삭제되지 않는다.
env.example의 값은 전부 비어 있다 (C-9)."
```

---

### Task 6: CI가 이미지를 만들어 GHCR에 올린다

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: Task 5의 `Dockerfile`
- Produces: `ghcr.io/junyupk/overmind:<sha>`와 `:latest`. Task 5의 compose가 소비한다.

**핵심은 `needs`다.** `verify`와 `guardrails`가 통과한 커밋만 이미지가 된다. 이게 없으면 "게이트를 통과한 바이트를 배포한다"는 D-H의 전제가 무너진다.

**멀티아치가 필수다.** `ubuntu-latest` 러너는 amd64다. jar는 아키텍처 중립이지만 `eclipse-temurin:21-jre` 베이스 레이어는 아니다. 그냥 빌드하면 amd64 이미지가 나오고 ARM 박스에서 실행되지 않는다.

- [ ] **Step 1: `publish` 잡을 추가한다**

`.github/workflows/ci.yml`의 `guardrails` 잡 뒤, `evaluation` 잡 앞에 넣는다:

```yaml
  publish:
    name: publish (GHCR 이미지)
    # 게이트를 통과한 커밋만 이미지가 된다. 이 needs가 D-H의 전제 전부다.
    needs: [verify, guardrails]
    if: github.event_name == 'push' && github.ref == 'refs/heads/master'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v4

      - run: ./gradlew bootJar

      - uses: docker/setup-buildx-action@v3

      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          # 레포가 public이라 박스는 익명으로 pull한다. PAT를 심을 필요가 없다.
          password: ${{ secrets.GITHUB_TOKEN }}

      - uses: docker/build-push-action@v6
        with:
          context: .
          # 박스가 aarch64다. Dockerfile에 RUN이 없어서 에뮬레이션 없이 만들어진다.
          platforms: linux/amd64,linux/arm64
          push: true
          tags: |
            ghcr.io/junyupk/overmind:${{ github.sha }}
            ghcr.io/junyupk/overmind:latest
```

- [ ] **Step 2: 워크플로 문법을 검사한다**

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('YAML OK')"
```

`if:` 조건이 다른 잡들과 충돌하지 않는지 확인한다. 기존 `verify`와 `guardrails`는 `if: github.event_name != 'schedule'`이라 push에서 돈다 — `needs`가 만족된다.

- [ ] **Step 3: `bootJar`가 실제로 그 경로에 파일을 만드는지 확인한다**

Dockerfile이 `build/libs/overmind-*.jar`를 COPY한다. 실제 파일명을 확인한다:

```bash
./gradlew bootJar && ls -la build/libs/
```

기대: `overmind-0.0.1-SNAPSHOT.jar`. **`overmind-0.0.1-SNAPSHOT-plain.jar`도 같이 나오면 glob이 두 파일을 잡아 COPY가 실패한다.** 그 경우 Dockerfile의 COPY를 정확한 이름으로 바꾸거나, `build.gradle.kts`에서 `tasks.jar { enabled = false }`로 plain jar를 끈다. 어느 쪽을 택했는지 커밋 메시지에 남긴다.

- [ ] **Step 4: 로컬에서 이미지가 빌드되는지 확인한다 (Docker가 있는 경우만)**

```bash
docker build -t overmind:local .
docker run --rm overmind:local java -version
```

기대: Java 21이 출력된다. **Docker가 없는 환경이면 이 단계를 건너뛰고 "확인하지 못했다"고 기록한다.** CI의 첫 `publish` 실행이 진짜 검증이다.

- [ ] **Step 5: 게이트와 커밋**

```bash
./gradlew verify && ./gradlew guardrails
```

`.github/`가 감시 경로이므로 `log.md`를 갱신해야 통과한다.

```bash
git add .github/workflows/ci.yml log.md
git commit -m "ci: verify와 guardrails를 통과한 커밋만 GHCR 이미지가 된다

needs: [verify, guardrails]가 이 잡의 전부다. 게이트가 빨간 커밋은 이미지가
아예 만들어지지 않으므로, 박스가 pull하는 바이트는 CI가 검증한 바이트다.

platforms에 linux/arm64를 넣는다. 러너는 amd64고 jar는 아키텍처 중립이지만
eclipse-temurin 베이스 레이어는 아니라서, 그냥 빌드하면 ARM 박스에서 안 돈다.

레포가 public이라 pull이 익명으로 되고 박스에 PAT를 심지 않는다."
```

- [ ] **Step 6: 병합 후 첫 실행을 확인한다**

이 커밋이 `master`에 병합되면 `publish`가 처음 돈다. **Actions 로그에서 두 아키텍처가 다 올라갔는지 확인한다:**

```bash
docker manifest inspect ghcr.io/junyupk/overmind:latest
```

기대: `linux/amd64`와 `linux/arm64` 둘 다. 하나만 있으면 `platforms` 설정이 안 먹은 것이다.

---

### Task 7: 백업

**Files:**
- Create: `deploy/backup/overmind-backup.sh`
- Create: `deploy/backup/overmind-backup.service`
- Create: `deploy/backup/overmind-backup.timer`
- Modify: `deploy/README.md`

**Interfaces:**
- Consumes: Task 5의 `deploy/compose.yaml` (`db` 서비스명, 볼륨)
- Produces: `/var/backups/overmind/overmind-<UTC타임스탬프>.dump.gpg`

**C-5가 암호화된 백업을 요구한다.** 그리고 **한 번도 복원해보지 않은 백업은 백업이 아니다** — 복원 드릴이 Task 8의 검증 항목에 들어간다.

**로컬만으로는 백업이 아니다.** 인스턴스가 죽으면 백업도 같이 죽는다. 스크립트는 로컬에 쓰고, OCI 오브젝트 스토리지 업로드는 별도 단계로 둔다 (OCI CLI 설정이 이 플랜의 범위 밖이라 README에 절차만 남긴다).

- [ ] **Step 1: 백업 스크립트**

`deploy/backup/overmind-backup.sh`:

```bash
#!/usr/bin/env bash
# OverMind DB 백업. systemd timer가 하루 한 번 부른다.
#
# 앱은 상태가 없다 -- 이미지는 GHCR에, 설정은 /etc/overmind에 있다.
# /etc/overmind/overmind.env의 사본을 박스 밖에 따로 둬야 한다. 그것은 백업이
# 아니라 복구 전제조건이다: cursor-secret을 잃으면 DB를 복원해도 기존 커서를
# 쓸 수 없다.
set -euo pipefail

COMPOSE_FILE=${COMPOSE_FILE:-/opt/overmind/compose.yaml}
BACKUP_DIR=${BACKUP_DIR:-/var/backups/overmind}
PASSPHRASE_FILE=${PASSPHRASE_FILE:-/etc/overmind/backup.pass}
KEEP_DAILY=${KEEP_DAILY:-7}

# env_file에서 DB 자격을 읽는다. 값을 echo하지 않는다 (C-8).
set -a
# shellcheck disable=SC1091
source /etc/overmind/overmind.env
set +a

install -d -m 0700 "$BACKUP_DIR"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$BACKUP_DIR/overmind-$stamp.dump.gpg"

# -Fc: 압축 내장 + pg_restore로 선택적 복원 가능.
# 파이프 실패를 놓치지 않으려고 set -o pipefail을 켜 뒀다.
docker compose -f "$COMPOSE_FILE" exec -T db \
    pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB" \
  | gpg --batch --yes --symmetric --cipher-algo AES256 \
        --passphrase-file "$PASSPHRASE_FILE" \
  > "$target"

chmod 0600 "$target"

# 빈 파일이 조용히 쌓이는 것을 막는다. 실패한 덤프도 파이프를 타고 0바이트로 남는다.
if [ ! -s "$target" ]; then
    echo "backup produced an empty file: $target" >&2
    rm -f "$target"
    exit 1
fi

# 보존: 일간 KEEP_DAILY개. 그 밖은 삭제.
find "$BACKUP_DIR" -name 'overmind-*.dump.gpg' -type f -printf '%T@ %p\n' \
  | sort -rn | tail -n "+$((KEEP_DAILY + 1))" | cut -d' ' -f2- \
  | xargs -r rm -f

echo "backup ok: $target ($(stat -c%s "$target") bytes)"
```

**빈 파일 검사가 중요하다.** `pg_dump`가 실패해도 `gpg`는 빈 입력을 성공적으로 암호화한다. 검사가 없으면 "백업이 돌고 있다"고 믿으면서 0바이트 파일만 쌓인다 — 이 저장소가 계속 잡아온 "통과하는데 아무것도 하지 않는 게이트"와 같은 형태다.

- [ ] **Step 2: systemd 유닛**

`deploy/backup/overmind-backup.service`:

```ini
[Unit]
Description=OverMind PostgreSQL 백업
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
ExecStart=/opt/overmind/backup/overmind-backup.sh
# 실패를 journalctl -u overmind-backup 으로 본다.
StandardOutput=journal
StandardError=journal
```

`deploy/backup/overmind-backup.timer`:

```ini
[Unit]
Description=OverMind 백업을 하루 한 번 돌린다

[Timer]
OnCalendar=daily
# 박스가 꺼져 있던 동안 놓친 실행을 따라잡는다. cron에는 없는 성질이다.
Persistent=true
RandomizedDelaySec=30m

[Install]
WantedBy=timers.target
```

- [ ] **Step 3: 스크립트 문법을 검사한다**

```bash
bash -n deploy/backup/overmind-backup.sh && echo "문법 OK"
command -v shellcheck >/dev/null && shellcheck deploy/backup/overmind-backup.sh || echo "shellcheck 없음 — 건너뜀"
chmod +x deploy/backup/overmind-backup.sh
```

- [ ] **Step 4: 빈 파일 검사가 실제로 동작하는지 확인한다**

이건 박스 없이도 확인할 수 있다. 임시 디렉터리에서 `docker`를 실패하는 가짜로 바꿔 돌린다:

```bash
tmp=$(mktemp -d)
cat > "$tmp/docker" <<'FAKE'
#!/bin/sh
exit 1
FAKE
chmod +x "$tmp/docker"
echo "test-passphrase" > "$tmp/pass"
printf 'POSTGRES_USER=u\nPOSTGRES_DB=d\n' > "$tmp/env"

# 스크립트가 /etc/overmind/overmind.env를 하드코딩하므로, 이 검사는
# 그 줄을 임시로 "$tmp/env"로 바꿔서 돌린다. 확인 후 되돌린다.
```

기대: `set -o pipefail`과 빈 파일 검사 중 하나가 걸려 **exit code가 0이 아니어야 한다.** 0이면 실패한 백업이 성공으로 보고되는 것이다.

**확인 후 스크립트를 원상 복구한다.**

- [ ] **Step 5: README에 설치·복원 절차를 추가한다**

`deploy/README.md`에 섹션을 추가한다:

````markdown
## 백업

### 설치

```bash
sudo cp -r deploy/backup /opt/overmind/
sudo chmod +x /opt/overmind/backup/overmind-backup.sh

# gpg 패스프레이즈. 값이 셸 히스토리에 남지 않는다.
printf '%s\n' "$(openssl rand -base64 32)" | sudo tee /etc/overmind/backup.pass >/dev/null
sudo chmod 0600 /etc/overmind/backup.pass

sudo cp /opt/overmind/backup/overmind-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now overmind-backup.timer
sudo systemctl start overmind-backup.service    # 한 번 즉시 돌려본다
journalctl -u overmind-backup -n 30
```

**`/etc/overmind/backup.pass`의 사본을 박스 밖에 둔다.** 이걸 잃으면 백업을 복호화할 수 없다.

### 박스 밖으로 내보내기

로컬만으로는 백업이 아니다 — 인스턴스가 죽으면 백업도 같이 죽는다.
OCI Always Free에 20 GB 오브젝트 스토리지가 포함된다. 버킷은 private으로 만들고
(서버측 암호화는 기본), `oci os object put`으로 올린다. gpg는 그 위의 이중 방어라
OCI 콘솔 접근권만으로는 내용을 볼 수 없다.

M0 데이터는 1인 관찰 이벤트 로그라 덤프가 한동안 KB~MB 단위다. 용량은 제약이 아니다.

### 복원 드릴 — 이걸 해야 백업이다

**운영 DB에 복원하지 않는다.** 별도 컨테이너에 복원하고 행 수를 대조한다.

```bash
docker run -d --name overmind-restore-drill \
  -e POSTGRES_PASSWORD=drill -e POSTGRES_DB=overmind -e POSTGRES_USER=drill \
  pgvector/pgvector:pg16
sleep 10

gpg --batch --decrypt --passphrase-file /etc/overmind/backup.pass \
    /var/backups/overmind/<최신>.dump.gpg \
  | docker exec -i overmind-restore-drill pg_restore -U drill -d overmind --no-owner

# 원본과 대조
docker compose -f /opt/overmind/compose.yaml exec -T db \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc 'SELECT count(*) FROM observation'
docker exec -i overmind-restore-drill \
  psql -U drill -d overmind -tAc 'SELECT count(*) FROM observation'

docker rm -f overmind-restore-drill
```

두 숫자가 같아야 한다. 다르면 백업이 불완전한 것이고, **절차를 고치지 말고 원인을 찾는다.**
````

- [ ] **Step 6: 게이트와 커밋**

```bash
./gradlew guardrails
```

```bash
git add deploy/backup deploy/README.md log.md
git commit -m "feat: 백업 — pg_dump + gpg + systemd timer, 복원 드릴 절차

빈 파일 검사를 넣었다. pg_dump가 실패해도 gpg는 빈 입력을 성공적으로
암호화하므로, 검사가 없으면 '백업이 돌고 있다'고 믿으면서 0바이트 파일만
쌓인다. 실제로 실패를 잡는지 가짜 docker로 확인했다.

cron이 아니라 systemd timer다. journalctl로 실패가 보이고 Persistent=true가
박스가 꺼져 있던 동안의 실행을 따라잡는다.

복원 드릴 절차를 README에 넣었다. 한 번도 복원해보지 않은 백업은 백업이 아니다."
```

---

### Task 8: 배포 검증을 스모크 절차에 넣는다

**Files:**
- Modify: `docs/harness/70-m0-smoke.md`

**Interfaces:**
- Consumes: Task 1~7 전부
- Produces: 없음 (문서)

**확인일·확인자 칸을 비워둔 채로 넣는다.** 빈 칸은 "안 해봤다"는 뜻이고, 그게 이 문서의 전체 요지다.

기존 표(1~10번)는 M0 기능 확인이고 그대로 둔다. **배포 확인은 별도 표로 붙인다** — 성격이 다르고, 재배포할 때마다 다시 채워야 하는 것과 한 번만 확인하면 되는 것이 섞이면 안 된다.

- [ ] **Step 1: 배포 검증 절 추가**

`docs/harness/70-m0-smoke.md`의 `## 실패했을 때` 절 **앞에** 추가한다:

```markdown
## 배포 검증 — 깨뜨려서 확인한다

설계 근거는 `docs/superpowers/specs/2026-09-04-overmind-deploy-design.md` §12에 있다.

**"확인했다"가 아니라 "막히는 것을 봤다"를 채운다.** 방어가 통과하는 것만 보면
그 방어가 실제로 무언가를 막는지 알 수 없다. 아래 항목 중 여럿이 일부러
깨뜨려 보라고 요구하는 이유다.

| # | 확인할 것 | 어떻게 | 확인일 | 확인자 |
|---|---|---|---|---|
| D1 | 앱 포트가 외부에 안 보인다 | 외부 호스트에서 `curl http://<공인IP>:8080/mcp` → 거부. **그다음 compose의 `127.0.0.1:` 접두사를 빼고 재기동해 외부에서 응답이 오는 것을 확인한 뒤 되돌린다** | | |
| D2 | DB가 외부에 안 보인다 | 외부 호스트에서 `nc -vz <공인IP> 5432` → 거부 | | |
| D3 | pgvector superuser 순서 | `initdb/01-vector.sql` **없이** 먼저 띄워 Flyway V1이 권한 오류로 실패하는 것을 확인 → 스크립트를 넣고 성공 확인 | | |
| D4 | 어느 게이트가 기동을 막는가 | issuer를 비우고 기동 → 실패 확인. 그다음 `SPRING_PROFILES_ACTIVE=production`을 빼고 반복 → **동일하게 실패해야 결정 D-M이 맞다.** 통과하면 `RequiredSettings.Validation`이 진짜 게이트다 | | |
| D5 | 디스커버리 체인 | `curl -i -X POST https://overmind.<도메인>/mcp` → 401 + `WWW-Authenticate`에 `resource_metadata=`. 그 URL을 `curl` → `authorization_servers`에 Auth0 issuer | | |
| D6 | 공개 URL이 루프백이 아니다 | D5의 응답에서 `resource`와 `resource_metadata`가 **`https://overmind.<도메인>`으로 시작한다.** `127.0.0.1`이나 `http://`가 보이면 forwarded 헤더 설정이 안 먹은 것이다 | | |
| D7 | forwarded 헤더 신뢰 경계 | 박스 밖에서 `curl -H 'X-Forwarded-Host: evil.example' https://overmind.<도메인>/.well-known/oauth-protected-resource/mcp` → 응답의 `resource`에 `evil.example`이 **없어야 한다** | | |
| D8 | Auth0가 JWT를 준다 (opaque가 아니라) | 토큰이 `.`으로 세 조각인지 확인하고 payload를 디코드해 `aud`/`sub`/`scope`를 본다. **세 조각이 아니면 테넌트 Default Audience가 안 걸린 것이다** | | |
| D9 | sub allowlist가 막는다 | Auth0에 두 번째 사용자를 만들어 토큰을 받고 `/mcp` 호출 → 401. 막지 못하면 allowlist는 장식이다 | | |
| D10 | 백업 복원 드릴 | 덤프를 **별도 컨테이너**에 복원하고 `observation` 행 수를 원본과 대조 (`deploy/README.md` 참조). 운영 DB에 복원하지 않는다 | | |
| D11 | 재부팅 생존 | `sudo reboot` 후 사람 개입 없이 Caddy·docker·compose가 모두 복귀하는지 | | |
| D12 | 이미지가 게이트를 통과한 것인가 | 돌고 있는 태그의 sha로 GitHub Actions를 찾아 `verify`·`guardrails`가 초록인지 확인 | | |

**D4와 D8은 결과를 모르는 검사다.** 나머지는 확인이지만 이 둘은 발견이 될 수 있다.
결과가 예상과 다르면 스펙 §13의 해당 결정을 고친다.

**D7은 자동 테스트가 대신할 수 없다.** MockMvc는 Tomcat `RemoteIpValve`를 거치지
않아서 `internal-proxies` 값을 `.*`로 바꿔도 L1이 통과한다. 신뢰 경계는 여기서만
확인된다.
```

- [ ] **Step 2: 사전 준비 절을 갱신한다**

기존 `## 사전 준비`의 "관리형 OIDC 발급자에서 다음 설정을 채운다" 뒤에 한 줄 추가한다:

```markdown
- Auth0를 쓴다면 **테넌트 Default Audience를 API Identifier와 같게 설정한다.**
  Claude는 OAuth 요청에 `resource`만 보내고 `audience`를 보내지 않아서, 이 설정이
  없으면 Auth0가 JWT 대신 opaque 토큰을 발급하고 `NimbusJwtDecoder`가 파싱조차
  하지 못한다. D8이 이것을 확인한다.
```

- [ ] **Step 3: 게이트와 커밋**

```bash
./gradlew guardrails
```

`docs/harness/`가 감시 경로이므로 `log.md` 갱신이 필요하다.

```bash
git add docs/harness/70-m0-smoke.md log.md
git commit -m "docs: 배포 검증 12항목을 스모크 절차에 넣는다

기존 표는 M0 기능 확인이고 이건 배포 확인이라 별도 표로 붙였다.
확인일·확인자 칸은 비워둔 채로 시작한다.

여러 항목이 일부러 깨뜨려 보라고 요구한다. 방어가 통과하는 것만 보면
그 방어가 실제로 무언가를 막는지 알 수 없다.

D7(forwarded 헤더 신뢰 경계)은 자동 테스트가 대신할 수 없다 -- MockMvc가
RemoteIpValve를 거치지 않아 internal-proxies를 .*로 바꿔도 L1이 통과한다."
```

---

### Task 9: 결정을 등재하고 스펙을 정정한다

**Files:**
- Modify: `docs/arch/decisions.md`
- Modify: `docs/superpowers/specs/2026-09-04-overmind-deploy-design.md` (§7.2)

**Interfaces:**
- Consumes: Task 1의 Step 2 관찰 결과
- Produces: 없음

**마지막에 하는 이유:** D-M과 G-1의 진위가 Task 1·3에서 실측되어야 정확히 쓸 수 있다.

- [ ] **Step 1: `docs/arch/decisions.md`의 "확정" 표에 D-H~D-M을 추가한다**

기존 D-G 행 아래에 붙인다. 표 형식(`| ID | 결정 | 근거 | 날짜 |`)을 그대로 따른다:

```markdown
| D-H | **이미지는 CI가 빌드하고 박스는 pull만 한다.** Dockerfile에 빌드 단계를 두지 않는다. 의존성 잠금이 없어 박스 재빌드는 CI가 검증한 바이트와 갈라질 수 있다 | 배포 스펙 §5.2 | 2026-09-04 |
| D-I | **앱과 PostgreSQL을 단일 compose에 둔다.** 볼륨은 `external: true`로 선언해 `down -v`로도 삭제되지 않게 한다 | 배포 스펙 §5.3 | 2026-09-04 |
| D-J | **인가 서버는 Auth0 무료 티어.** 실측이 깨지면 MCP 전용 벤더로 이동한다 — 환경변수 3개만 바꾸면 되므로 교체 비용이 근사 0이다 | 배포 스펙 §8.1, 사용자 승인 2026-09-04 | 2026-09-04 |
| D-K | **이미지는 커밋 sha로 고정 배포한다.** `latest`는 편의용이며 배포에 쓰지 않는다 | 배포 스펙 §10.4 | 2026-09-04 |
| D-L | **백업은 pg_dump + gpg + OCI 오브젝트 스토리지.** 복원 드릴이 검증 항목에 포함된다 | 배포 스펙 §11 | 2026-09-04 |
```

**D-M은 Task 1·3의 실측 결과에 따라 쓴다.** 배포 스모크 D4가 아직 안 돌았다면 "확정"이 아니라 **"열려 있음"** 절에 넣는다:

```markdown
- **D-M — `RequiredSettings.Validation`(`@Profile("production")`)이 중복 방어인가.**
  코드 읽기로는 `SecurityConfig.jwtDecoder`가 싱글턴이라 기동 시 `requireComplete()`가
  동기 호출되어 프로파일과 무관하게 실패해야 한다. **실행으로 확인하지 않았다.**
  배포 스모크 D4가 확인 또는 반증한다. 반증되면 `Validation`이 진짜 게이트다
```

- [ ] **Step 2: 스펙 §7.2의 G-1을 실측 결과로 정정한다**

Task 1 Step 2에서 404가 나왔다면, 스펙의 G-1 행이 틀린 것이다. `docs/superpowers/specs/2026-09-04-overmind-deploy-design.md` §7.2의 표를 고친다:

```markdown
| # | 문제 | 위치 |
|---|---|---|
| ~~G-1~~ | ~~`anyRequest().denyAll()`이 `/.well-known/**`를 삼킨다~~ — **틀렸다.** `OAuth2ProtectedResourceMetadataFilter`는 `AbstractPreAuthenticatedProcessingFilter`보다 앞에 설치되어 `AuthorizationFilter`에 도달하기 전에 응답을 쓴다. Task 1에서 실측했다 | — |
| G-2 | `McpHttpErrors.unauthenticated()`가 `WWW-Authenticate`를 `"Bearer"`로 덮어써서 `resource_metadata=` 파라미터가 사라진다 | `McpHttpErrors` |
| G-3 | `protectedResourceMetadata(...)` 미활성 | `SecurityConfig` |
| G-4 | **(브레인스토밍 후 발견)** 리버스 프록시 뒤에서 `resource`와 `resource_metadata`가 루프백 주소로 만들어진다 | `application.yml` |
```

403이 나왔다면 G-1을 그대로 두고 "Task 1에서 403으로 확인했다"를 덧붙인다.

**어느 쪽이든 G-4를 추가한다** — 이건 스펙 작성 후에 발견한 것이다.

- [ ] **Step 3: 게이트와 커밋**

```bash
./gradlew guardrails
```

```bash
git add docs/arch/decisions.md docs/superpowers/specs/2026-09-04-overmind-deploy-design.md log.md
git commit -m "docs: 배포 결정 D-H~D-L 등재, 스펙의 G-1을 실측으로 정정

D-M은 배포 스모크 D4가 돌기 전까지 '열려 있음'에 둔다. 코드 읽기에 근거한
추론이고 실행으로 확인하지 않았다.

스펙 §7.2가 denyAll이 디스커버리 엔드포인트를 삼킨다고 단정했는데,
필터가 AuthorizationFilter보다 앞에 설치되어 그렇지 않았다. Task 1에서
실측한 결과로 고친다.

리버스 프록시 뒤 공개 URL 문제(G-4)는 스펙 작성 후에 발견해 추가했다."
```

---

## 남은 손 작업 — 코드가 아닌 것

이 플랜이 끝나도 아래는 사람이 해야 한다. 태스크로 만들 수 없다.

| | 무엇 | 왜 |
|---|---|---|
| H1 | **Auth0 테넌트 설정** — API 생성(Identifier=audience), `memory:read`/`memory:write` permission 정의, DCR 활성화, **Default Audience 설정**, third-party 기본 permission 지정 | 외부 서비스 콘솔 |
| H2 | **DNS A 레코드** — `overmind.<도메인>` → 인스턴스 공인 IP | Caddy의 ACME HTTP-01이 이걸 요구한다 |
| H3 | **flight-friend 종료와 Caddy 블록 교체** | 사용자 판단 |
| H4 | **디스크 정리** — `docker system df`로 소비처 확인 후 정리. **볼륨 삭제는 flight-friend 종료 확정 뒤에** | 100 GB가 차 있다 |
| H5 | **미확정 값 확인** — `nproc`, `free -m`, `docker compose version`, 도메인 | Task 5의 `mem_limit`이 막혀 있다 |
| H6 | **원격 브랜치 3종 삭제** — `codex/m0-t2-t3`, `feat/m0`, `claude/overmind-handover-8njuet`. 원격 컨테이너에서 `git push --delete`가 403이다 | 환경 제약 |

**H5가 Task 5를 막는다.** `mem_limit` 없이 배포하면 메모리 압박 때 OOM killer가 PG를 죽인다. Task 5는 자산을 만들되 그 값을 주석으로 두고 README에 경고를 남기는 것까지가 범위다.

**H1이 Task 1~3의 실효를 막는다.** 코드가 다 맞아도 Auth0 설정이 없으면 디스커버리 체인이 5단계에서 끊긴다. 배포 스모크 D5~D9가 그걸 잡는다.
