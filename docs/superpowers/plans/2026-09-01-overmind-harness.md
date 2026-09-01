# OverMind 개발 하네스 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OverMind 저장소에 도구 중립 협업 규약(AGENTS.md / log.md), 단일 기계 게이트(`./gradlew verify`), 가드레일 게이트(`./gradlew guardrails`), 3계층 테스트 하네스, 불변식 카탈로그를 세워 M0 도메인 구현을 시작할 수 있는 상태로 만든다.

**Architecture:** 단일 Gradle 모듈 Spring Boot 애플리케이션. 아키텍처 경계는 패키지로 나누고 ArchUnit 테스트로 강제한다. 테스트는 3계층으로 분리하며 JUnit 태그로 구분한다 — L1(무태그, 외부 의존 없음), L2(`integration`, Testcontainers + LLM 픽스처 재생), L3(`evaluation`, 실 LLM). 가드레일도 JUnit 테스트(`guardrail` 태그)로 구현해 로컬과 CI가 같은 코드를 실행하게 한다.

**Tech Stack:** Java 21, Spring Boot 3.5.0, Gradle 8.14 (Kotlin DSL), JUnit 5, AssertJ, ArchUnit 1.3.0, Testcontainers 1.20.4, Flyway, PostgreSQL + pgvector, GitHub Actions, gitleaks

**Spec:** `docs/superpowers/specs/2026-09-01-overmind-harness-design.md`

## Global Constraints

스펙에서 확정된 프로젝트 전역 제약. 모든 태스크의 요구사항에 암묵적으로 포함된다.

- **저장소 루트:** `C:\Users\top15\Desktop\Project OverMind\OverMind` — 이 문서의 모든 경로는 이 디렉터리 기준 상대 경로다.
- **기본 브랜치:** `master`
- **Java toolchain:** 21
- **Gradle:** 8.14, Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`)
- **Spring Boot:** `3.5.0` / **io.spring.dependency-management:** `1.1.7`
- **ArchUnit:** `com.tngtech.archunit:archunit-junit5:1.3.0`
- **Testcontainers:** BOM `org.testcontainers:testcontainers-bom:1.20.4`
- **PostgreSQL 테스트 이미지:** `pgvector/pgvector:pg16` (기본 `postgres` 이미지에는 vector 확장이 없다)
- **루트 패키지:** `com.overmind`
- **JUnit 태그 규약:** L1 = 태그 없음 / L2 = `@Tag("integration")` / L3 = `@Tag("evaluation")` / 가드레일 = `@Tag("guardrail")`
- **문서 줄 수 상한:** `CLAUDE.md` 40줄, `AGENTS.md` 120줄 (CI가 강제)
- **`spring.jpa.hibernate.ddl-auto`는 `validate` 고정** — 다른 값이면 가드레일 실패
- **`docs/arch/baseline-v0.1.md`, `docs/arch/review-v0.1.md`는 읽기 전용 사료.** 어떤 태스크도 내용을 수정하지 않는다
- **문서 언어:** 한국어
- **커밋 메시지:** 한국어 본문, `type: 요약` 형식 (`docs:`, `feat:`, `chore:`, `test:`, `ci:`)

---

## File Structure

| 경로 | 책임 |
|---|---|
| `AGENTS.md` | 도구 중립 단일 진입점. 규약이 아니라 라우팅 테이블 |
| `CLAUDE.md` | AGENTS.md 포인터 + Claude Code 전용 사항 |
| `log.md` | 작업 로그. HEAD 블록 + append-only 세션 기록 |
| `docs/harness/00-start-here.md` ~ `60-invariants.md` | 규약 실체. 진입 파일이 여기로 라우팅한다 |
| `docs/arch/` | baseline/review 사료 + 결정 레지스터 + ADR |
| `docs/requirements/R1-R6.md` | 요구사항과 acceptance criteria |
| `build.gradle.kts` | 의존성 + 테스트 계층 태스크(`test`/`integrationTest`/`evaluationTest`/`guardrailTest`) + 집합 태스크(`verify`/`guardrails`) |
| `src/main/java/com/overmind/{domain,application,adapter,config}/package-info.java` | 패키지 경계 선언 |
| `src/main/java/com/overmind/application/port/LlmPort.java` 외 | LLM 포트 계약 + 프롬프트 버전 상수 |
| `src/main/resources/db/migration/V*.sql` | Flyway 마이그레이션 (forward-only) |
| `src/test/java/com/overmind/arch/` | ArchUnit 규칙 + 프로바이더 이름 소스 스캔 |
| `src/test/java/com/overmind/support/` | 테스트 하네스 — Postgres 베이스, LLM 픽스처, 로그 캡처 |
| `src/test/java/com/overmind/guardrail/` | 가드레일 검사 (문서 줄 수, ddl-auto, 마이그레이션 해시, log.md 동반 변경) |
| `src/test/resources/llm-fixtures/<promptVersion>/` | 녹화된 LLM 응답 |
| `.github/workflows/ci.yml` | verify / guardrails / evaluation 3잡 |

---

## Task 1: 저장소 재배치와 문서 골격

**Files:**
- Move: `gptsol-plan.md` → `docs/arch/baseline-v0.1.md`
- Move: `opus-review.md` → `docs/arch/review-v0.1.md`
- Create: `docs/arch/decisions.md`
- Create: `docs/arch/adr/.gitkeep`, `docs/eval/.gitkeep`, `docs/log-archive/.gitkeep`, `docs/requirements/.gitkeep`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `docs/arch/baseline-v0.1.md`, `docs/arch/review-v0.1.md` 경로. 이후 모든 문서가 이 경로로 참조한다

- [ ] **Step 1: 디렉터리 생성**

```bash
mkdir -p docs/arch/adr docs/eval docs/log-archive docs/requirements
touch docs/arch/adr/.gitkeep docs/eval/.gitkeep docs/log-archive/.gitkeep docs/requirements/.gitkeep
```

- [ ] **Step 2: 사료 문서 이동**

```bash
git mv gptsol-plan.md docs/arch/baseline-v0.1.md
git mv opus-review.md docs/arch/review-v0.1.md
```

- [ ] **Step 3: 이동 검증**

Run: `ls gptsol-plan.md opus-review.md docs/arch/`
Expected: 루트의 두 파일은 "No such file", `docs/arch/`에 `baseline-v0.1.md`와 `review-v0.1.md`가 보인다

- [ ] **Step 4: 결정 레지스터 작성**

`docs/arch/decisions.md`:

```markdown
# 결정 레지스터

확정된 결정과 아직 열려 있는 결정을 한 곳에 모은다.
사료(`baseline-v0.1.md`, `review-v0.1.md`)는 수정하지 않는다. 결정이 바뀌면 여기에 기록한다.

## 확정

| ID | 결정 | 근거 | 확정일 |
|---|---|---|---|
| D-A | 문서 권위: baseline은 아키텍처 지향점, review는 실행 순서(M0~M6). review §6 C-1~C-7은 반영 대상 | 하네스 스펙 §1 | 2026-09-01 |
| D-B | 스택: Java 21 + Spring Boot 3. ArchUnit으로 아키텍처 불변식 강제 | 하네스 스펙 §1 | 2026-09-01 |
| D-C | log.md: 2단 단일 파일 (HEAD 덮어쓰기 + append-only 세션 기록) | 하네스 스펙 §4 | 2026-09-01 |
| D-D | 루프: 기계 게이트 자동 반송(상한 3회), 리뷰는 도구 교차 검증 후 사람 판단 | 하네스 스펙 §5 | 2026-09-01 |
| D-E | 테스트 3계층: L1 fake / L2 Testcontainers+녹화재생 / L3 실 LLM | 하네스 스펙 §6 | 2026-09-01 |
| D-F | 하네스 구축 범위: Walking Skeleton. 평가자는 마일스톤별 활성화 | 하네스 스펙 §1 | 2026-09-01 |

## 열려 있음 — M0 도메인 브레인스토밍에서 결정

| ID | 안건 | 출처 |
|---|---|---|
| A-1 | Fast path 유지 vs Async only | review §4 |
| A-2 | Replay 불변식(observation=event log) 채택 여부 | review §4 |
| A-3 | `observation.idempotency_key` 구성 방식 | review §4 |
| A-4 | 파이프라인 버저닝 컬럼 세부 | review §4 |
| B-1 | Slot Registry 범위 (dynamic slot 폐기 여부) | review §5 |
| B-2 | Snapshot 테이블화 시점 | review §5 |
| B-3 | Bootstrap 범위와 비용 상한 수치 | review §5 |

## 반영 대기 결함 — M2 이후 도메인 스펙

C-1 async job 순서 보장 / C-2 N:M evidence purge 정책 / C-3 청크 단위 삭제 /
C-4 snapshot 시간 무효화 / C-5 canonical_text 템플릿 버전 / C-6 PENDING UX 구멍 /
C-7 동시성·nullable·confidence 컬럼

C-1은 불변식 INV-06으로 카탈로그에 선등록되어 있다.
```

- [ ] **Step 5: 커밋**

```bash
git add -A docs gptsol-plan.md opus-review.md
git commit -m "docs: 사료 문서를 docs/arch로 이동하고 문서 골격 생성

- gptsol-plan.md -> docs/arch/baseline-v0.1.md
- opus-review.md -> docs/arch/review-v0.1.md
- docs/arch/decisions.md 결정 레지스터 신규
- adr/eval/log-archive/requirements 디렉터리 생성"
```

---

## Task 2: log.md 초기화

**Files:**
- Create: `log.md`

**Interfaces:**
- Consumes: Task 1의 `docs/` 구조
- Produces: `log.md` HEAD 블록 형식. Task 3의 AGENTS.md와 Task 9의 `LogUpdatedGuardTest`가 이 파일을 참조한다

- [ ] **Step 1: log.md 작성**

HTML 주석 구분자는 규약의 일부다. 에이전트가 HEAD 블록의 끝을 기계적으로 찾을 수 있어야 한다.

```markdown
# OverMind 작업 로그

작성 규약은 `docs/harness/00-start-here.md`에 있다.
세션 시작 시에는 아래 HEAD 블록만 읽는다. 세션 기록은 필요할 때만 검색한다.

<!-- ===== HEAD — 항상 최신으로 덮어쓴다 ===== -->

## 현재 상태

- **마일스톤:** H — 개발 하네스 구축
- **최근 갱신:** 2026-09-01 · Claude Code
- **브랜치:** master
- **verify:** 미구축

### 진행 중

- [ ] H-1~H-9 하네스 구축 (`docs/superpowers/plans/2026-09-01-overmind-harness.md`)

### 다음 할 일

1. Task 3 에이전트 문서 세트 작성
2. Task 4 Gradle + Spring Boot 스켈레톤

### 열려 있는 결정

- 없음 (도메인 결정은 `docs/arch/decisions.md` 참조)

### 막힌 것

- 없음

<!-- ===== 세션 기록 — append-only, 최신이 위 ===== -->

## 세션 기록

### 2026-09-01 · Claude Code · Task 1~2

- **한 일:** 사료 문서를 `docs/arch/`로 이동, 문서 골격과 결정 레지스터 생성, log.md 초기화
- **결과:** verify 미구축 / 리뷰 미실시
- **함정:** 없음
- **다음:** Task 3 에이전트 문서 세트
```

- [ ] **Step 2: HEAD 블록 구분자 검증**

Run: `grep -c "===== HEAD" log.md; grep -c "===== 세션 기록" log.md`
Expected: 각각 `1`

- [ ] **Step 3: 커밋**

```bash
git add log.md
git commit -m "docs: log.md 초기화

