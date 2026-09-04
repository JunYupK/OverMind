# OverMind 배포 설계 — Oracle Cloud 단일 인스턴스

**상태:** 초안 (사용자 리뷰 대기)
**작성일:** 2026-09-04
**범위:** M0(Task 0~14)를 실사용 가능한 원격 MCP 서버로 배포하는 데 필요한 인프라·인증·파이프라인·검증

## 1. 목적과 범위

M0 코드는 `master`에 전부 들어갔지만 아직 어디에서도 돌지 않는다. `docs/harness/70-m0-smoke.md`의 수동 스모크 표는 실제 배포 없이는 채울 수 없고, M1의 golden set 50문항은 실사용 데이터를 전제로 한다. **이 문서는 그 선행 조건을 만든다.**

구현 범위:

- 기존 Oracle Cloud 인스턴스 위의 앱 + PostgreSQL 배치
- Auth0를 인가 서버로 하는 OAuth 2.1/OIDC 연결
- MCP 클라이언트가 인가 서버를 발견하게 하는 코드 변경(§7)
- 시크릿 관리, 백업, CI 이미지 파이프라인
- 검증 절차 — 각 방어가 실제로 무언가를 막는다는 증거

배제 범위는 §14.

## 2. 대상 환경

### 2.1 확정된 사실

| 항목 | 값 | 출처 |
|---|---|---|
| 인스턴스 | Oracle Cloud Ampere A1 (aarch64) | 사용자 확인 |
| OS | Oracle Linux 8 | 사용자 확인 |
| 컨테이너 런타임 | Docker 설치됨. Podman 없음 | 사용자 확인 |
| 리버스 프록시 | Caddy (호스트, 80/443 점유) | 사용자 확인 |
| TLS | Caddy 자동 발급. 별도 certbot 없음 | 사용자 확인 |
| CDN/프록시 | 없음 (Cloudflare 등 미개입) | 사용자 확인 |
| 부트 볼륨 | 100 GB, 현재 거의 사용 중 | 사용자 확인 |
| 기존 서비스 | flight-friend — **종료하고 교체한다** | 사용자 결정 |
| 레포 공개 여부 | public | GitHub API `"visibility":"public"` |

### 2.2 미확정 값

구현 시작 전에 채워야 한다. §부록 B에 목록과 확인 명령을 둔다.

- 실제 할당 OCPU/메모리 (`nproc`, `free -m`) — `mem_limit`과 JVM 힙을 정한다
- `docker compose` 버전 (v2 플러그인 / v1) — compose 파일 문법
- 도메인 — 본문에서는 `overmind.<도메인>`으로 표기한다

이 값들이 없어도 설계는 확정된다. 값은 숫자와 문자열이지 구조가 아니다.

## 3. 상위 제약

전부 기존 문서에서 온다. 이 설계가 새로 만드는 제약은 없다.

| # | 제약 | 근거 |
|---|---|---|
| C-1 | 외부에는 필요한 HTTPS 엔드포인트만 노출한다. 애플리케이션 포트를 직접 노출하지 않는다 | baseline §30, M0 spec §6 |
| C-2 | PostgreSQL + pgvector는 private only | baseline §30 |
| C-3 | 기존/관리형 인가 제공자를 쓴다. 인가 서버를 만들지 않는다 | baseline §30 |
| C-4 | 단일 애플리케이션 + PostgreSQL. 쿠버네티스·마이크로서비스 없음 | baseline §30 |
| C-5 | 전송 중 TLS, 저장 시 암호화, **암호화된 백업**, 배포 계층이 시크릿을 관리 | baseline §30 |
| C-6 | 로그에 관찰 내용·토큰·Authorization 헤더·원시 커서·스택 트레이스가 없다 | baseline §30, M0 spec §5.5/§8 |
| C-7 | scope는 `memory:read` / `memory:write` (`memory:delete`는 M6) | baseline §30, M0 spec §6 |
| C-8 | cursor-secret을 로그·티켓·PR 본문에 붙여 넣지 않는다 | `70-m0-smoke.md` |
| C-9 | 고엔트로피 문자열을 레포에 커밋하지 않는다. `.gitleaks.toml` allowlist로 게이트를 무력화하지 않는다 | `40-guardrails.md`, PR #5 사고 |

## 4. 물리 구조

```
                    인터넷
                      │ :443  (Caddy 자동 TLS, ACME HTTP-01)
                      ▼
  ┌──────────────────────────────────────────────┐
  │ Oracle Linux 8 / Ampere A1 (aarch64)         │
  │                                              │
  │  Caddy (호스트, systemd — 기존 설치 재사용)     │
  │    overmind.<도메인> → 127.0.0.1:8080         │
  │                      │                       │
  │  ┌─ docker compose ──┼────────────────────┐  │
  │  │                   ▼                    │  │
  │  │  app    127.0.0.1:8080:8080            │  │
  │  │    └── overmind-net ──┐                │  │
  │  │                       ▼                │  │
  │  │  db (pgvector:pg16)  포트 게시 없음      │  │
  │  │    volume: overmind-pgdata (external)  │  │
  │  └────────────────────────────────────────┘  │
  └──────────────────────────────────────────────┘
```

