# OverMind

> 하나의 기억, 모든 AI 클라이언트.

OverMind는 Claude Chat, ChatGPT, Claude Code, Codex 같은 여러 AI 클라이언트가
하나의 개인 메모리를 공유하도록 만드는 MCP(Model Context Protocol) 서버입니다.
대화에서 관측한 사실을 원본 관측으로 보존하고, 이후 마일스톤에서 이를 시점별로
유효한 사실로 정규화하는 것을 목표로 합니다.

현재 `master`의 M0는 **원본 관측을 안전하게 저장하고 다시 조회하는 최소 수직
슬라이스**입니다. 정규화, 의미 검색, snapshot, forget은 아직 구현 범위가 아닙니다.

## 핵심 기능

- MCP Streamable HTTP를 통한 `remember_memory`, `recall_memory` 도구 제공
- USER 메모리와 PROJECT 메모리 저장 및 통합 조회
- 호출자가 제공한 idempotency key 기반 중복 저장 방지
- HMAC 서명 cursor를 이용한 안정적인 keyset pagination
- OAuth 2.0 bearer JWT 인증과 `memory:read` / `memory:write` scope 분리
- RFC 9728 protected resource metadata를 통한 인가 서버 발견
- PostgreSQL + Flyway + pgvector 기반 영속화
- 로그와 오류 응답에서 메모리·토큰·식별자 원문을 노출하지 않는 경계

## 동작 방식

```text
MCP client
    │ HTTPS + OAuth bearer token
    ▼
Caddy
    │ 127.0.0.1:8080
    ▼
OverMind
    │ JDBC / Flyway
    ▼
PostgreSQL + pgvector
```

OverMind는 인가 서버를 직접 구현하지 않습니다. 운영 배포에서는 Auth0를 인가
서버로 사용하며, OverMind는 토큰을 검증하는 resource server로만 동작합니다.

저장된 observation은 append-only입니다. 일반 처리 경로에는 update/delete가
없으며, 동일한 idempotency key를 같은 요청으로 재시도하면 기존 observation을
돌려줍니다. 같은 키를 다른 내용으로 재사용하면 충돌 오류를 반환합니다.

## 현재 구현 범위

### M0에서 제공하는 것

- 단일 허용 사용자와 선택적인 프로젝트별 observation
- 원문 observation 저장
- 최신순 recall과 cursor pagination
- MCP 도구 계약 및 안전한 오류 응답
- JWT issuer, audience, subject, expiry 검증
- 도구별 scope 인가
- Docker Compose, Caddy 예시, GHCR 이미지 배포 파이프라인
- 암호화된 PostgreSQL 백업과 복원 절차

### 아직 제공하지 않는 것

- LLM 기반 사실 추출 및 canonicalization
- 시점별 current fact 계산
- 의미 검색과 embedding
- snapshot 생성
- forget 및 완전 삭제
- 다중 사용자 tenancy

마일스톤과 열린 결정은 [`log.md`](log.md)와
[`docs/arch/decisions.md`](docs/arch/decisions.md)를 기준으로 확인합니다.

## 빠른 시작

### 요구 사항

- Java 21
- Docker 및 Docker Compose v2
- PostgreSQL 테스트를 실행할 수 있는 Docker daemon

저장소를 받은 뒤 전체 검증을 실행합니다.

```bash
git clone https://github.com/junyupk/overmind.git
cd overmind
./gradlew verify
./gradlew guardrails
```

`verify`는 단위 테스트와 Testcontainers 기반 통합 테스트를 실행합니다.
`guardrails`는 아키텍처·마이그레이션·문서·시크릿 관련 저장소 규칙을 검사합니다.
로컬에 `gitleaks`가 없으면 시크릿 스캔만 생략되므로 최종 판정은 CI 결과도 함께
확인해야 합니다.

애플리케이션을 직접 기동하려면 다음 설정이 모두 필요합니다.