HEAD 블록 + append-only 세션 기록 2단 구조.
세션 시작 시 HEAD 블록만 읽는다."
```

---

## Task 3: 에이전트 문서 세트

**Files:**
- Create: `AGENTS.md`, `CLAUDE.md`
- Create: `docs/harness/00-start-here.md`, `10-repo-map.md`, `20-build-and-test.md`, `30-loop.md`, `40-guardrails.md`, `50-review-protocol.md`

**Interfaces:**
- Consumes: Task 1 `docs/arch/*`, Task 2 `log.md`
- Produces: `AGENTS.md`(≤120줄), `CLAUDE.md`(≤40줄) — Task 9의 `DocLineLimitGuardTest`가 이 상한을 검사한다

- [ ] **Step 1: AGENTS.md 작성**

```markdown
# AGENTS.md — OverMind

OverMind는 여러 AI 클라이언트(Claude Chat / ChatGPT / Claude Code / Codex)가 공유하는
단일 개인 메모리 서비스다. MCP로 노출하며, 대화에서 관측된 사실을 정규화해
시점별로 유효한 사실을 관리한다.

이 파일은 규약을 담지 않는다. **어디를 읽어야 하는지만** 알려준다.

## 세션 시작 절차 (예외 없음)

1. `log.md`의 HEAD 블록을 읽는다. `<!-- ===== 세션 기록` 아래로는 읽지 않는다.
2. HEAD의 "현재 마일스톤"과 "다음 할 일"을 확인한다.
3. 지금 할 태스크의 스펙/플랜 문서를 읽는다 (`docs/superpowers/`).
4. `docs/harness/00-start-here.md`를 읽는다.

## 절대 규칙

1. `docs/arch/baseline-v0.1.md`와 `docs/arch/review-v0.1.md`는 **읽기 전용 사료**다.
   결정이 바뀌면 `docs/arch/decisions.md`에 기록하고 사료는 건드리지 않는다.
2. `log.md`의 과거 세션 기록 항목은 편집하지 않는다. 정정은 새 항목에 쓴다.
3. `src/` 아래를 고쳤으면 같은 커밋에 `log.md`를 갱신한다. CI가 강제한다.
4. 커밋 전에 `./gradlew verify`가 통과해야 한다.
5. 다음은 금지다 — `master`에 `git push --force`, `git reset --hard`,
   마이그레이션 밖의 `DROP`/`TRUNCATE`, 프로덕션 DB 직접 접속.

## 라우팅 테이블

| 지금 하려는 것 | 읽을 문서 |
|---|---|
| 세션을 막 시작함 | `docs/harness/00-start-here.md` |
| 파일이 어디 있는지 모름 | `docs/harness/10-repo-map.md` |
| 빌드·테스트를 돌리려 함 | `docs/harness/20-build-and-test.md` |
| 태스크를 구현하려 함 | `docs/harness/30-loop.md` |
| 커밋·푸시·마이그레이션·시크릿 | `docs/harness/40-guardrails.md` |
| 남의 코드를 리뷰하려 함 | `docs/harness/50-review-protocol.md` |
| 이 변경이 무엇을 깨면 안 되는지 확인 | `docs/harness/60-invariants.md` |
| 아키텍처 배경이 궁금함 | `docs/arch/baseline-v0.1.md`, `docs/arch/review-v0.1.md` |
| 무엇이 이미 정해졌는지 확인 | `docs/arch/decisions.md` |
| 이 기능이 왜 필요한지 확인 | `docs/requirements/R1-R6.md` |

## 세션 종료 절차

1. `log.md` HEAD 블록을 현재 상태로 덮어쓴다.
2. `## 세션 기록` 바로 아래에 새 항목을 추가한다. 필드는 5개 고정:
   제목줄(날짜 · 도구 · 태스크 · 커밋 SHA) / 한 일 / 결과 / 함정 / 다음.
3. **git이 기록하는 것은 쓰지 않는다.** diff·파일 목록은 git에 있다.
   log.md에는 왜 그렇게 했는지, 시도했다 버린 것, 함정, 열려 있는 결정만 쓴다.
4. 두 변경을 같은 커밋에 넣는다.

## 이 파일의 제약

120줄을 넘기지 않는다. CI가 검사한다.
규약 본문이 이 파일로 흘러들어오면 도구별 문서가 갈라지기 시작한다.
내용은 `docs/harness/`에 한 벌만 둔다.
```

- [ ] **Step 2: CLAUDE.md 작성**

```markdown
# CLAUDE.md

**먼저 `AGENTS.md`를 읽어라.** 이 프로젝트의 규약은 전부 거기서 라우팅된다.
이 파일에는 Claude Code 전용 사항만 있다.

## Claude Code 전용

- 구현 작업은 `superpowers:test-driven-development`를 따른다.
- 버그·테스트 실패를 만나면 `superpowers:systematic-debugging`을 먼저 쓴다.
- 새 기능·설계 변경은 `superpowers:brainstorming`을 먼저 쓴다.
- 리뷰는 **자기가 구현한 코드를 자기가 리뷰하지 않는다.**
  Claude Code가 구현했으면 리뷰는 `/codex`로 넘긴다.
  자세한 규칙은 `docs/harness/50-review-protocol.md`.

## 권한

- 자동 허용 — 읽기·검색, `./gradlew` 실행, `src/**` 편집, `log.md` 편집
- 확인 필요 — 커밋, 푸시, PR 생성, 의존성 추가, 마이그레이션 파일 생성,
  실 LLM 호출(`./gradlew evaluationTest`)
- 금지 — `docs/harness/40-guardrails.md`의 금지 목록

## 이 파일의 제약

40줄을 넘기지 않는다. CI가 검사한다.
규약을 여기 복제하면 `AGENTS.md`와 갈라진다.
```

- [ ] **Step 3: docs/harness/00-start-here.md 작성**

```markdown
# 00 · 세션 시작

## 읽는 순서

1. `log.md` HEAD 블록 — `<!-- ===== 세션 기록` 위까지만
2. HEAD의 "현재 마일스톤", "진행 중", "다음 할 일", "막힌 것"
3. 지금 할 태스크의 플랜 문서
4. 이 파일

## HEAD 블록을 신뢰하는 범위

HEAD는 **마지막 세션이 끝난 시점**의 상태다.
그 세션이 비정상 종료했다면 HEAD가 실제와 다를 수 있다.
`git log --oneline -5`와 `git status`로 한 번 대조하고 시작한다.
불일치를 발견하면 HEAD를 먼저 고치고 작업을 시작한다.

## 세션 종료

HEAD 덮어쓰기 + 세션 기록 1개 추가. 같은 커밋에 넣는다.

세션 기록 항목 형식:

    ### YYYY-MM-DD HH:MM · <도구> · <태스크ID> · <커밋SHA>
    - **한 일:**
    - **결과:** verify 통과/실패 / 리뷰 실시/미실시
    - **함정:**
    - **다음:**

**함정 필드가 이 로그의 존재 이유다.** git은 무엇이 바뀌었는지 기록하지만
무엇에 걸려 넘어졌는지는 기록하지 못한다. 다음 세션이 같은 곳에서
같은 시간을 쓰지 않게 하는 것이 이 필드의 역할이다.

## 마일스톤이 끝나면

`## 세션 기록` 아래 전체를 `docs/log-archive/M<N>.md`로 옮기고,
log.md에는 3줄 요약만 남긴다. 단일 파일을 유지하면서 무한 증식을 막는 장치다.
```

- [ ] **Step 4: docs/harness/10-repo-map.md 작성**

```markdown
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
```

- [ ] **Step 5: docs/harness/20-build-and-test.md 작성**

```markdown
# 20 · 빌드와 테스트

## 커맨드

| 커맨드 | 내용 | 언제 |
|---|---|---|
| `./gradlew test` | L1 단위 + ArchUnit. 외부 의존 없음 | 구현 중 수시로 |
| `./gradlew integrationTest` | L2. Testcontainers + LLM 픽스처 재생 | 커밋 전 |
| `./gradlew verify` | **기계 게이트.** compile + L1 + L2 | 커밋 전 필수 |
| `./gradlew guardrails` | 문서 줄 수, ddl-auto, 마이그레이션 해시, log.md 동반 변경, gitleaks | 커밋 전 |
| `./gradlew evaluationTest` | L3. 실제 LLM 호출. **비용 발생** | 수동 / 야간 CI |
| `./gradlew updateMigrationChecksums` | 새 마이그레이션 추가 후 해시 갱신 | 마이그레이션 추가 시 |

`verify`와 `guardrails`는 CI의 같은 이름 잡과 **정확히 같은 것**을 실행한다.
로컬에서 통과했는데 CI에서 깨지면 그것은 하네스 버그다. 즉시 고친다.

## 테스트 계층

| 계층 | 태그 | 외부 의존 |
|---|---|---|
| L1 | 없음 | 없음. `LlmPort`는 손으로 쓴 fake |
| L2 | `integration` | Testcontainers `pgvector/pgvector:pg16` + 녹화된 LLM 응답 |
| L3 | `evaluation` | 실제 LLM |

**L1에서 `@SpringBootTest`를 쓰지 않는다.** Spring 컨텍스트가 뜨는 순간
단위 테스트가 아니고, 초 단위 피드백이 깨지면 루프가 실용성을 잃는다.

## 사전 준비

- Docker가 떠 있어야 L2가 돈다.
- 컨테이너 재사용을 켜면 L2가 빨라진다. `~/.testcontainers.properties`에
  `testcontainers.reuse.enable=true`를 넣는다. CI에서는 자동으로 무시된다.
- gitleaks가 PATH에 없으면 `guardrails`가 경고만 내고 넘어간다. CI에서는 필수 단계다.

## LLM 픽스처

L2는 `src/test/resources/llm-fixtures/<프롬프트버전>/<키>.json`을 재생한다.

프롬프트를 고쳤으면 재녹화한다:

    ./gradlew integrationTest -Dovermind.llm.record=true

**재녹화 diff는 리뷰 대상이다.** 프롬프트를 바꿨을 때 출력이 어떻게
달라졌는지가 그 diff에 그대로 드러난다. 무심코 커밋하지 않는다.

프롬프트 버전은 세 곳이 일치해야 한다:

1. 픽스처 디렉터리 이름
2. `PromptVersions` 상수
3. (M0 이후) `observation.extractor_version` 컬럼 값

어긋나면 `PromptVersionFixtureLinkTest`가 실패한다.
```

- [ ] **Step 6: docs/harness/30-loop.md 작성**

```markdown
# 30 · 작업 루프

작업 단위는 태스크 하나다.

    [0] 세션 시작 — log.md HEAD + 태스크 스펙
         ↓
    [1] 실패하는 테스트 작성
         ↓
    [2] 구현  ←──────────────────┐
         ↓                       │ 자동 반송 (최대 3회)
    [3] ./gradlew verify ────────┘
         ↓ 통과
    [4] 커밋 (verify 통과 상태만)
         ↓
    [5] 교차 리뷰 — 구현하지 않은 도구가 수행
         ↓
    [6] 사람 판단 — BLOCKING 수용/기각
         ↓  (수용 시 [2]로, 기각 시 사유를 log.md에)
    [7] log.md 갱신 → 태스크 종료

## 규칙

**자동 반송은 [3]→[2] 구간에만 있다.**
기계 게이트만 판정이 객관적이다. 리뷰어의 "통과"는 자동 반송의 근거가 되지 못한다.

**반송 상한은 3회다.**
같은 실패를 세 번 못 고쳤다면 원인 가설이 틀린 것이다. 네 번째를 시도하지 말고
멈춰서 `log.md`의 "막힌 것"에 적는다. 그 다음은 `superpowers:systematic-debugging`이다.

**커밋이 리뷰보다 앞선다.**
리뷰어에게 줄 diff가 필요하고, verify 통과 상태를 커밋으로 고정해야
반송했을 때 되돌아갈 지점이 생긴다.

**리뷰 담당은 log.md가 결정한다.**
세션 기록에 누가 구현했는지 적혀 있다. 같은 모델이 자기 코드를 리뷰하면
통과 편향이 생긴다.

| 구현 도구 | 리뷰 도구 |
|---|---|
| Claude Code | `/codex` |
| Codex | Claude Code 서브에이전트 |
| 기타 LLM | 위 둘 중 택1, 선택 결과를 log.md에 기록 |
```

- [ ] **Step 7: docs/harness/40-guardrails.md 작성**

```markdown
# 40 · 가드레일

## 자동 검사 (`./gradlew guardrails`)

| 영역 | 규칙 |
|---|---|
| 문서 | `CLAUDE.md` 40줄, `AGENTS.md` 120줄 상한 |
| 스키마 | `spring.jpa.hibernate.ddl-auto: validate` 고정 |
| 마이그레이션 | 이미 커밋된 `V*__*.sql`은 수정 불가 (해시 비교) |
| 로그 | `src/**` 변경 시 `log.md` 동반 변경 |
| 시크릿 | gitleaks 스캔 |

## 금지 목록

- `master`에 `git push --force`
- `git reset --hard`
- 마이그레이션 파일 밖에서의 `DROP` / `TRUNCATE`
- 프로덕션 DB 직접 접속
- `docs/arch/baseline-v0.1.md`, `docs/arch/review-v0.1.md` 수정
- `log.md` 과거 세션 기록 항목 편집

## 권한 3단계

| 단계 | 대상 |
|---|---|
| 자동 허용 | 읽기·검색, `./gradlew` 실행, `src/**` 편집, `log.md` 편집 |
| 확인 필요 | 커밋, 푸시, PR 생성, 의존성 추가, 마이그레이션 파일 생성, `evaluationTest` 실행 |
| 금지 | 위 금지 목록 |

## 마이그레이션

Flyway는 forward-only다. 이미 커밋된 마이그레이션은 고치지 않는다.
스키마를 바꾸려면 새 버전 파일을 추가한다.

새 마이그레이션을 추가한 뒤에는 해시를 갱신한다:

    ./gradlew updateMigrationChecksums

**해시 갱신은 새 파일을 추가했을 때만 한다.** 기존 파일을 고치고
해시를 갱신하는 것은 가드레일을 우회하는 행위다.

## 로그에 남기면 안 되는 것

메모리 페이로드, 대화 원문, canonical 값, 토큰, Authorization 헤더.
불변식 INV-02이며 `docs/harness/60-invariants.md`에 검사 방법이 있다.
로그에는 메타데이터만 남긴다 — id, 개수, 소요 시간, 상태.
```

- [ ] **Step 8: docs/harness/50-review-protocol.md 작성**

```markdown
# 50 · 리뷰 프로토콜

## 리뷰어에게 주는 것 — 이것만 준다

1. 태스크 스펙 (acceptance criteria 포함)
2. diff
3. 이 태스크에 걸리는 불변식 목록 (`60-invariants.md`에서 발췌)
4. 아래 판정 형식

더 주지 않는다. 컨텍스트를 넓히면 리뷰가 산만해진다.

## 판정 형식

`BLOCKING` / `NON-BLOCKING` / `의견` 3분류.
각 지적은 파일과 줄 번호를 포함한다.

## BLOCKING 목록 v1 — 4종

1. acceptance criteria 미충족
2. 불변식 카탈로그 위반
3. 데이터 손실 또는 프라이버시 결함
4. 테스트가 이름과 달리 실제로는 아무것도 검증하지 않음

**그 외는 전부 NON-BLOCKING 또는 의견이다. 스타일 선호는 BLOCKING이 될 수 없다.**

LLM 리뷰어를 붙일 때 실제로 발생하는 문제는 놓친 버그가 아니라 과잉 지적이다.
스타일 선호가 BLOCKING으로 올라오면 루프가 진흙탕이 된다.

## 목록 확장 절차

1. "이건 막았어야 했다"는 사례가 나오면 `log.md`의 "열려 있는 결정"에 후보로 올린다.
2. 같은 유형이 **2회 반복**되면 목록에 추가한다. 1회는 우연일 수 있다.
3. 추가하기 전에 먼저 묻는다 — **기계 게이트로 만들 수 있는가.**
   만들 수 있으면 이 목록이 아니라 `./gradlew verify`로 보낸다.

3번이 핵심이다. 사람 판단을 요구하는 게이트보다 자동 반송되는 게이트가 언제나 낫다.

## 사람의 역할

리뷰 결과는 자동 반송되지 않는다. BLOCKING을 수용할지 기각할지는 사람이 정한다.
기각하면 사유를 `log.md`에 남긴다. 같은 지적이 반복해서 기각되면
그 항목은 BLOCKING 목록에서 빼야 한다는 신호다.
```

- [ ] **Step 9: 줄 수 상한 확인**

Run: `wc -l AGENTS.md CLAUDE.md`
Expected: `AGENTS.md` ≤ 120, `CLAUDE.md` ≤ 40. 넘으면 내용을 `docs/harness/`로 옮긴다

- [ ] **Step 10: 커밋**

```bash
git add AGENTS.md CLAUDE.md docs/harness
git commit -m "docs: 에이전트 문서 세트 작성

AGENTS.md는 라우팅 테이블만, CLAUDE.md는 포인터만 담는다.
규약 실체는 docs/harness/에 한 벌만 둔다 - 복제하면 갈라진다."
```

---

## Task 4: Gradle + Spring Boot 스켈레톤과 테스트 계층 태스크

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `.gitignore`
- Create: `gradle/wrapper/*` (wrapper 생성 커맨드 실행 결과)
- Create: `src/main/java/com/overmind/OvermindApplication.java`
- Create: `src/main/java/com/overmind/{domain,application,adapter,config}/package-info.java`
- Create: `src/main/java/com/overmind/adapter/{in,out}/package-info.java`
- Create: `src/main/resources/application.yml`
- Test: `src/test/java/com/overmind/PackageLayoutTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - Gradle 태스크 `test`(L1), `integrationTest`(L2), `evaluationTest`(L3), `guardrailTest`, `verify`, `guardrails`
  - 패키지 `com.overmind.domain` / `.application` / `.adapter.in` / `.adapter.out` / `.config`
  - 가드레일 시스템 프로퍼티 `overmind.guardrail.baseRef` (기본값 `origin/master`)

- [ ] **Step 1: Gradle wrapper 생성**

```bash
gradle wrapper --gradle-version 8.14
```

`gradle` 커맨드가 없으면 IntelliJ에서 프로젝트를 열어 wrapper를 생성하거나,
https://services.gradle.org/distributions/gradle-8.14-bin.zip 을 받아 한 번 실행한다.

Run: `./gradlew --version`
Expected: `Gradle 8.14`

- [ ] **Step 2: settings.gradle.kts 작성**

```kotlin
rootProject.name = "overmind"
```

- [ ] **Step 3: .gitignore 작성**

```gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar

.idea/
*.iml
out/

.env
.env.local

*.log
```

- [ ] **Step 4: 실패하는 테스트 작성**

`src/test/java/com/overmind/PackageLayoutTest.java`:

```java
package com.overmind;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * L1. 아키텍처 경계가 되는 최상위 패키지가 실제로 존재하는지 확인한다.
 * ArchUnit 규칙(Task 5)은 이 패키지들을 대상으로 삼는다.
 */