### 4.1 앱 포트는 루프백에만 바인딩한다

`ports: ["127.0.0.1:8080:8080"]` — 접두사는 선택이 아니다.

Docker는 포트를 게시할 때 iptables `DOCKER` 체인에 규칙을 직접 삽입하고, **이 규칙이 firewalld/UFW보다 앞선다.** `"8080:8080"`이라고 쓰면 방화벽에서 8080을 막아두어도 `0.0.0.0:8080`이 인터넷에 열린다. C-1이 조용히 깨지는 경로다. 루프백 바인딩은 이 문제를 원천 제거한다.

§12-1에서 실제로 깨뜨려 확인한다.

### 4.2 DB는 포트를 게시하지 않는다

compose 네트워크 안에서 `db:5432`로만 도달한다. 호스트에도 열리지 않는다 (C-2). 사람이 접근할 때는 `docker compose exec db psql`을 쓴다.

### 4.3 Caddy는 호스트에 그대로 둔다

이미 동작 중이고 인증서 상태를 보유하고 있다. 컨테이너로 옮기면 그 상태를 이전해야 한다. 변경은 flight-friend 블록을 지우고 이걸 넣는 것이 전부다:

```
overmind.<도메인> {
    reverse_proxy 127.0.0.1:8080
}
```

Caddy는 응답 `Content-Type`이 `text/event-stream`이면 버퍼링을 자동으로 끈다. MCP Streamable HTTP의 장수명 스트림에 추가 설정이 필요 없다.

## 5. 컨테이너와 이미지

### 5.1 Dockerfile — 빌드하지 않는다

```dockerfile
FROM eclipse-temurin:21-jre
COPY --chown=10001:10001 build/libs/overmind-*.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
```

**`RUN`이 하나도 없다.** `useradd` 대신 숫자 UID를 직접 쓴다 — `/etc/passwd` 항목이 없어도 JVM은 동작한다. 실행되는 명령이 없으므로 buildx가 **QEMU 에뮬레이션 없이** 멀티아치 이미지를 만든다(§10.2).

`-XX:MaxRAMPercentage=60`은 compose의 `mem_limit`과 짝이다. 힙 상한을 명시하지 않으면 메모리 압박 시 OOM killer가 앱보다 PG를 먼저 죽일 수 있다. **앱이 죽는 것보다 DB가 죽는 것이 훨씬 나쁘다.**

### 5.2 왜 multi-stage 빌드가 아닌가 (D-H)

세 가지 이유가 있다.

**(가) 게이트가 검증한 바이트와 배포하는 바이트가 갈라진다.** 이 프로젝트에는 의존성 잠금이 없다:

```
$ find . -name "gradle.lockfile" -o -name "verification-metadata.xml"   → 없음
$ grep "dependencyLocking" build.gradle.kts settings.gradle.kts         → 없음
```

BOM이 직접 의존성을 고정하지만 전이 의존성 해석은 잠기지 않는다. CI가 `verify`를 돌린 시점과 박스에서 `bootJar`를 돌린 시점 사이에 전이 의존성이 바뀌면 **`master`의 초록 체크는 배포되는 바이트에 대한 증거가 아니다.** "게이트가 통과하는데 아무것도 검사하지 않는" 문제의 배포판이다.

**(나) 자원.** Gradle 데몬 + javac + Boot 4 애노테이션 처리가 PG와 메모리를 다툰다. 인스턴스가 두 워크로드를 감당하기 어렵다는 것이 flight-friend를 교체하기로 한 이유다.

**(다) 업스트림 의존.** `log.md`에 Maven Central HTTP 429로 baseline이 빨개진 기록이 두 번 있다. 업스트림 레이트 리밋으로 배포가 실패할 수 있는 경로를 만들지 않는다.

대신 CI가 이미지를 만들어 GHCR에 올리고 박스는 pull만 한다(§10).

### 5.3 왜 DB를 같은 compose에 두는가 (D-I)

**(가) Oracle Linux 8 기본 저장소에 pgvector 패키지가 없다.** 호스트 설치는 PG 확장을 소스에서 컴파일하는 것을 뜻하고, PG 마이너 업그레이드마다 반복해야 한다. 이미지는 그 유지보수를 대신해준다.

**(나) L2 테스트와 같은 이미지다.** `PostgresTestBase`가 쓰는 것이 정확히 `pgvector/pgvector:pg16`이다. 프로덕션이 다른 PG/pgvector 버전으로 돌면 §6.3에서 발견한 것과 같은 종류의 parity 구멍이 생긴다.

