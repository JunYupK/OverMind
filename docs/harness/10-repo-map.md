# 10 · 저장소 지도

## 루트

| 파일 | 역할 |
|---|---|
| `AGENTS.md` | 도구 중립 진입점. 라우팅 테이블 |
| `CLAUDE.md` | Claude Code 전용 사항 |
| `log.md` | 작업 로그 (HEAD + 세션 기록) |
| `build.gradle.kts` | 의존성, 테스트 계층 태스크, `verify`/`guardrails` |

## docs/

| 경로 | 역할 |
|---|---|
| `harness/` | 이 프로젝트의 개발 규약 실체 |
| `arch/baseline-v0.1.md` | 최초 아키텍처 설계 (읽기 전용 사료) |
| `arch/review-v0.1.md` | 그 설계에 대한 외부 리뷰 (읽기 전용 사료) |
| `arch/decisions.md` | 확정/미확정 결정 레지스터 |
| `arch/adr/` | 개별 결정 기록 |
| `requirements/R1-R6.md` | 요구사항과 acceptance criteria |
| `eval/` | L3 평가 결과 (날짜별) |
| `log-archive/` | 마일스톤별로 잘라낸 세션 기록 |
| `superpowers/specs/` | 설계 명세 |
| `superpowers/plans/` | 구현 계획 |

## src/main/java/com/overmind/

| 패키지 | 책임 | 금지 |
|---|---|---|
| `domain` | 순수 도메인 | Spring, JPA, adapter 의존 |
| `application` | 유스케이스 + `port/` 인터페이스 | adapter 의존 |
| `adapter/in` | MCP, HTTP 진입 | — |
| `adapter/out` | 영속, LLM, 임베딩 | — |
| `config` | Spring 설정 | — |

프로바이더 고유명(Claude, ChatGPT, OpenAI, Anthropic, Gemini)은
`domain`과 `application`에 등장할 수 없다. `adapter` 안에 가둔다.

## src/test/java/com/overmind/

| 패키지 | 역할 |
|---|---|
| `arch` | ArchUnit 규칙, 프로바이더 이름 소스 스캔 |
| `support` | 테스트 하네스 (Postgres 베이스, LLM 픽스처, 로그 캡처) |
| `guardrail` | 가드레일 검사 (`@Tag("guardrail")`) |