class PackageLayoutTest {

    private static final List<String> REQUIRED_PACKAGES =
            List.of("domain", "application", "adapter/in", "adapter/out", "config");

    @Test
    void base_packages_exist() {
        for (String pkg : REQUIRED_PACKAGES) {
            Path dir = Path.of("src/main/java/com/overmind", pkg);
            assertThat(dir)
                    .as("아키텍처 경계 패키지 %s 가 없습니다", pkg)
                    .isDirectory();
        }
    }
}
```

- [ ] **Step 5: build.gradle.kts 작성**

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.overmind"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------- 테스트 계층 ----------
// L1 = 태그 없음 / L2 = integration / L3 = evaluation / 가드레일 = guardrail

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration", "evaluation", "guardrail")
    }
    testLogging { events("failed") }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "L2 — Testcontainers + LLM 픽스처 재생"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    systemProperty("overmind.llm.record", System.getProperty("overmind.llm.record", "false"))
    shouldRunAfter(tasks.named("test"))
    testLogging { events("failed") }
}

val evaluationTest = tasks.register<Test>("evaluationTest") {
    group = "verification"
    description = "L3 — 실제 LLM 호출. 비용이 발생한다"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("evaluation") }
    testLogging { events("failed", "passed") }
}

val guardrailTest = tasks.register<Test>("guardrailTest") {
    group = "verification"
    description = "가드레일 검사"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("guardrail") }
    systemProperty(
        "overmind.guardrail.baseRef",
        (project.findProperty("baseRef") ?: "origin/master").toString()
    )
    outputs.upToDateWhen { false }
    testLogging { events("failed") }
}

val gitleaksScan = tasks.register("gitleaksScan") {
    group = "verification"
    description = "gitleaks 시크릿 스캔. PATH에 없으면 경고만 내고 넘어간다"
    doLast {
        val available = try {
            ProcessBuilder("gitleaks", "version").start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
        if (!available) {
            logger.warn("[guardrails] gitleaks가 PATH에 없어 로컬 스캔을 생략합니다. CI에서는 필수 단계입니다.")
            return@doLast
        }
        val exit = ProcessBuilder("gitleaks", "detect", "--no-banner", "--redact")
            .directory(rootDir)
            .inheritIO()
            .start()
            .waitFor()
        if (exit != 0) {
            throw GradleException("gitleaks가 시크릿을 탐지했습니다 (exit=$exit)")
        }
    }
}

// ---------- 집합 게이트 ----------
// CI의 같은 이름 잡과 정확히 같은 것을 실행해야 한다

tasks.register("verify") {
    group = "verification"
    description = "기계 게이트 — compile + L1 + ArchUnit + L2 + 활성 불변식"
    dependsOn(tasks.named("test"), integrationTest)
}

tasks.register("guardrails") {
    group = "verification"
    description = "가드레일 게이트 — 문서 상한, ddl-auto, 마이그레이션 해시, log.md, gitleaks"
    dependsOn(guardrailTest, gitleaksScan)
}
```

- [ ] **Step 6: 테스트 실행 — 실패 확인**

Run: `./gradlew test --tests "com.overmind.PackageLayoutTest"`
Expected: FAIL — `아키텍처 경계 패키지 domain 가 없습니다`

- [ ] **Step 7: 패키지 구조 생성**

`src/main/java/com/overmind/domain/package-info.java`:

```java
/**
 * 순수 도메인.
 *
 * <p>Spring, JPA, adapter 패키지에 의존하지 않는다 (AR-1).
 * 프로바이더 고유명이 등장하지 않는다 (AR-4 / INV-01).
 */
package com.overmind.domain;
```

`src/main/java/com/overmind/application/package-info.java`:

```java
/**
 * 유스케이스 계층.
 *
 * <p>adapter 패키지에 의존하지 않는다 (AR-2).
 * 외부 시스템은 {@code application.port}의 인터페이스로만 접근한다.
 */
package com.overmind.application;
```

`src/main/java/com/overmind/adapter/in/package-info.java`:

```java
/** 진입 어댑터 — MCP, HTTP. */
package com.overmind.adapter.in;
```

`src/main/java/com/overmind/adapter/out/package-info.java`:

```java
/**
 * 진출 어댑터 — 영속, LLM, 임베딩.
 *
 * <p>LLM/임베딩 SDK 클래스는 이 패키지 밖에서 참조되지 않는다 (AR-3).
 */
package com.overmind.adapter.out;
```

`src/main/java/com/overmind/config/package-info.java`:

```java
/** Spring 설정. */
package com.overmind.config;
```

- [ ] **Step 8: 애플리케이션 클래스와 설정 작성**

`src/main/java/com/overmind/OvermindApplication.java`:

```java
package com.overmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OvermindApplication {

    public static void main(String[] args) {
        SpringApplication.run(OvermindApplication.class, args);
    }
}
```

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: overmind
  datasource:
    url: ${OVERMIND_DB_URL:}
    username: ${OVERMIND_DB_USER:}
    password: ${OVERMIND_DB_PASSWORD:}
  jpa:
    hibernate:
      # 가드레일이 이 값을 검사한다. validate 외의 값으로 바꾸지 않는다.
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
```

- [ ] **Step 9: 테스트 실행 — 통과 확인**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 10: 게이트 태스크 확인**

Run: `./gradlew verify`
Expected: BUILD SUCCESSFUL (L2 테스트는 아직 없으므로 `integrationTest`는 NO-SOURCE로 통과)

- [ ] **Step 11: 커밋**

```bash
git add settings.gradle.kts build.gradle.kts .gitignore gradle gradlew gradlew.bat src
git commit -m "feat: Gradle + Spring Boot 스켈레톤과 테스트 계층 태스크

- 단일 모듈. 아키텍처 경계는 패키지로 나누고 ArchUnit으로 강제한다
- 테스트 태그: L1 무태그 / L2 integration / L3 evaluation / guardrail
- verify = compile + L1 + L2, guardrails = 가드레일 + gitleaks
- ddl-auto validate 고정"
```

---

## Task 5: ArchUnit 규칙 AR-1 ~ AR-4

**Files:**
- Create: `src/test/resources/archunit.properties`
- Test: `src/test/java/com/overmind/arch/LayerDependencyTest.java`
- Test: `src/test/java/com/overmind/arch/ProviderNameLeakTest.java`
- Temp: `src/main/java/com/overmind/domain/TempViolation.java` (Step 2에서 만들고 Step 4에서 삭제)
- Temp: `src/main/java/com/overmind/domain/ClaudeSampleValue.java` (Step 6에서 만들고 Step 8에서 삭제)

**Interfaces:**
- Consumes: Task 4의 패키지 구조
- Produces: `verify`가 AR-1~AR-4를 강제. INV-01의 구현체

- [ ] **Step 1: archunit.properties 작성**

`src/test/resources/archunit.properties`:

```properties
# 검사 대상 클래스가 아직 없는 규칙도 통과시킨다.
# 하네스는 코드보다 먼저 세워지므로 초기에는 대부분의 패키지가 비어 있다.
archRule.failOnEmptyShould=false
```

- [ ] **Step 2: AR-1 위반 샘플과 테스트 작성**

먼저 일부러 규칙을 어기는 클래스를 만든다. 규칙이 실제로 잡는지 확인하기 위해서다.

`src/main/java/com/overmind/domain/TempViolation.java`:

```java
package com.overmind.domain;

import org.springframework.stereotype.Component;

/** AR-1 규칙이 동작하는지 확인하기 위한 임시 클래스. Step 4에서 삭제한다. */
@Component
public class TempViolation {
}
```

`src/test/java/com/overmind/arch/LayerDependencyTest.java`:

```java
package com.overmind.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** L1. 아키텍처 의존 방향과 로깅 관련 불변식을 강제한다. */
@AnalyzeClasses(packages = "com.overmind", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyTest {

    @ArchTest
    static final ArchRule AR_1_domain_is_pure =
            noClasses()
                    .that().resideInAPackage("com.overmind.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.overmind.adapter..")
                    .because("AR-1: domain은 순수해야 한다. 프레임워크와 어댑터에 의존하지 않는다");

    @ArchTest
    static final ArchRule AR_2_application_does_not_depend_on_adapter =
            noClasses()
                    .that().resideInAPackage("com.overmind.application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.overmind.adapter..")
                    .because("AR-2: application은 port 인터페이스로만 바깥과 통신한다");

    @ArchTest
    static final ArchRule AR_3_llm_sdk_stays_in_outbound_adapter =
            noClasses()
                    .that().resideOutsideOfPackage("com.overmind.adapter.out..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.anthropic..",
                            "com.openai..",
                            "dev.langchain4j..",
                            "io.modelcontextprotocol..")
                    .because("AR-3: LLM/MCP SDK는 진출 어댑터 안에 가둔다");

    @ArchTest
    static final ArchRule INV_02_domain_has_no_toString =
            noMethods()
                    .that().areDeclaredInClassesThat().resideInAPackage("com.overmind.domain..")
                    .should().haveName("toString")
                    .because("INV-02: 도메인 엔티티의 toString이 민감 값을 로그로 흘린다");
}
```

- [ ] **Step 3: 테스트 실행 — AR-1 실패 확인**

Run: `./gradlew test --tests "com.overmind.arch.LayerDependencyTest"`
Expected: FAIL — `AR_1_domain_is_pure` 가 `TempViolation`의 Spring 의존을 지적한다

- [ ] **Step 4: 위반 샘플 삭제하고 통과 확인**

```bash
rm src/main/java/com/overmind/domain/TempViolation.java
```

Run: `./gradlew test --tests "com.overmind.arch.LayerDependencyTest"`
Expected: PASS

- [ ] **Step 5: AR-4 소스 스캔 테스트 작성**

ArchUnit은 문자열 리터럴 내용을 볼 수 없다. AR-4는 소스 스캔으로 구현한다.

`src/test/java/com/overmind/arch/ProviderNameLeakTest.java`:

```java
package com.overmind.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L1. AR-4 / INV-01 — 프로바이더 고유명이 코어 도메인에 누출되지 않는다.
 *
 * <p>타입명뿐 아니라 식별자와 문자열 리터럴까지 잡아야 하므로 소스를 직접 읽는다.
 * ArchUnit은 바이트코드를 보기 때문에 문자열 리터럴 내용을 검사할 수 없다.
 */
class ProviderNameLeakTest {

    private static final List<String> FORBIDDEN =
            List.of("Claude", "ChatGPT", "OpenAI", "Anthropic", "Gemini");

    private static final List<Path> SCANNED_ROOTS =
            List.of(
                    Path.of("src/main/java/com/overmind/domain"),
                    Path.of("src/main/java/com/overmind/application"));

    @Test
    void core_domain_contains_no_provider_names() throws IOException {
        List<String> hits = new ArrayList<>();

        for (Path root : SCANNED_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                List<Path> javaFiles =
                        paths.filter(p -> p.toString().endsWith(".java")).toList();
                for (Path file : javaFiles) {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        for (String word : FORBIDDEN) {
                            if (lines.get(i).contains(word)) {
                                hits.add(file + ":" + (i + 1) + " → " + word);
                            }
                        }
                    }
                }
            }
        }

        assertThat(hits)
                .as(
                        "AR-4 / INV-01: 프로바이더 고유명이 코어 도메인에 누출되었습니다. "
                                + "프로바이더 차이는 adapter 안에 가둡니다")
                .isEmpty();
    }
}
```

- [ ] **Step 6: AR-4 위반 샘플 작성**

`src/main/java/com/overmind/domain/ClaudeSampleValue.java`:

```java
package com.overmind.domain;

/** AR-4 규칙이 동작하는지 확인하기 위한 임시 클래스. Step 8에서 삭제한다. */
public record ClaudeSampleValue(String value) {
}
```

- [ ] **Step 7: 테스트 실행 — AR-4 실패 확인**

Run: `./gradlew test --tests "com.overmind.arch.ProviderNameLeakTest"`
Expected: FAIL — `ClaudeSampleValue.java:4 → Claude` 를 포함한 목록이 비어 있지 않다고 보고한다

- [ ] **Step 8: 위반 샘플 삭제하고 전체 통과 확인**

```bash
rm src/main/java/com/overmind/domain/ClaudeSampleValue.java
```

Run: `./gradlew verify`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add src/test/java/com/overmind/arch src/test/resources/archunit.properties
git commit -m "test: ArchUnit AR-1~AR-4로 아키텍처 경계 강제

- AR-1 domain 순수성, AR-2 application->adapter 금지, AR-3 SDK 격리
- AR-4는 소스 스캔으로 구현. ArchUnit은 문자열 리터럴을 못 본다
- INV-02 일부(domain toString 금지)를 함께 건다
- archRule.failOnEmptyShould=false: 검사 대상 코드보다 규칙이 먼저 선다"
```

---

## Task 6: Testcontainers + Flyway L2 하네스

**Files:**
- Create: `src/test/java/com/overmind/support/PostgresTestBase.java`
- Create: `src/main/resources/db/migration/V1__enable_pgvector.sql`
- Test: `src/test/java/com/overmind/support/FlywayMigrationTest.java`

**Interfaces:**
- Consumes: Task 4의 Gradle `integrationTest` 태스크, `application.yml`
- Produces:
  - `com.overmind.support.PostgresTestBase` — L2 테스트가 상속하는 베이스 클래스.
    컨테이너를 정적 싱글턴으로 한 번만 띄우고 `spring.datasource.*`를 주입한다
  - Flyway 마이그레이션 디렉터리 `src/main/resources/db/migration/`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/overmind/support/PostgresTestBase.java`:

```java
package com.overmind.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * L2 테스트의 공통 베이스.
 *
 * <p>컨테이너를 정적 싱글턴으로 한 번만 띄운다. JUnit의 {@code @Container}를 쓰면
 * 클래스마다 새로 뜨기 때문에 L2 전체 시간이 선형으로 늘어난다.
 *
 * <p>기본 postgres 이미지에는 vector 확장이 없으므로 pgvector 이미지를 쓴다.
 * 로컬에서 재사용을 켜려면 {@code ~/.testcontainers.properties}에
 * {@code testcontainers.reuse.enable=true}를 넣는다. CI에서는 자동으로 무시된다.
 */
public abstract class PostgresTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("pgvector/pgvector:pg16")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("overmind")
                    .withUsername("overmind")
                    .withPassword("overmind")
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

`src/test/java/com/overmind/support/FlywayMigrationTest.java`:

```java
package com.overmind.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** L2. Flyway 마이그레이션이 실제로 적용된 스키마 위에서 테스트가 돈다는 것을 확인한다. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FlywayMigrationTest extends PostgresTestBase {

    @Autowired private DataSource dataSource;

    @Test
    void pgvector_extension_is_created_by_migration() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("V1 마이그레이션이 vector 확장을 생성해야 합니다")
                    .isEqualTo(1);
        }
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Docker가 떠 있어야 한다.

Run: `./gradlew integrationTest`
Expected: FAIL — `V1 마이그레이션이 vector 확장을 생성해야 합니다 ... expected: 1 but was: 0`

- [ ] **Step 3: 마이그레이션 작성**

`src/main/resources/db/migration/V1__enable_pgvector.sql`:

```sql
-- OverMind는 canonical memory의 임베딩 검색에 pgvector를 쓴다.
-- 확장 생성을 마이그레이션에 두어, 테스트가 실제 프로덕션 스키마 절차를 거치게 한다.
CREATE EXTENSION IF NOT EXISTS vector;
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew integrationTest`
Expected: PASS

- [ ] **Step 5: verify 전체 확인**

Run: `./gradlew verify`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/db/migration src/test/java/com/overmind/support
git commit -m "test: Testcontainers + Flyway L2 하네스

- pgvector/pgvector:pg16 정적 싱글턴 컨테이너. 클래스마다 띄우지 않는다
- Flyway 마이그레이션을 실제로 돌린 스키마 위에서 L2를 실행한다
- V1: vector 확장 생성"
```

---

## Task 7: LLM 포트와 픽스처 재생·녹화 장치

**Files:**
- Create: `src/main/java/com/overmind/application/port/LlmPort.java`
- Create: `src/main/java/com/overmind/application/port/LlmRequest.java`
- Create: `src/main/java/com/overmind/application/port/LlmResponse.java`
- Create: `src/main/java/com/overmind/application/port/PromptVersions.java`
- Create: `src/test/java/com/overmind/support/FixtureLlmPort.java`
- Create: `src/test/resources/llm-fixtures/extractor-v1/.gitkeep`
- Test: `src/test/java/com/overmind/support/PromptVersionFixtureLinkTest.java`
- Test: `src/test/java/com/overmind/support/FixtureLlmPortTest.java`

**Interfaces:**
- Consumes: Task 4의 `com.overmind.application` 패키지
- Produces:
  - `LlmPort.complete(LlmRequest) → LlmResponse`
  - `record LlmRequest(String promptVersion, String prompt)`
  - `record LlmResponse(String content)`
  - `PromptVersions.EXTRACTOR = "extractor-v1"`, `PromptVersions.all() → Set<String>`
  - `FixtureLlmPort.replaying()` — L2가 쓰는 재생 포트
  - `FixtureLlmPort.recording(LlmPort real)` — 녹화 모드 포트

- [ ] **Step 1: 실패하는 테스트 작성 — 버전 삼중 연결**

`src/test/java/com/overmind/support/PromptVersionFixtureLinkTest.java`:

```java
package com.overmind.support;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.overmind.application.port.PromptVersions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L1. 프롬프트 버전과 픽스처 디렉터리가 일치하는지 검사한다.
 *
 * <p>프롬프트를 고치고 픽스처 재녹화를 잊는 것이 이 구조의 가장 흔한 실패다.
 * 이름을 묶어두면 기계가 잡는다.
 */
class PromptVersionFixtureLinkTest {

    private static final Path FIXTURE_ROOT = Path.of("src/test/resources/llm-fixtures");

    @Test
    void declared_prompt_versions_and_fixture_directories_match() throws IOException {
        assertThat(FIXTURE_ROOT)
                .as("픽스처 루트 디렉터리가 있어야 합니다")
                .isDirectory();

        Set<String> directories;
        try (Stream<Path> entries = Files.list(FIXTURE_ROOT)) {
            directories =
                    entries.filter(Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .collect(toSet());
        }

        assertThat(directories)
                .as(
                        "픽스처 디렉터리와 PromptVersions.all()이 일치해야 합니다. "
                                + "프롬프트 버전을 올렸으면 -Dovermind.llm.record=true 로 재녹화하세요")
                .isEqualTo(PromptVersions.all());
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew test --tests "com.overmind.support.PromptVersionFixtureLinkTest"`
Expected: FAIL — `package com.overmind.application.port does not exist`

- [ ] **Step 3: 포트 계약과 버전 상수 작성**

`src/main/java/com/overmind/application/port/LlmRequest.java`:

```java
package com.overmind.application.port;

/**
 * LLM 호출 요청.
 *
 * @param promptVersion 이 요청을 만든 프롬프트의 버전. 픽스처 디렉터리 이름과 같아야 하고,
 *     M0 이후에는 {@code observation.extractor_version}에 그대로 기록된다
 * @param prompt 최종 프롬프트 본문
 */
public record LlmRequest(String promptVersion, String prompt) {}
```

`src/main/java/com/overmind/application/port/LlmResponse.java`:

```java
package com.overmind.application.port;

/**
 * LLM 호출 응답.
 *
 * @param content 모델이 돌려준 본문
 */
public record LlmResponse(String content) {}
```

`src/main/java/com/overmind/application/port/LlmPort.java`:

```java
package com.overmind.application.port;

/**
 * 의미 추론용 LLM 호출 포트.
 *
 * <p>구현체는 {@code adapter.out} 안에만 존재한다 (AR-3).
 * application과 domain은 이 인터페이스만 안다.
 */
public interface LlmPort {

    LlmResponse complete(LlmRequest request);
}
```

`src/main/java/com/overmind/application/port/PromptVersions.java`:

```java
package com.overmind.application.port;

import java.util.Set;

/**
 * 선언된 프롬프트 버전.
 *
 * <p>이 상수는 세 곳과 묶여 있다.
 *
 * <ol>
 *   <li>{@code src/test/resources/llm-fixtures/<버전>/} 디렉터리 이름
 *   <li>이 클래스의 상수
 *   <li>(M0 이후) {@code observation.extractor_version} 컬럼 값
 * </ol>
 *
 * <p>어긋나면 {@code PromptVersionFixtureLinkTest}가 실패한다.
 */
public final class PromptVersions {

    /** 대화에서 observation을 추출하는 프롬프트. */
    public static final String EXTRACTOR = "extractor-v1";

    private PromptVersions() {}

    public static Set<String> all() {
        return Set.of(EXTRACTOR);
    }
}
```

- [ ] **Step 4: 픽스처 디렉터리 생성**

```bash
mkdir -p src/test/resources/llm-fixtures/extractor-v1
touch src/test/resources/llm-fixtures/extractor-v1/.gitkeep
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew test --tests "com.overmind.support.PromptVersionFixtureLinkTest"`
Expected: PASS

- [ ] **Step 6: 실패하는 테스트 작성 — 픽스처 재생**

`src/test/java/com/overmind/support/FixtureLlmPortTest.java`:

```java
package com.overmind.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.overmind.application.port.LlmRequest;
import com.overmind.application.port.LlmResponse;
import com.overmind.application.port.PromptVersions;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** L1. 픽스처 재생·녹화 장치 자체를 검증한다. */
class FixtureLlmPortTest {

    @Test
    void replays_a_recorded_response(@TempDir Path root) throws Exception {
        LlmRequest request = new LlmRequest(PromptVersions.EXTRACTOR, "안녕하세요");
        FixtureLlmPort port = new FixtureLlmPort(root, null, false);

        Path file = port.fixtureFile(request);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"content\":\"녹화된 응답\"}");

        LlmResponse response = port.complete(request);

        assertThat(response.content()).isEqualTo("녹화된 응답");
    }

    @Test
    void fails_loudly_when_fixture_is_missing(@TempDir Path root) {
        LlmRequest request = new LlmRequest(PromptVersions.EXTRACTOR, "픽스처 없는 프롬프트");
        FixtureLlmPort port = new FixtureLlmPort(root, null, false);

        assertThatThrownBy(() -> port.complete(request))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("overmind.llm.record");
    }

    @Test
    void records_through_the_delegate_and_writes_the_fixture(@TempDir Path root) throws Exception {
        LlmRequest request = new LlmRequest(PromptVersions.EXTRACTOR, "새 프롬프트");
        FixtureLlmPort port =
                new FixtureLlmPort(root, req -> new LlmResponse("실제 모델 응답"), true);

        LlmResponse response = port.complete(request);

        assertThat(response.content()).isEqualTo("실제 모델 응답");
        assertThat(Files.readString(port.fixtureFile(request))).contains("실제 모델 응답");
    }

    @Test
    void same_prompt_maps_to_the_same_fixture_file(@TempDir Path root) {
        FixtureLlmPort port = new FixtureLlmPort(root, null, false);

        Path a = port.fixtureFile(new LlmRequest(PromptVersions.EXTRACTOR, "같은 프롬프트"));
        Path b = port.fixtureFile(new LlmRequest(PromptVersions.EXTRACTOR, "같은 프롬프트"));
        Path c = port.fixtureFile(new LlmRequest(PromptVersions.EXTRACTOR, "다른 프롬프트"));

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }
}
```

- [ ] **Step 7: 테스트 실행 — 실패 확인**

Run: `./gradlew test --tests "com.overmind.support.FixtureLlmPortTest"`
Expected: FAIL — `cannot find symbol: class FixtureLlmPort`

- [ ] **Step 8: FixtureLlmPort 구현**

`src/test/java/com/overmind/support/FixtureLlmPort.java`:

```java
package com.overmind.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.overmind.application.port.LlmPort;
import com.overmind.application.port.LlmRequest;
import com.overmind.application.port.LlmResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * L2용 LLM 포트. 녹화된 응답을 재생하거나, 녹화 모드에서 실제 포트를 호출하고 픽스처를 갱신한다.
 *
 * <p>파일 경로는 {@code <root>/<promptVersion>/<프롬프트 SHA-256 앞 16자>.json}이다.
 * 프롬프트가 같으면 항상 같은 파일을 가리키므로, 테스트가 케이스 이름을 관리할 필요가 없다.
 */
public final class FixtureLlmPort implements LlmPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;
    private final LlmPort delegate;
    private final boolean recording;

    /** 재생 전용. */
    public FixtureLlmPort(Path root, LlmPort delegate) {
        this(root, delegate, Boolean.getBoolean("overmind.llm.record"));
    }

    public FixtureLlmPort(Path root, LlmPort delegate, boolean recording) {
        this.root = root;
        this.delegate = delegate;
        this.recording = recording;
    }

    /** 저장소의 실제 픽스처를 재생하는 기본 인스턴스. */
    public static FixtureLlmPort replaying() {
        return new FixtureLlmPort(Path.of("src/test/resources/llm-fixtures"), null, false);
    }

    /** 실제 포트를 감싸 녹화하는 인스턴스. */
    public static FixtureLlmPort recording(LlmPort real) {
        return new FixtureLlmPort(Path.of("src/test/resources/llm-fixtures"), real, true);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Path file = fixtureFile(request);

        if (recording) {
            if (delegate == null) {
                throw new IllegalStateException(
                        "녹화 모드인데 실제 LlmPort가 주입되지 않았습니다");
            }
            LlmResponse fresh = delegate.complete(request);
            write(file, fresh);
            return fresh;
        }

        if (!Files.exists(file)) {
            throw new AssertionError(
                    "LLM 픽스처가 없습니다: "
                            + file
                            + System.lineSeparator()
                            + "재녹화: ./gradlew integrationTest -Dovermind.llm.record=true");
        }
        return read(file);
    }

    /** 이 요청이 매핑되는 픽스처 파일 경로. */
    public Path fixtureFile(LlmRequest request) {
        return root.resolve(request.promptVersion()).resolve(keyOf(request.prompt()) + ".json");
    }

    static String keyOf(String prompt) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(prompt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private LlmResponse read(Path file) {
        try {
            return MAPPER.readValue(Files.readString(file), LlmResponse.class);
        } catch (IOException e) {
            throw new UncheckedIOException("픽스처를 읽지 못했습니다: " + file, e);
        }
    }

    private void write(Path file, LlmResponse response) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(response));
        } catch (IOException e) {
            throw new UncheckedIOException("픽스처를 쓰지 못했습니다: " + file, e);
        }
    }
}
```