**(다) 네트워크 격리가 더 단순해진다.** compose 안이면 DB가 포트를 아예 게시하지 않는다. 호스트 설치는 `127.0.0.1:5432` 바인딩 + 앱 컨테이너의 `extra_hosts`(또는 host 네트워킹)를 요구한다 — 부품이 늘고 노출면이 넓어진다.

**(라) 기동 순서.** `depends_on: {condition: service_healthy}`가 Flyway에게 연결을 받을 준비가 된 DB를 보장한다. systemd는 프로세스 기동은 알아도 PG의 준비 상태는 모른다.

**받아들이는 위험:** `docker compose down -v`가 볼륨을 지운다. 대응은 볼륨을 `external: true`로 선언하는 것이다 — external 볼륨은 `down -v`로도 삭제되지 않는다. 최초 1회 `docker volume create overmind-pgdata`만 손으로 한다. 그 뒤로 compose가 데이터를 지울 수 있는 경로는 없다.

나중에 DB를 별도 compose 프로젝트로 분리하고 싶어지면, 볼륨이 이미 external이라 **파일만 쪼개면 되고 데이터는 움직이지 않는다.**

### 5.4 compose의 형태

```yaml
services:
  db:
    image: pgvector/pgvector:pg16
    restart: unless-stopped
    environment: [POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD]
    volumes:
      - overmind-pgdata:/var/lib/postgresql/data
      - ./initdb:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
    networks: [overmind-net]
    mem_limit: <미확정 — §부록 B>

  app:
    image: ghcr.io/junyupk/overmind:${OVERMIND_TAG}
    restart: unless-stopped
    depends_on:
      db: { condition: service_healthy }
    ports: ["127.0.0.1:8080:8080"]
    env_file: /etc/overmind/overmind.env
    environment:
      SPRING_PROFILES_ACTIVE: production
    networks: [overmind-net]
    mem_limit: <미확정 — §부록 B>

volumes:
  overmind-pgdata:
    external: true

networks:
  overmind-net:
```

`image:`이지 `build:`가 아니다 — `build: .`은 박스에서 컴파일을 돌려 §5.2의 이유를 전부 무효화한다.

## 6. 데이터베이스

### 6.1 마이그레이션

Flyway가 앱 기동 시 실행한다 (`spring.flyway.enabled: true`). V1이 pgvector 확장을, V2가 `memory_subject`/`observation`을 만든다. `ddl-auto: validate`는 가드레일이 검사하므로 바꾸지 않는다.

### 6.2 계정

앱 계정 하나를 쓴다. superuser가 아니다.

### 6.3 pgvector는 superuser를 요구한다 — 실증된 제약

pgvector의 `vector.control`을 원본에서 확인했다:

```
comment = 'vector data type and ivfflat and hnsw access methods'
default_version = '0.8.6'
module_pathname = '$libdir/vector'
relocatable = true
```

**`trusted = true`가 없다.** 따라서 `CREATE EXTENSION vector`는 superuser만 실행할 수 있다. 그런데 `V1__enable_pgvector.sql`은 Flyway가 앱 계정으로 실행한다.

**L2 테스트는 이것을 구조적으로 잡을 수 없다.** `PostgreSQLContainer`의 기본 계정이 컨테이너 안에서 superuser이므로 항상 통과한다. test/prod parity 구멍이다.

**대응:** 확장을 `docker-entrypoint-initdb.d/01-vector.sql`에서 `postgres` superuser로 미리 만든다. 그러면 Flyway의 `CREATE EXTENSION IF NOT EXISTS vector`는 이미 존재하는 확장을 보고 통과한다 — PostgreSQL이 존재 검사를 권한 검사보다 먼저 하기 때문이다.

**이 순서는 가정이 아니라 검증 항목이다** (§12-3). initdb 스크립트 없이 먼저 띄워 Flyway가 실패하는 것을 본 뒤에 스크립트를 넣는다. 실패를 보지 못하면 이 대응이 필요했다는 증거가 없다.

## 7. 코드 격차 — 디스커버리

### 7.1 이미 되어 있는 것

- `McpScopeFilter`가 `remember_memory` → `SCOPE_memory:write`, `recall_memory` → `SCOPE_memory:read`를 SDK가 스트림을 열기 전에 강제한다
- `SecurityConfig.validators()`가 `iss`/`aud`/`sub`/`exp`를 검사한다
- `RequiredSettings`가 issuer HTTPS 절대 URI와 cursor-secret 최소 32 UTF-8 바이트를 검사한다

C-7이 요구하는 인가 모델은 완성되어 있다.

### 7.2 없는 것 — 세 개

Spring Security 7.1.1은 RFC 9728 Protected Resource Metadata를 **이미 내장하고 있다.** jar에서 직접 확인했다:

```
org/springframework/security/oauth2/server/resource/web/
    OAuth2ProtectedResourceMetadataFilter.class      → "%/.well-known/oauth-protected-resource"
    BearerTokenAuthenticationEntryPoint.class        → "resource_metadata", "WWW-Authenticate"
org/springframework/security/config/annotation/web/configurers/oauth2/server/resource/
    OAuth2ResourceServerConfigurer$ProtectedResourceMetadataConfigurer
    → DSL: oauth2ResourceServer(o -> o.protectedResourceMetadata(...))
```

클레임도 `resource` / `authorization_servers` / `scopes_supported` / `bearer_methods_supported`를 지원한다. **엔드포인트를 손으로 만들 필요가 없다.**

막힌 지점은 셋이다:

| # | 문제 | 위치 |
|---|---|---|
| G-1 | `anyRequest().denyAll()`이 `/.well-known/**`를 삼킨다 | `SecurityConfig.securityFilterChain` |
| G-2 | `McpHttpErrors.unauthenticated()`가 `WWW-Authenticate`를 `"Bearer"`로 덮어써서 프레임워크가 붙였을 `resource_metadata=` 파라미터가 사라진다 | `McpHttpErrors` |
| G-3 | `protectedResourceMetadata(...)` 미활성 | `SecurityConfig` |

**G-2가 실질적 차단 지점이다.** MCP 클라이언트는 401 응답의 `resource_metadata` 파라미터를 보고 인가 서버를 찾아간다. 이게 없으면 Claude 웹은 어디서 로그인해야 하는지 알 수 없다.

G-2를 고칠 때 C-6을 유지한다 — `WWW-Authenticate`에 `error_description`이나 클레임 값을 싣지 않는다. 메타데이터 URL만 추가한다.

### 7.3 검증

`CrossClientAcceptanceTest` 옆에 L2 테스트를 추가한다: 무토큰 요청이 401 + `resource_metadata` 파라미터를 반환하고, 그 URL이 인증 없이 열리며, `authorization_servers`에 설정된 issuer가 담긴다.

## 8. 인증 — Auth0 (D-J)

### 8.1 선택 근거

C-3이 관리형 제공자를 요구한다. Auth0를 고른 이유:

- 무료 티어 25k MAU — 1인 사용에 충분
- **Claude.ai 원격 MCP + Auth0 조합의 공개 구축기가 여러 건 있다.** 혼자 삽질할 영역이 가장 좁다
- DCR 지원. DCR로 등록된 앱은 third-party로 분류되어 `authorization_code` + `refresh_token`만 쓸 수 있는데, 이는 MCP가 필요로 하는 것과 정확히 일치한다
- issuer가 `/.well-known/openid-configuration`을 제공하므로 `NimbusJwtDecoder.withIssuerLocation`이 그대로 동작한다

**교체 비용이 거의 0이라는 점이 이 선택을 되돌릴 수 있게 만든다.** 환경변수 3개(issuer/audience/allowedSubject)만 바꾸면 다른 제공자로 옮긴다. 지금 완벽한 벤더를 고르는 데 시간을 쓰지 않는다.

검토했으나 택하지 않은 것:

- **Stytch / Descope / WorkOS** — MCP를 1급으로 다루므로 §8.3의 함정을 이미 풀었을 가능성이 높다. 다만 이 환경의 egress 프록시가 해당 도메인을 차단해 1차 문서를 확인하지 못했다. Auth0 실측이 깨지면 여기로 간다
- **Keycloak 자체 호스팅** — C-3의 취지에 반하고, JVM을 하나 더 얹으며(§2.1의 자원 제약), `resource` 대신 비표준 `audience` 파라미터를 써서 protocol mapper 우회가 필요하다. 인터넷에 노출된 인증 서버의 패치를 1인 프로젝트가 떠안는 것도 부담이다

### 8.2 흐름

| # | 주체 | 동작 | 상태 |
|---|---|---|---|
| 1 | 클라이언트 | 토큰 없이 `POST /mcp` | 있음 |
| 2 | OverMind | `401` + `WWW-Authenticate: Bearer resource_metadata="…"` | **G-2** |
| 3 | 클라이언트 | 그 URL `GET` | **G-1** |
| 4 | OverMind | `{resource, authorization_servers, scopes_supported}` | **G-3** |
| 5 | 클라이언트 | Auth0 `/.well-known/openid-configuration` 조회 | Auth0 제공 |
| 6 | 클라이언트 | DCR로 `client_id` 발급 | Auth0 설정 |
| 7 | 클라이언트 | authorization_code + PKCE, `resource=https://overmind.<도메인>/mcp` | 있음 |
| 8 | Auth0 | JWT 발급 — `aud`, `sub`, `scope` | **§8.3** |
| 9 | OverMind | `iss`/`aud`/`sub`/`exp` 검증 | 있음 |
| 10 | OverMind | 도구별 scope 검증 | 있음 |

### 8.3 1순위 함정 — opaque 토큰