```text
OVERMIND_DB_URL
OVERMIND_DB_USER
OVERMIND_DB_PASSWORD
OVERMIND_OIDC_ISSUER
OVERMIND_OIDC_AUDIENCE
OVERMIND_ALLOWED_SUBJECT
OVERMIND_CURSOR_SECRET
```

운영용 환경 파일의 설명과 예시는 [`deploy/app.env.example`](deploy/app.env.example)과
[`deploy/db.env.example`](deploy/db.env.example)에 있습니다. 실제 시크릿은 저장소에
커밋하지 마세요.

## MCP 클라이언트 연결

공개 MCP endpoint는 다음 하나입니다.

```text
https://overmind.<domain>/mcp
```

서버가 제공하는 도구는 정확히 두 개입니다.

| 도구 | 필요 scope | 역할 |
|---|---|---|
| `remember_memory` | `memory:write` | USER 또는 PROJECT observation 하나를 저장 |
| `recall_memory` | `memory:read` | USER와 선택한 PROJECT observation을 최신순으로 조회 |

토큰 없이 `/mcp`를 호출하면 응답의 `WWW-Authenticate` 헤더가 RFC 9728 metadata
문서를 가리킵니다. MCP 클라이언트는 이 문서를 통해 Auth0 issuer와 지원 scope를
발견합니다.

실제 클라이언트 등록과 OAuth 검증은
[`docs/harness/70-m0-smoke.md`](docs/harness/70-m0-smoke.md)의 수동 절차를 따릅니다.

## 인증과 보안

- JWT의 서명, issuer, audience, subject, expiry를 모두 검사합니다.
- `OVERMIND_ALLOWED_SUBJECT`와 정확히 일치하는 사용자 한 명만 허용합니다.
- read/write scope는 MCP 요청마다 다시 검사합니다.
- query parameter와 form body의 bearer token은 허용하지 않습니다.
- 애플리케이션 포트는 호스트의 `127.0.0.1`에만 게시합니다.
- PostgreSQL 포트는 게시하지 않습니다.
- 앱은 PostgreSQL bootstrap superuser와 분리된 non-superuser role로 접속합니다.
- 메모리 content, source ID, idempotency key, project key, 토큰, claim, 원시 cursor는
  로그에 남기지 않습니다.

보안 경계와 불변식은 [`docs/harness/60-invariants.md`](docs/harness/60-invariants.md),
배포 제약은
[`docs/superpowers/specs/2026-09-04-overmind-deploy-design.md`](docs/superpowers/specs/2026-09-04-overmind-deploy-design.md)에
정리되어 있습니다.

## 배포

운영 구조는 한 호스트의 Docker Compose에 OverMind와 PostgreSQL을 올리고, 호스트의
Caddy가 TLS를 종료해 `127.0.0.1:8080`으로 전달하는 형태입니다. 애플리케이션 이미지는
GitHub Actions가 테스트를 통과한 `master` commit으로 만들고 GHCR에 게시합니다.

실제 배포 전에 다음 운영 값과 외부 설정을 먼저 확정해야 합니다.

- 인스턴스의 CPU, 메모리, 디스크 여유와 컨테이너별 `mem_limit`
- 실제 도메인과 DNS A record
- Auth0 API Identifier, scope, Default Audience, 허용 사용자
- GHCR package 공개 설정 또는 `read:packages` PAT 로그인
- Caddy site block

설치, 최초 기동, SHA 고정 배포 및 롤백의 복사 가능한 명령은
[`deploy/README.md`](deploy/README.md)에 있습니다. 배포 완료 선언 전에는
[`docs/harness/70-m0-smoke.md`](docs/harness/70-m0-smoke.md)의 일반 항목과 D1~D13을
모두 실제 환경에서 확인해야 합니다.

## 백업과 복구