- [ ] **Step 9: 테스트 실행 — 통과 확인**

Run: `./gradlew verify`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/overmind/application src/test/java/com/overmind/support src/test/resources/llm-fixtures
git commit -m "feat: LLM 포트 계약과 픽스처 재생/녹화 장치

- LlmPort/LlmRequest/LlmResponse: LLM을 포트 뒤로 밀어 L1을 결정론적으로 만든다
- FixtureLlmPort: 프롬프트 해시로 픽스처를 찾아 재생. 없으면 재녹화 방법을 알리고 실패
- PromptVersionFixtureLinkTest: 프롬프트 버전과 픽스처 디렉터리 일치를 강제"
```

---

## Task 8: 불변식 카탈로그와 INV-02 로그 캡처

**Files:**
- Create: `docs/harness/60-invariants.md`
- Create: `src/test/java/com/overmind/support/LogCapture.java`
- Test: `src/test/java/com/overmind/support/LogCaptureTest.java`

**Interfaces:**
- Consumes: Task 3의 `docs/harness/` 구조, Task 5의 `LayerDependencyTest`
- Produces:
  - `docs/harness/60-invariants.md` — AGENTS.md 라우팅 테이블이 이미 가리키고 있는 문서
  - `LogCapture.start()` → `AutoCloseable`, `.lines()`, `.assertNoOccurrenceOf(String...)`
    — M0 이후 시나리오 테스트가 INV-02를 검사할 때 쓰는 유틸리티

- [ ] **Step 1: 불변식 카탈로그 작성**

`docs/harness/60-invariants.md`:

```markdown
# 60 · 불변식 카탈로그

이 프로젝트가 깨뜨리면 안 되는 것들의 목록이다.
각 항목은 **활성 마일스톤**을 가진다. 그 마일스톤에 도달하면 검사를 켠다.

카탈로그는 코드보다 먼저 존재한다. 무엇을 검사할지 알고 짜야
검사 가능한 형태로 짜게 된다.

## 요약

| ID | 불변식 | 검사 | 활성 | 상태 |
|---|---|---|---|---|
| INV-01 | 프로바이더 개념이 코어 도메인에 누출되지 않는다 | AR-4 소스 스캔 | M0 | 구현됨 |
| INV-02 | 로그에 민감 값이 나타나지 않는다 | ArchUnit + L2 로그 캡처 | M0 | 부분 구현 |
| INV-09 | 동일 idempotency_key로 observation이 중복 적재되지 않는다 | L2 | M0 | 문서화됨 |
| INV-07 | NONE 질의는 메모리 검색을 호출하지 않는다 | L2 + L3 골든셋 | M1 | 문서화됨 |
| INV-03 | 일반 canonicalization은 observation을 변경하지 않는다 | L2 체크섬 비교 | M2 | 문서화됨 |
| INV-04 | SINGLE 슬롯에 상호배타 current 사실이 공존하지 않는다 | L2 + DB 제약 | M2 | 문서화됨 |
| INV-05 | Snapshot은 Canonical Memory만으로 재구축 가능하다 | L2 재구축 diff | M2 | 문서화됨 |
| INV-06 | (subject, slot) 직렬화 + observed_at 순서 보장 | L2 역순 도착 | M2 | 문서화됨 |
| INV-08 | FORGET 이후 어떤 경로로도 재출현하지 않는다 | L2 전 경로 스윕 | M6 | 문서화됨 |
| ~~INV-10~~ | ~~Async-required가 Fast eligibility를 이긴다~~ | — | 폐기 | 폐기됨 |

---

### INV-01 — 프로바이더 중립성

- **진술:** `com.overmind.domain`과 `com.overmind.application`의 소스에
  프로바이더 고유명(Claude, ChatGPT, OpenAI, Anthropic, Gemini)이 등장하지 않는다.
- **근거:** baseline §34, §37 아키텍처 평가자
- **검사:** `ProviderNameLeakTest` (소스 스캔). 타입명·식별자·문자열 리터럴 전부.
  ArchUnit은 바이트코드를 보므로 리터럴 내용을 검사할 수 없다.
- **활성:** M0 · **상태:** 구현됨

### INV-02 — 로그 누출 금지

- **진술:** 애플리케이션 로그에 메모리 페이로드, 대화 원문, canonical 값,
  토큰, Authorization 헤더가 나타나지 않는다.
- **근거:** baseline §30, §37 로깅 평가자
- **검사 (세 겹):**
  1. ArchUnit `INV_02_domain_has_no_toString` — 도메인 엔티티의 toString 금지
  2. (M0) 시나리오 L2 테스트에서 `LogCapture`로 로그를 캡처하고
     테스트가 심어둔 매직 스트링이 없는지 확인
  3. 코드 리뷰 — BLOCKING 3번(프라이버시 결함)
  2번이 실질적인 방어선이다. 1번은 흔한 실수를 값싸게 걸러낸다.
- **활성:** M0 · **상태:** 부분 구현 (1번 구현됨, 2번은 유틸리티만 준비됨)

### INV-09 — 관측 멱등성

- **진술:** 같은 `idempotency_key`로 두 번 remember해도 observation은 하나만 적재된다.
- **근거:** review A-3. MCP 클라이언트 재시도와 LLM의 동일 턴 중복 호출이 실재한다
- **검사:** L2 — 같은 키로 두 번 호출한 뒤 행 수를 센다. DB unique 제약이 1차 방어선
- **활성:** M0 · **상태:** 문서화됨 (스키마가 생기는 M0 첫 태스크에서 구현)

### INV-07 — 불필요한 검색 금지

- **진술:** READ_INTENT가 NONE인 질의는 메모리 검색을 호출하지 않는다.
- **근거:** baseline §14, §31, §37 retrieval 평가자
- **검사:** L2 — 검색 포트를 스파이로 감싸 호출 횟수 0을 확인.
  L3 골든셋의 NONE 10문항으로 실제 모델 행동까지 확인
- **활성:** M1 · **상태:** 문서화됨

### INV-03 — Observation 불변

- **진술:** 일반 canonicalization은 observation 행을 변경하지 않는다. FORGET만 예외다.
- **근거:** baseline §33-3, §37 observation 평가자, review A-2 (replay 불변식)
- **검사:** L2 — canonicalization 전후로 observation 테이블 체크섬을 비교
- **활성:** M2 · **상태:** 문서화됨

### INV-04 — SINGLE 슬롯 배타성

- **진술:** cardinality가 SINGLE인 슬롯에 상호배타적인 current 사실이 동시에 존재할 수 없다.
- **근거:** baseline §33-6, §37 canonical 평가자
- **검사:** L2 + DB 부분 유니크 인덱스
- **활성:** M2 · **상태:** 문서화됨

### INV-05 — Snapshot 재구축 가능성

- **진술:** Snapshot은 Canonical Memory만으로 완전히 재구축된다.
- **근거:** baseline §33-7, §37 projection 평가자
- **검사:** L2 — snapshot을 버리고 재구축한 뒤 diff가 비어 있는지 확인
- **활성:** M2 · **상태:** 문서화됨

### INV-06 — 시간 순서 보장

- **진술:** `(subject_id, slot_id)` 단위로 canonicalization이 직렬화되며,
  `observed_at` 순서대로 처리된다.
- **근거:** review C-1. 재시도로 인한 역순 처리가 시간 체인을 거꾸로 돌린다.
  bootstrap에서는 역순 도착이 예외가 아니라 기본 동작이다
- **검사:** L2 — 늦은 관측을 먼저 처리시킨 뒤 이른 관측을 넣고,
  결과가 시간 역행하지 않는지 확인
- **활성:** M2 · **상태:** 문서화됨

### INV-08 — 망각의 완전성

- **진술:** FORGET 이후 canonical, observation, embedding, snapshot,
  raw chunk, pending job 어느 경로로도 해당 정보가 재출현하지 않는다.
- **근거:** baseline §24, §37 프라이버시 평가자, 요구사항 R4
- **검사:** L2 — 모든 검색 경로를 순회하며 매직 스트링 부재를 확인
- **활성:** M6 · **상태:** 문서화됨

### ~~INV-10~~ — Async 우선 (폐기)

- **진술(당시):** Async-required 조건이 Fast eligibility를 항상 이긴다.
- **폐기 사유:** review A-1이 MVP에서 Fast path를 제거했다.
- **보존 이유:** Fast path가 부활하면 이 항목이 근거가 된다.
  Deferred Alternatives를 버리지 않는다는 원칙이 불변식에도 적용된다.
```

- [ ] **Step 2: 실패하는 테스트 작성 — 로그 캡처**

`src/test/java/com/overmind/support/LogCaptureTest.java`:

```java
package com.overmind.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L1. INV-02 검사 도구 자체를 검증한다.
 *
 * <p>누출을 잡지 못하는 검사 도구는 검사하지 않는 것보다 나쁘다. 통과했다는
 * 잘못된 신호를 주기 때문이다.
 */
class LogCaptureTest {

    private static final Logger log = LoggerFactory.getLogger(LogCaptureTest.class);