**Claude는 `resource`(RFC 8707)를 보내지만 `audience`는 보내지 않는다.** Auth0는 `audience`를 받지 못하면 JWT가 아니라 **불투명 토큰**을 발급한다. 그러면 `NimbusJwtDecoder`가 파싱조차 못 한다.

대응: 테넌트에 **Default Audience**를 설정한다. 이 테넌트는 OverMind 전용이므로 테넌트 전체에 같은 audience가 걸리는 것이 문제가 되지 않는다.

§12-6이 이것을 실측한다.

### 8.4 반드시 일치해야 하는 값

- Auth0 API **Identifier** = 테넌트 **Default Audience** = `OVERMIND_OIDC_AUDIENCE`
- Auth0 **Domain** = `OVERMIND_OIDC_ISSUER` (HTTPS 절대 URI, 끝 `/` 포함)
- Auth0의 내 `user_id` = `OVERMIND_ALLOWED_SUBJECT`

셋 중 하나만 어긋나도 401이 나는데, 서버는 **의도적으로** 이유를 알려주지 않는다(C-6). 진단은 Auth0 대시보드 로그와 앱 로그를 대조해서 한다. §11에 절차를 둔다.

### 8.5 DCR은 시한부다

MCP 2026-07-28 스펙이 DCR을 deprecate하고 **CIMD**(Client ID Metadata Documents)로 이동했다. 하위 호환으로 계속 동작하며 제거는 빨라야 2027-07-28이다.

**지금 DCR로 가는 것은 타당하다.** 다만 이 사실을 기록해두어, 제공자를 재검토할 때 CIMD 지원을 기준에 넣는다. 서버 쪽 변경은 없다 — CIMD는 클라이언트와 인가 서버 사이의 문제다.

## 9. 시크릿

### 9.1 등급

| 변수 | 등급 | 근거 |
|---|---|---|
| `OVERMIND_CURSOR_SECRET` | 비밀 | HMAC 키. 유출 시 커서 위조 가능 |
| `OVERMIND_DB_PASSWORD` | 비밀 | — |
| `OVERMIND_ALLOWED_SUBJECT` | 준민감 | Auth0 `user_id` — 비밀은 아니나 식별자 |
| `OVERMIND_OIDC_ISSUER` / `_AUDIENCE` | 공개 | issuer는 공개 URL, audience는 protected resource metadata에 실린다 |
| `OVERMIND_DB_URL` / `_USER` | 낮음 | 사설 네트워크 내부 |

### 9.2 저장과 한계

`/etc/overmind/overmind.env`, `root:root`, `0600`. compose의 `env_file:`이 참조한다.

**한계를 명시한다: 환경변수는 강한 비밀 경계가 아니다.** `docker inspect`와 `docker compose config`가 평문으로 출력하므로 docker 그룹 구성원은 모두 볼 수 있다. Docker secrets(`/run/secrets/`)가 더 강하지만 앱이 `*_FILE` 관례를 지원하지 않아 코드 변경이 필요하다.

**docker 그룹 구성원이 1명인 동안 env 파일은 비례하는 선택이다.** 사람이 늘면 그때 올린다 — 이것은 M0 배포의 의도적 수용이지 간과가 아니다.

### 9.3 생성

```bash
sudo install -d -m 0700 -o root -g root /etc/overmind
umask 077
printf 'OVERMIND_CURSOR_SECRET=%s\n' "$(openssl rand -hex 32)" \
  | sudo tee -a /etc/overmind/overmind.env >/dev/null
```

`hex 32`는 64자 = 64 UTF-8 바이트로 `MIN_CURSOR_SECRET_BYTES = 32`를 충족한다. **값이 셸 히스토리에 리터럴로 남지 않는다** — 명령만 남는다.

### 9.4 레포에 들어가는 것

`deploy/overmind.env.example`은 **모든 값을 비워둔다:**

```
OVERMIND_CURSOR_SECRET=
```

샘플이라도 고엔트로피 문자열을 넣으면 gitleaks `generic-api-key`에 걸린다(C-9). **PR #5에서 두 번 막혔고, gitleaks가 워킹 트리가 아니라 커밋 범위를 스캔하기 때문에 파일 수정만으로는 통과하지 못하고 히스토리에서 제거해야 했다.**

### 9.5 회전

cursor-secret을 바꾸면 발급된 모든 커서가 즉시 `INVALID_CURSOR`가 되고 클라이언트는 페이지네이션을 처음부터 시작한다. 데이터 손실은 없다. 단일 사용자에게 수용 가능한 대가다. **이 성질을 문서에 남겨 나중의 오진을 막는다.**

## 10. 빌드·배포 파이프라인 (D-K)

### 10.1 CI가 이미지를 만든다

`.github/workflows/ci.yml`에 잡을 추가한다:

```yaml
publish:
  needs: [verify, guardrails]
  if: github.event_name == 'push' && github.ref == 'refs/heads/master'
  runs-on: ubuntu-latest
  permissions: { contents: read, packages: write }
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with: { distribution: temurin, java-version: '21' }
    - uses: gradle/actions/setup-gradle@v4
    - run: ./gradlew bootJar
    - uses: docker/setup-buildx-action@v3
    - uses: docker/login-action@v3
      with:
        registry: ghcr.io
        username: ${{ github.actor }}
        password: ${{ secrets.GITHUB_TOKEN }}
    - uses: docker/build-push-action@v6
      with:
        platforms: linux/amd64,linux/arm64
        push: true
        tags: |
          ghcr.io/junyupk/overmind:${{ github.sha }}
          ghcr.io/junyupk/overmind:latest
```

`needs: [verify, guardrails]`가 핵심이다 — **게이트가 빨간 커밋은 이미지가 만들어지지 않는다.**

레포가 public이므로 GHCR pull이 익명으로 가능하다. **박스에 PAT를 심지 않는다** — 관리할 시크릿이 하나 줄어든다.

### 10.2 멀티아치

GitHub의 `ubuntu-latest` 러너는 amd64다. jar는 아키텍처 중립이지만 **이미지의 베이스 레이어는 아니다** — 그냥 빌드하면 `eclipse-temurin:21-jre`의 amd64 변종이 들어가고 ARM 박스에서 실행되지 않는다.

`platforms: linux/amd64,linux/arm64`로 해결한다. §5.1의 Dockerfile에 `RUN`이 없으므로 **실행되는 명령이 없어 QEMU 에뮬레이션이 필요 없다.** 베이스 이미지의 아키텍처별 레이어를 고르고 jar를 얹는 것이 전부라 빌드 시간 차이가 사실상 없다.

### 10.3 박스 레이아웃

```
/opt/overmind/
  compose.yaml
  .env                      OVERMIND_TAG=<커밋 sha>
  initdb/01-vector.sql      CREATE EXTENSION IF NOT EXISTS vector;
/etc/overmind/
  overmind.env              앱 시크릿        0600 root:root
  backup.pass               gpg 패스프레이즈  0600 root:root
```

**`.env`와 `env_file:`은 서로 다른 기구다. 혼동이 고전적인 버그다:**

- `/opt/overmind/.env` → compose 파일 안의 `${...}` **치환**에 쓰인다. `OVERMIND_TAG`가 여기 간다
- `env_file: /etc/overmind/overmind.env` → **컨테이너 안으로 주입**된다. 앱 시크릿이 여기 간다

`OVERMIND_TAG`를 `env_file` 쪽에 두면 이미지 태그가 치환되지 않아 pull이 실패한다.

### 10.4 배포와 롤백

```bash
cd /opt/overmind
sudo sed -i "s/^OVERMIND_TAG=.*/OVERMIND_TAG=<새 sha>/" .env
sudo docker compose pull && sudo docker compose up -d
```

롤백은 sha를 되돌리고 같은 두 줄이다. **박스에 JDK도 Gradle도 소스도 없다.**

`latest` 태그는 편의용으로만 둔다. compose는 `${OVERMIND_TAG}`로 sha에 고정한다 — `latest`로 배포하면 무엇이 돌고 있는지 알 수 없고 롤백 대상도 사라진다.

### 10.5 `deploy/`를 감시 경로에 넣는다

배포 자산은 새 경로(`deploy/`)에 들어간다. `40-guardrails.md`의 감시 경로 목록에 추가하지 않으면 **배포 설정을 바꿔도 `log.md` 갱신이 강제되지 않는다.**

감시 목록은 세 곳에 중복되어 있다 — `AGENTS.md` 규칙 3, `40-guardrails.md`, `LogUpdatedGuardTest`의 상수. `WatchedPathSyncGuardTest`가 이 셋을 기계 대조하므로, 한 곳만 고치면 CI가 막는다. **세 곳을 함께 고친다.**

`Dockerfile`은 레포 루트에 놓으므로 `WATCHED_FILES`에도 추가한다 (`build.gradle.kts`, `settings.gradle.kts`와 같은 취급).

## 11. 백업 (D-L)

### 11.1 대상

DB만이다. 앱은 상태가 없다 — 이미지는 GHCR에, 설정은 `/etc/overmind`에 있다.

`/etc/overmind/overmind.env`의 사본을 박스 밖 안전한 곳에 둔다. 이것은 백업이 아니라 **복구 전제조건**이다 — cursor-secret을 잃으면 DB를 복원해도 기존 커서를 쓸 수 없다.

### 11.2 절차

```bash
docker compose -f /opt/overmind/compose.yaml exec -T db \
  pg_dump -U "$PGUSER" -Fc overmind \
  | gpg --batch --symmetric --cipher-algo AES256 \
        --passphrase-file /etc/overmind/backup.pass \
  > /var/backups/overmind/overmind-$(date -u +%Y%m%dT%H%M%SZ).dump.gpg
```