`deploy/backup/overmind-backup.sh`는 PostgreSQL custom-format dump를 만들고 AES-256
대칭키로 암호화합니다. systemd timer 예시는 하루 한 번 백업을 실행합니다.

운영자는 다음 자산을 인스턴스 밖의 안전한 위치에도 보관해야 합니다.

- 암호화된 `*.dump.gpg` 백업
- 백업 passphrase
- `db.env`
- `app.env`의 복구 필수 설정, 특히 cursor secret

백업은 생성 성공만으로 검증되지 않습니다. 별도 PostgreSQL 컨테이너에 복원하고
운영 DB와 observation 행 수를 대조하는 드릴을 수행해야 합니다. 자세한 절차와 실제
재해복구 순서는 [`deploy/README.md`](deploy/README.md)를 따릅니다.

## 개발 및 테스트

| 명령 | 역할 |
|---|---|
| `./gradlew test` | L1 단위 테스트와 ArchUnit 규칙 |
| `./gradlew integrationTest` | L2 Testcontainers 통합 테스트 |
| `./gradlew verify` | compile + L1 + L2 필수 게이트 |
| `./gradlew guardrails` | 마이그레이션, 로그 동반 변경, 문서, 시크릿 가드 |
| `./gradlew evaluationTest` | 실제 LLM을 사용하는 수동/야간 L3 평가 |

변경 작업을 시작하기 전에 루트의 [`AGENTS.md`](AGENTS.md)와
[`docs/harness/00-start-here.md`](docs/harness/00-start-here.md)를 먼저 읽으세요.
빌드 계층과 환경 요구 사항은
[`docs/harness/20-build-and-test.md`](docs/harness/20-build-and-test.md)에 있습니다.

## 프로젝트 구조

```text
src/main/java/com/overmind/
├── domain/          순수 도메인과 값 객체
├── application/     유스케이스와 포트
├── adapter/in/      MCP 진입 어댑터
├── adapter/out/     PostgreSQL 및 보안 출력 어댑터
└── config/          Spring 구성과 운영 설정

deploy/              Compose, Caddy, DB 초기화, 백업 자산
docs/harness/        개발·검증 절차와 불변식
docs/arch/           결정 기록과 아키텍처 사료
docs/superpowers/    기능 설계와 구현 플랜
```

의존성 방향과 패키지 규칙은 테스트로 강제됩니다. 상세한 저장소 지도는
[`docs/harness/10-repo-map.md`](docs/harness/10-repo-map.md)를 참고하세요.

## 아키텍처와 설계 문서

- [M0 설계](docs/superpowers/specs/2026-09-02-overmind-m0-design.md)
- [배포 설계](docs/superpowers/specs/2026-09-04-overmind-deploy-design.md)
- [아키텍처 결정 레지스터](docs/arch/decisions.md)
- [요구사항 R1~R6](docs/requirements/R1-R6.md)
- [활성 불변식](docs/harness/60-invariants.md)
- [M0 원격 스모크 절차](docs/harness/70-m0-smoke.md)

`docs/arch/baseline-v0.1.md`와 `docs/arch/review-v0.1.md`는 과거 설계 사료입니다.
현재 결정을 확인할 때는 `docs/arch/decisions.md`를 우선합니다.

## 로드맵

| 마일스톤 | 방향 |
|---|---|
| M0 | 원본 observation 저장·조회와 배포 가능한 MCP 서버 |
| M1 | 읽기 의도 분류와 검색 경로 |
| M2 | canonical memory와 시점별 사실 정규화 |
| 이후 | snapshot, replay, 평가 고도화, forget |

세부 순서와 아직 열려 있는 결정은 [`log.md`](log.md)의 HEAD와
[`docs/arch/decisions.md`](docs/arch/decisions.md)를 확인하세요.

## 라이선스

현재 저장소에는 별도의 라이선스 파일이 없습니다. 재사용 또는 배포 범위를 공개적으로
정하기 전에 라이선스를 추가해야 합니다.