    @Test
    void detects_a_leaked_payload() {
        try (LogCapture capture = LogCapture.start()) {
            log.info("사용자 발화를 그대로 로그에 남긴다: {}", "MAGIC-LEAK-1");

            assertThatThrownBy(() -> capture.assertNoOccurrenceOf("MAGIC-LEAK-1"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("MAGIC-LEAK-1");
        }
    }

    @Test
    void passes_when_only_metadata_is_logged() {
        try (LogCapture capture = LogCapture.start()) {
            log.info("observation 저장 완료 id={} count={}", 42L, 3);

            capture.assertNoOccurrenceOf("MAGIC-LEAK-1");
        }
    }

    @Test
    void collects_rendered_lines() {
        try (LogCapture capture = LogCapture.start()) {
            log.warn("pending job {}건", 7);

            assertThat(capture.lines()).anyMatch(line -> line.contains("pending job 7건"));
        }
    }
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

Run: `./gradlew test --tests "com.overmind.support.LogCaptureTest"`
Expected: FAIL — `cannot find symbol: class LogCapture`

- [ ] **Step 4: LogCapture 구현**

`src/test/java/com/overmind/support/LogCapture.java`:

```java
package com.overmind.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;

/**
 * INV-02 검사 도구. 루트 로거에 붙어 실행 중 발생한 로그를 모은다.
 *
 * <p>사용법:
 *
 * <pre>{@code
 * try (LogCapture capture = LogCapture.start()) {
 *     rememberMemoryService.handle(요청);
 *     capture.assertNoOccurrenceOf("사용자가 실제로 말한 문장", "canonical 값");
 * }
 * }</pre>
 */
public final class LogCapture implements AutoCloseable {

    private final ch.qos.logback.classic.Logger rootLogger;
    private final ListAppender<ILoggingEvent> appender;

    private LogCapture(ch.qos.logback.classic.Logger rootLogger, ListAppender<ILoggingEvent> appender) {
        this.rootLogger = rootLogger;
        this.appender = appender;
    }

    public static LogCapture start() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();

        rootLogger.addAppender(appender);
        rootLogger.setLevel(Level.TRACE);
        return new LogCapture(rootLogger, appender);
    }

    /** 캡처된 로그를 렌더링된 문자열로 돌려준다. 예외 스택트레이스 메시지도 포함한다. */
    public List<String> lines() {
        return appender.list.stream()
                .flatMap(
                        event ->
                                Stream.concat(
                                        Stream.of(event.getFormattedMessage()),
                                        event.getThrowableProxy() == null
                                                ? Stream.empty()
                                                : Stream.of(event.getThrowableProxy().getMessage())))
                .toList();
    }

    /** 주어진 문자열이 어느 로그 줄에도 나타나지 않아야 한다. */
    public void assertNoOccurrenceOf(String... forbidden) {
        List<String> lines = lines();
        for (String secret : forbidden) {
            for (String line : lines) {
                if (line != null && line.contains(secret)) {
                    throw new AssertionError(
                            "INV-02 위반: 로그에 민감 값이 나타났습니다 — \""
                                    + secret
                                    + "\""
                                    + System.lineSeparator()
                                    + "  로그 줄: "
                                    + line);
                }
            }
        }
    }

    @Override
    public void close() {
        rootLogger.detachAppender(appender);
        appender.stop();
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew verify`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add docs/harness/60-invariants.md src/test/java/com/overmind/support/LogCapture.java src/test/java/com/overmind/support/LogCaptureTest.java
git commit -m "feat: 불변식 카탈로그와 INV-02 로그 캡처 도구

- 60-invariants.md: INV-01~09 + 폐기된 INV-10. 각 항목에 활성 마일스톤
- LogCapture: 루트 로거를 캡처해 민감 값 누출을 잡는다
- 검사 도구 자체를 먼저 테스트했다. 누출을 못 잡는 검사는 통과했다는 거짓 신호를 준다"
```

---

## Task 9: 가드레일 검사

**Files:**
- Create: `docs/harness/migration-checksums.txt`
- Modify: `build.gradle.kts` (updateMigrationChecksums 태스크 추가)
- Test: `src/test/java/com/overmind/guardrail/DocLineLimitGuardTest.java`
- Test: `src/test/java/com/overmind/guardrail/DdlAutoGuardTest.java`
- Test: `src/test/java/com/overmind/guardrail/MigrationChecksumGuardTest.java`
- Test: `src/test/java/com/overmind/guardrail/LogUpdatedGuardTest.java`

**Interfaces:**
- Consumes: Task 3의 `AGENTS.md`/`CLAUDE.md`, Task 4의 `application.yml`과 `guardrailTest` 태스크,
  Task 6의 `src/main/resources/db/migration/`
- Produces: `./gradlew guardrails` 게이트. 시스템 프로퍼티 `overmind.guardrail.baseRef` 소비

- [ ] **Step 1: 실패하는 테스트 작성 — 문서 줄 수**

`src/test/java/com/overmind/guardrail/DocLineLimitGuardTest.java`:

```java
package com.overmind.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 진입 문서에 규약 본문이 흘러들어오는 것을 막는다.
 *
 * <p>"두 문서가 일치하는가"는 기계적으로 검사할 수 없다. 대신 길이를 막으면
 * 복제가 시작되는 순간 걸린다.
 */
@Tag("guardrail")
class DocLineLimitGuardTest {

    @Test
    void agents_md_stays_a_routing_table() throws IOException {
        assertLineLimit(Path.of("AGENTS.md"), 120);
    }

    @Test
    void claude_md_stays_a_pointer() throws IOException {
        assertLineLimit(Path.of("CLAUDE.md"), 40);
    }

    private void assertLineLimit(Path file, int limit) throws IOException {
        assertThat(file).exists();
        long lines = Files.readAllLines(file).size();
        assertThat(lines)
                .as(
                        "%s는 %d줄을 넘길 수 없습니다. 규약 본문은 docs/harness/로 옮기세요",
                        file, limit)
                .isLessThanOrEqualTo(limit);
    }
}
```

- [ ] **Step 2: 테스트 실행 — 통과 확인 (이미 상한 이내)**

Run: `./gradlew guardrailTest --tests "com.overmind.guardrail.DocLineLimitGuardTest"`
Expected: PASS

이 검사가 실제로 잡는지 확인한다. 임시로 `CLAUDE.md` 끝에 빈 줄 30개를 넣고 다시 실행:

```bash
for i in $(seq 1 30); do echo "" >> CLAUDE.md; done
./gradlew guardrailTest --tests "com.overmind.guardrail.DocLineLimitGuardTest"
```

Expected: FAIL — `CLAUDE.md는 40줄을 넘길 수 없습니다`

되돌린다: `git checkout CLAUDE.md`

- [ ] **Step 3: ddl-auto 가드 작성**

`src/test/java/com/overmind/guardrail/DdlAutoGuardTest.java`:

```java
package com.overmind.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 스키마 변경 경로를 Flyway 하나로 묶는다.
 *
 * <p>ddl-auto가 update나 create가 되면 스키마가 마이그레이션 밖에서 바뀌고,
 * 그 순간 마이그레이션 해시 가드가 무의미해진다.
 */
@Tag("guardrail")
class DdlAutoGuardTest {

    private static final Pattern DDL_AUTO = Pattern.compile("ddl-auto:\\s*(\\S+)");

    @Test
    void ddl_auto_is_validate() throws IOException {
        Path config = Path.of("src/main/resources/application.yml");
        assertThat(config).exists();

        String content = Files.readString(config);
        Matcher matcher = DDL_AUTO.matcher(content);

        assertThat(matcher.find())
                .as("application.yml에 ddl-auto 설정이 있어야 합니다")
                .isTrue();
        assertThat(matcher.group(1))
                .as("스키마는 Flyway만 바꾼다. ddl-auto는 validate 고정입니다")
                .isEqualTo("validate");
    }
}
```

- [ ] **Step 4: 마이그레이션 해시 가드 작성**

`src/test/java/com/overmind/guardrail/MigrationChecksumGuardTest.java`:

```java
package com.overmind.guardrail;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Flyway는 forward-only다. 이미 커밋된 마이그레이션을 고치면
 * 이미 적용된 환경과 새 환경의 스키마가 갈라진다.
 */
@Tag("guardrail")
class MigrationChecksumGuardTest {

    static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    static final Path CHECKSUM_FILE = Path.of("docs/harness/migration-checksums.txt");

    @Test
    void committed_migrations_are_unchanged() throws IOException {
        Map<String, String> actual = currentChecksums();
        Map<String, String> recorded = recordedChecksums();

        assertThat(actual)
                .as(
                        "마이그레이션이 변경되었습니다. 기존 파일을 고치지 말고 새 버전을 추가하세요. "
                                + "새 파일을 추가한 경우에만 ./gradlew updateMigrationChecksums 를 실행합니다")
                .isEqualTo(recorded);
    }

    static Map<String, String> currentChecksums() throws IOException {
        if (!Files.isDirectory(MIGRATION_DIR)) {
            return Map.of();
        }
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .collect(
                            toMap(
                                    p -> p.getFileName().toString(),
                                    MigrationChecksumGuardTest::sha256,
                                    (a, b) -> a,
                                    LinkedHashMap::new));
        }
    }

    static Map<String, String> recordedChecksums() throws IOException {
        Map<String, String> recorded = new LinkedHashMap<>();
        if (!Files.exists(CHECKSUM_FILE)) {
            return recorded;
        }
        for (String line : Files.readAllLines(CHECKSUM_FILE)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            recorded.put(parts[0], parts[1]);
        }
        return recorded;
    }

    static String sha256(Path file) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(Files.readString(file).replace("\r\n", "\n")
                                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("해시 계산 실패: " + file, e);
        }
    }
}
```

- [ ] **Step 5: 테스트 실행 — 실패 확인**

Run: `./gradlew guardrailTest --tests "com.overmind.guardrail.MigrationChecksumGuardTest"`
Expected: FAIL — `V1__enable_pgvector.sql` 항목이 실제에는 있고 기록에는 없다

- [ ] **Step 6: 체크섬 갱신 태스크를 build.gradle.kts에 추가**

`build.gradle.kts`의 `gitleaksScan` 정의 아래에 추가한다:

```kotlin
tasks.register("updateMigrationChecksums") {
    group = "verification"
    description = "새 마이그레이션을 추가한 뒤 해시 기록을 갱신한다"
    doLast {
        val migrationDir = file("src/main/resources/db/migration")
        val target = file("docs/harness/migration-checksums.txt")
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        val lines = mutableListOf(
            "# Flyway 마이그레이션 해시. forward-only 강제용.",
            "# 새 파일을 추가했을 때만 ./gradlew updateMigrationChecksums 로 갱신한다.",
            "# 기존 파일을 고치고 갱신하는 것은 가드레일 우회다."
        )
        if (migrationDir.isDirectory) {
            migrationDir.listFiles { f -> f.name.endsWith(".sql") }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    digest.reset()
                    val normalized = f.readText().replace("\r\n", "\n")
                    val hex = digest.digest(normalized.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    lines += "${f.name} $hex"
                }
        }
        target.parentFile.mkdirs()
        target.writeText(lines.joinToString("\n") + "\n")
        logger.lifecycle("[guardrails] ${target.path} 갱신 완료")
    }
}
```

- [ ] **Step 7: 체크섬 생성하고 통과 확인**

Run: `./gradlew updateMigrationChecksums && ./gradlew guardrailTest --tests "com.overmind.guardrail.MigrationChecksumGuardTest"`
Expected: PASS. `docs/harness/migration-checksums.txt`에 `V1__enable_pgvector.sql <해시>` 한 줄이 생긴다

- [ ] **Step 8: log.md 동반 변경 가드 작성**

`src/test/java/com/overmind/guardrail/LogUpdatedGuardTest.java`:

```java
package com.overmind.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * src/ 를 고쳤으면 log.md도 같이 고쳐야 한다.
 *
 * <p>이 게이트를 CI에 둔 이유는 도구 중립성이다. Claude Code hooks는 Claude Code에서만
 * 돌지만, CI는 Codex든 웹 LLM이든 사람이든 똑같이 걸린다.
 *
 * <p>base ref를 찾을 수 없는 로컬 환경에서는 검사를 건너뛴다. 진짜 게이트는 CI다.
 */
@Tag("guardrail")
class LogUpdatedGuardTest {

    @Test
    void source_changes_come_with_a_log_update() {
        String baseRef = System.getProperty("overmind.guardrail.baseRef", "origin/master");

        assumeTrue(
                git("rev-parse", "--verify", baseRef).exitCode() == 0,
                "base ref '" + baseRef + "' 를 찾을 수 없어 검사를 건너뜁니다 (CI에서는 항상 존재)");

        GitResult diff = git("diff", "--name-only", baseRef + "...HEAD");
        assumeTrue(diff.exitCode() == 0, "git diff 실패 — 검사를 건너뜁니다");

        List<String> changed = diff.lines();
        boolean touchedSource = changed.stream().anyMatch(path -> path.startsWith("src/"));
        boolean touchedLog = changed.contains("log.md");

        if (!touchedSource) {
            return;
        }

        assertThat(touchedLog)
                .as(
                        "src/ 를 변경했으면 log.md도 갱신해야 합니다. "
                                + "HEAD 블록을 덮어쓰고 세션 기록을 추가하세요. 변경된 파일: %s",
                        changed)
                .isTrue();
    }