`-Fc`는 압축이 내장되고 `pg_restore`로 선택적 복원이 된다. gpg는 Oracle Linux 8에 기본 설치되어 있다.

**systemd timer로 1일 1회.** cron이 아닌 이유: `journalctl -u overmind-backup`으로 실패가 보이고, `Persistent=true`가 박스가 꺼져 있던 동안의 실행을 따라잡는다.

### 11.3 보관 위치

**로컬만으로는 백업이 아니다** — 인스턴스가 죽으면 백업도 함께 죽는다. OCI Always Free의 오브젝트 스토리지(20 GB)로 올린다. 버킷은 private, 서버측 암호화가 기본이다. gpg는 그 위의 이중 방어로, OCI 콘솔 접근권만으로는 내용을 볼 수 없게 한다(C-5).

M0 데이터는 1인 관찰 이벤트 로그라 덤프가 한동안 KB~MB 단위다. 용량은 제약이 아니다.

보존: 일간 7 + 주간 4. 그 밖은 삭제.

### 11.4 복원 드릴

**한 번도 복원해보지 않은 백업은 백업이 아니다.** §12-9(검증)에 항목으로 둔다.

## 12. 검증 — 깨뜨려서 확인한다

`70-m0-smoke.md`에 추가한다. **확인일·확인자 칸은 비워둔 채로 시작한다 — 빈 칸은 "안 했음"이다.**

| # | 무엇을 | 어떻게 깨뜨려서 확인하나 |
|---|---|---|
| 1 | 앱 포트가 외부에 안 보인다 | 외부 호스트에서 `curl http://<공인IP>:8080/mcp` → 거부. **그 뒤 `127.0.0.1:` 접두사를 빼고 재기동해 외부에서 응답이 오는 것을 확인한 뒤 되돌린다.** 이걸 봐야 루프백 바인딩이 실제로 무언가를 막는다는 증거가 생긴다 |
| 2 | DB가 외부에 안 보인다 | 외부 호스트에서 `nc -vz <공인IP> 5432` → 거부 |
| 3 | pgvector superuser 순서 | initdb 스크립트 **없이** 먼저 띄워 Flyway V1이 권한 오류로 실패하는 것을 확인 → 스크립트를 넣고 성공 확인 (§6.3) |
| 4 | 어느 게이트가 실제로 막는가 | issuer를 비우고 기동 → `jwtDecoder`가 던지는지 확인. 그 다음 `SPRING_PROFILES_ACTIVE=production`을 빼고 반복 → **동일하게 실패해야 §13 D-M의 판단이 맞다.** 통과하면 `Validation`이 진짜 게이트다 |
| 5 | 디스커버리 체인 | `curl -i -X POST https://overmind.<도메인>/mcp` → 401 + `WWW-Authenticate`에 `resource_metadata=`. 그 URL을 `curl` → `authorization_servers`에 Auth0 issuer |
| 6 | Auth0가 JWT를 준다 | 토큰이 `.`으로 세 조각인지 확인하고 payload를 디코드해 `aud`/`sub`/`scope`를 본다. **세 조각이 아니면 Default Audience가 안 걸린 것이다** (§8.3) |
| 7 | sub allowlist가 막는다 | Auth0에 두 번째 사용자를 만들어 토큰을 받고 `/mcp` 호출 → 401. 막지 못하면 allowlist는 장식이다 |
| 8 | scope가 막는다 | `memory:read`만 있는 토큰으로 `remember_memory` 호출 → 403 |
| 9 | 백업 복원 드릴 | 덤프를 **별도 컨테이너**에 복원하고 `observation` 행 수를 원본과 대조. 운영 DB에 복원하지 않는다 |
| 10 | 재부팅 생존 | `sudo reboot` 후 사람 개입 없이 Caddy·docker·compose가 모두 복귀하는지 |
| 11 | 로그 위생 | 실사용 트래픽 후 `docker compose logs`를 grep — 관찰 내용, Authorization 헤더, 원시 커서, 스택 트레이스가 없어야 한다 (C-6) |

**4번과 6번은 결과를 모르는 검사다.** 나머지는 확인이지만 이 둘은 발견이 될 수 있다.

## 13. 결정 기록

`docs/arch/decisions.md`에 추가할 항목이다.

| ID | 결정 | 근거 |
|---|---|---|
| D-H | 이미지는 CI가 빌드하고 박스는 pull만 한다. Dockerfile에 빌드 단계를 두지 않는다 | §5.2 — 의존성 잠금 부재, 자원, Maven Central 429 이력 |
| D-I | 앱과 PostgreSQL을 단일 compose에 둔다. 볼륨은 `external: true` | §5.3 — pgvector 유지보수, L2 parity, 네트워크 격리, `down -v` 방어 |
| D-J | 인가 서버는 Auth0 무료 티어. 실측이 깨지면 MCP 전용 벤더로 이동 | §8.1 — 공개 구축기 존재, 교체 비용 근사 0 |
| D-K | 이미지는 커밋 sha로 고정 배포한다. `latest`는 편의용 | §10.4 — 롤백 가능성 |
| D-L | 백업은 pg_dump + gpg + OCI 오브젝트 스토리지. 복원 드릴을 검증 항목에 포함 | §11 — C-5 |
| D-M | `RequiredSettings.Validation`(`@Profile("production")`)은 중복 방어다. 기동 차단은 `SecurityConfig.jwtDecoder`의 `requireComplete()` 호출이 담당한다 | 코드 확인. §12-4에서 실증한다 |

D-M은 **아직 실증되지 않은 판단이다.** 코드 읽기로는 `jwtDecoder`가 싱글턴 빈이라 기동 시 `requireComplete()`가 동기 호출되어 프로파일과 무관하게 실패해야 한다. §12-4가 이를 확인하거나 반증한다. 반증되면 이 행을 수정한다.

## 14. 배제 범위

- **HA·이중화** — 단일 인스턴스다 (C-4). 인스턴스 장애 시 복구는 백업 복원이다
- **모니터링·알림** — M0에서는 `journalctl`과 `docker compose logs`로 충분하다. 메트릭 수집은 M1 이후
- **`memory:delete` scope** — forget 정책은 M6에서 설계한다 (M0 spec §2.2)
- **애플리케이션 수준 필드 암호화** — baseline §30이 명시적으로 유예한다
- **IP allowlist** — baseline §30이 심층 방어일 뿐 인증이 아니라고 못 박는다. M0에서 넣지 않는다
- **CIMD 전환** — §8.5. 서버 쪽 변경이 없고 제거 시점이 2027-07-28 이후다
- **자동 배포(CD)** — 배포는 손으로 두 줄이다. 자동화는 배포가 잦아진 뒤에 고려한다

## 부록 A. 1차 근거

이 설계가 문서가 아니라 원본에서 확인한 사실들.

| 사실 | 확인 방법 | 결과 |
|---|---|---|
| pgvector arm64 존재 | Docker Hub 매니페스트 API | `linux/amd64`, `linux/arm64` 모두 존재 |
| pgvector가 trusted 확장이 아님 | `vector.control` 원본 | `trusted = true` 없음 → superuser 필요 |
| Spring Security의 RFC 9728 지원 | `spring-security-oauth2-resource-server-7.1.1.jar` / `spring-security-config` 내 클래스와 문자열 | 필터·DSL·엔트리포인트 모두 존재 |
| 의존성 잠금 부재 | `find` + `grep` | lockfile·verification-metadata·`dependencyLocking` 전부 없음 |
| 레포 public | GitHub API | `"visibility":"public"` |
| Claude Code의 헤더/OAuth 지원 | 공식 문서 | `--header`, `claude mcp login` 모두 지원 |
| Claude가 `audience`를 안 보냄 | 공개 구축기 다수 | Default Audience 미설정 시 opaque 토큰 |
| DCR deprecate | MCP 2026-07-28 스펙 | CIMD로 이동, 제거는 2027-07-28 이후 |

확인하지 못한 것: Stytch·WorkOS·Descope·Auth0의 1차 문서. 이 환경의 egress 프록시가 해당 도메인을 차단한다. §8.1의 벤더 비교는 검색 요약에 기반하며, Auth0 설정의 세부는 구현 시 1차 문서로 확인해야 한다.

## 부록 B. 미확정 값

구현 시작 전에 채운다.

| 값 | 확인 방법 | 쓰이는 곳 |
|---|---|---|
| OCPU 수 | `nproc` | JVM 힙, PG `max_connections` |
| 메모리 | `free -m` | `mem_limit`, `MaxRAMPercentage`, PG `shared_buffers` |
| compose 버전 | `docker compose version` | compose 파일 문법 |
| 디스크 여유 | `df -h`, `docker system df` | 정리 필요량 판단 |
| 도메인 | 사용자 | Caddyfile, `resource`, Auth0 콜백 |

본문의 `overmind.<도메인>`은 **placeholder이며 TBD가 아니다** — 구조는 확정되어 있고 문자열만 비어 있다.

**디스크 정리:** OverMind가 새로 쓰는 용량은 대략 `eclipse-temurin:21-jre` ~180 MB + 앱 레이어 ~80 MB + pgvector ~154 MB + 초기 데이터 수십 MB로 **1 GB 미만**이다. 100 GB가 찼다면 원인은 flight-friend의 누적 Docker 레이어·볼륨·로그다. `docker system df`로 소비처를 확인한 뒤 정리한다. **볼륨 삭제는 flight-friend 종료가 확정된 뒤에 한다.**