    private static GitResult git(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }
            }
            return new GitResult(process.waitFor(), lines);
        } catch (IOException | InterruptedException e) {
            return new GitResult(-1, List.of());
        }
    }

    private record GitResult(int exitCode, List<String> lines) {}
}
```

- [ ] **Step 9: 가드레일 전체 실행**

Run: `./gradlew guardrails`
Expected: BUILD SUCCESSFUL. gitleaks가 없으면 경고 한 줄이 나오고 넘어간다

- [ ] **Step 10: log.md 갱신 후 커밋**

`log.md` HEAD의 "다음 할 일"을 Task 10으로 바꾸고 세션 기록을 추가한 뒤:

```bash
git add build.gradle.kts docs/harness/migration-checksums.txt src/test/java/com/overmind/guardrail log.md
git commit -m "feat: 가드레일 검사 4종

- 문서 줄 수 상한: 규약 복제가 시작되는 순간 걸린다
- ddl-auto validate 고정: 스키마 변경 경로를 Flyway 하나로 묶는다
- 마이그레이션 해시: forward-only 강제
- log.md 동반 변경: CI에 두어 도구 중립적으로 강제한다"
```

---

## Task 10: GitHub Actions 워크플로

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: Task 4의 `verify`/`guardrails`/`evaluationTest` 태스크,
  Task 9의 `overmind.guardrail.baseRef` 프로퍼티
- Produces: PR을 막는 `verify`·`guardrails` 잡, PR을 막지 않는 `evaluation` 잡

- [ ] **Step 1: 워크플로 작성**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  pull_request:
  push:
    branches: [master]
  schedule:
    # 매일 03:00 KST (18:00 UTC) — L3 평가
    - cron: '0 18 * * *'
  workflow_dispatch:

jobs:
  verify:
    name: verify (기계 게이트)
    if: github.event_name != 'schedule'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v4

      # compile + L1 + ArchUnit + L2(Testcontainers). ubuntu 러너에는 Docker가 있다.
      - run: ./gradlew verify

  guardrails:
    name: guardrails
    if: github.event_name != 'schedule'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v4

      - name: gitleaks
        uses: gitleaks/gitleaks-action@v2
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

      # PR이면 base 브랜치와, push면 직전 커밋과 비교한다.
      - name: guardrail 검사
        run: |
          if [ -n "${{ github.base_ref }}" ]; then
            BASE="origin/${{ github.base_ref }}"
          else
            BASE="HEAD~1"
          fi
          echo "base ref = $BASE"
          ./gradlew guardrailTest -PbaseRef="$BASE"

  evaluation:
    name: evaluation (L3)
    if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - uses: gradle/actions/setup-gradle@v4

      # 실제 LLM을 호출한다. 비용이 발생하므로 PR을 막지 않는다.
      - run: ./gradlew evaluationTest
        env:
          OVERMIND_LLM_API_KEY: ${{ secrets.OVERMIND_LLM_API_KEY }}
```

`guardrails` 잡이 `./gradlew guardrails` 대신 `guardrailTest`를 부르는 것은
gitleaks를 전용 액션으로 돌리기 때문이다. 두 단계를 합치면 CI가 실행하는 검사 집합은
로컬 `./gradlew guardrails`와 같다.

- [ ] **Step 2: YAML 문법 확인**

Run: `python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml',encoding='utf-8')); print('ok')"`
Expected: `ok`

python이 없으면 이 단계는 건너뛰고 Step 4의 실제 실행으로 검증한다.

- [ ] **Step 3: 로컬 게이트 최종 확인**

Run: `./gradlew verify guardrails`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋 후 푸시하여 CI 확인**

```bash
git add .github/workflows/ci.yml log.md
git commit -m "ci: verify / guardrails / evaluation 3잡 워크플로

- verify, guardrails는 PR을 막는다
- evaluation은 실 LLM 호출이므로 야간 스케줄과 수동 트리거만
- guardrail base ref는 PR이면 base 브랜치, push면 직전 커밋"
git push
```

GitHub Actions 탭에서 `verify`와 `guardrails`가 초록인지 확인한다.
Testcontainers가 러너에서 실패하면 `docs/harness/20-build-and-test.md`에 원인을 기록하고,
`log.md`의 "막힌 것"에 올린다.

---

## Task 11: 요구사항과 acceptance criteria

**Files:**
- Create: `docs/requirements/R1-R6.md`
- Delete: `docs/requirements/.gitkeep`

**Interfaces:**
- Consumes: `docs/arch/review-v0.1.md` §2
- Produces: R1~R6과 각 2개 이상의 AC. M0 이후 모든 태스크의 acceptance criteria가 여기서 파생된다.
  리뷰 프로토콜의 BLOCKING 1번("acceptance criteria 미충족")이 이 문서를 근거로 삼는다

- [ ] **Step 1: 요구사항 문서 작성**

`docs/requirements/R1-R6.md`:

```markdown
# 요구사항과 acceptance criteria

리뷰 문서 §2가 지적한 대로, baseline에는 요구사항 문장이 없고 전부 solution 형태였다.
이 문서가 요구사항의 단일 출처다. 모든 아키텍처 요소는 여기의 어느 항목에
기여하는지 설명 가능해야 한다.

AC는 실행 가능한 테스트로 환산된다. "canonical correctness"는 테스트가 아니다.

**활성 마일스톤** 열은 그 AC를 실제 테스트로 구현하는 시점이다.

---

## R1 — 새 대화에서 상황을 다시 설명하지 않는다

새 대화를 시작할 때 직무, 현재 학습 주제, 진행 중 프로젝트를 다시 말하지 않아도 된다.

    R1-AC1  (M2)
    given  canonical: profile.role = "백엔드 개발자" (ACTIVE)
           canonical: learning.primary_focus = "CKAD" (ACTIVE)
    when   recall_memory(mode=CURRENT)
    then   반환된 context pack에 두 값이 모두 포함된다

    R1-AC2  (M2)
    given  위와 같은 canonical 상태
    when   새 대화의 첫 턴에서 CURRENT를 1회 호출
    then   Core + 관련 scope가 한 번의 응답으로 반환된다
           (MCP 왕복이 2회 이상이면 실패)

## R2 — 접은 관심사가 현재 사실로 섞이지 않는다

6개월 전에 접은 관심사가 "현재 관심사"로 답변에 섞이지 않는다.

    R2-AC1  (M2)
    given  canonical: learning.primary_focus = "Kafka" (valid_until = 6개월 전)
           canonical: learning.primary_focus = "CKAD"  (valid_from  = 1개월 전)
    when   recall_memory(mode=CURRENT, scope=learning)
    then   응답에 "Kafka"가 현재 사실로 포함되지 않는다

    R2-AC2  (M2, review C-4)
    given  canonical 한 건의 valid_until = 2026-06-30
           2026-06-30 이후 어떤 쓰기 이벤트도 발생하지 않음
    when   2026-07-01 시점에 recall_memory(mode=CURRENT)
    then   해당 사실이 current로 반환되지 않는다
           (snapshot version이 변하지 않았더라도)

## R3 — 명시하지 않아도 지속 사실은 남는다

"기억해"라고 말하지 않아도 지속적으로 유효한 사실은 남는다.

    R3-AC1  (M2)
    given  사용자가 "요즘 CKAD 준비 중이야"라고만 말함 (저장 요청 없음)
    when   대화 처리 후 canonicalization이 RESOLVED로 종료
    then   canonical: learning.primary_focus = "CKAD" 가 ACTIVE로 존재한다

    R3-AC2  (M2)
    given  사용자가 "오늘 점심 뭐 먹지"라고 말함
    when   동일 파이프라인을 통과
    then   canonical_memory에 durable 레코드가 생기지 않는다
           (observation은 생겨도 무방하다)

## R4 — 잊어달라고 한 정보는 어떤 경로로도 나오지 않는다

    R4-AC1  (M6)
    given  forget_memory("일본 여행") 실행 완료
    when   recall_memory(mode=HISTORICAL, query="여행")
           그리고 raw fallback 검색까지 도달
    then   일본 여행 관련 문자열이 응답 어디에도 없다

    R4-AC2  (M6)
    given  forget_memory 실행 완료
    when   canonical / observation / embedding / snapshot / raw chunk /
           pending job 전 경로를 직접 조회
    then   삭제 대상 문자열이 어느 저장소에도 남아 있지 않다
           (deletion_audit에는 메타데이터만 남는다)

## R5 — Claude에서 말한 것이 ChatGPT에도 반영된다

    R5-AC1  (M2)
    given  클라이언트 A가 remember_memory로 learning.primary_focus = "CKAD" 기록
           canonicalization job이 RESOLVED로 종료
    when   클라이언트 B가 recall_memory(mode=CURRENT)
    then   응답에 "CKAD"가 포함된다

    R5-AC2  (M3, review C-6)
    given  클라이언트 A와 B가 같은 SINGLE 슬롯에 서로 다른 값을 기록
    when   두 canonicalization job이 모두 종료
    then   한쪽 값이 임의로 선택되지 않고 UNRESOLVED_CONFLICT로 남으며,
           recall_memory(mode=CURRENT) 응답에 미해결 건수가 포함된다

## R6 — 콜드 스타트 없이 시작한다

    R6-AC1  (M5)
    given  최근 6개월치 Claude/ChatGPT export 파일
    when   bootstrap 실행
    then   registered slot 기준으로 canonical memory가 생성되고,
           사전 설정한 LLM 호출 수 / 토큰 / 금액 상한을 초과하지 않고 종료한다
           (상한 초과 시 중단하고 진행 상황을 보고한다)

    R6-AC2  (M5)
    given  bootstrap을 완료한 뒤 동일 export로 다시 실행
    when   idempotency_key가 적용된 상태
    then   observation 중복 적재가 0건이다
```

- [ ] **Step 2: .gitkeep 제거**

```bash
rm docs/requirements/.gitkeep
```

- [ ] **Step 3: 전체 게이트 확인**

Run: `./gradlew verify guardrails`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: log.md 마무리**

HEAD 블록을 하네스 완료 상태로 갱신한다:

```markdown
## 현재 상태

- **마일스톤:** H 완료 → M0 착수 대기
- **최근 갱신:** <오늘 날짜> · <도구>
- **브랜치:** master
- **verify:** 통과

### 진행 중

- 없음

### 다음 할 일

1. M0 도메인 브레인스토밍 (`superpowers:brainstorming`)
   — review §4의 A-1~A-4를 결정한다
2. M0 스펙 작성 후 별도 플랜

### 열려 있는 결정

- `docs/arch/decisions.md`의 "열려 있음" 표 참조

### 막힌 것

- 없음
```

세션 기록도 추가한다.

- [ ] **Step 5: 커밋**

```bash
git add docs/requirements log.md
git commit -m "docs: 요구사항 R1~R6과 acceptance criteria

- 각 요구사항에 실행 가능한 AC 2개씩. given/when/then 형식
- 각 AC에 활성 마일스톤 표기
- 리뷰 프로토콜의 BLOCKING 1번이 이 문서를 근거로 삼는다"
git push
```

---

## 완료 조건

하네스가 섰다고 말할 수 있는 조건:

- [ ] `./gradlew verify` 통과
- [ ] `./gradlew guardrails` 통과
- [ ] GitHub Actions의 `verify`, `guardrails` 잡이 초록
- [ ] `AGENTS.md`만 읽고도 다른 도구(Codex)가 세션을 시작할 수 있다
- [ ] `log.md` HEAD 블록에 "다음 할 일"이 적혀 있다
- [ ] `docs/harness/60-invariants.md`에 INV-01~09가 활성 마일스톤과 함께 있다
- [ ] `docs/requirements/R1-R6.md`에 R1~R6과 AC 12개가 있다

이후 M0 도메인 브레인스토밍으로 넘어간다. 이 플랜은 도메인 코드를 만들지 않는다.
