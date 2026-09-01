# OverMind 개발 하네스 — 설계 명세 v1.0

- **작성일:** 2026-09-01
- **범위:** OverMind 프로젝트의 개발 하네스(Harness Engineering) + 루프(Loop Engineering) + 테스트 전략 + 협업 규약
- **비범위:** 메모리 도메인 자체의 설계. M0 이후 도메인 스펙은 별도 브레인스토밍 → 별도 스펙 → 별도 플랜 사이클로 진행한다.
- **선행 문서:** `docs/arch/baseline-v0.1.md` (구 `gptsol-plan.md`), `docs/arch/review-v0.1.md` (구 `opus-review.md`)

---

## 0. 이 문서가 존재하는 이유

OverMind는 Claude Code, Codex, 그리고 여러 LLM 클라이언트를 넘나들며 개발된다.
세션은 매번 소멸하고 도구마다 컨텍스트가 다르다.

따라서 코드보다 먼저 확정되어야 하는 것이 있다.

1. 어느 도구로 들어와도 같은 규약을 읽게 만드는 **문서 계층**
2. 이전 세션이 어디까지 했는지 알려주는 **작업 로그**
3. 작업이 끝났음을 기계가 판정하는 **루프와 게이트**
4. LLM이 파이프라인 한가운데 있는 시스템의 **테스트 경계**

이 문서는 그 네 가지를 확정한다.

---

## 1. 확정된 선행 결정

이 설계는 아래 여섯 가지 결정 위에 서 있다. 변경하면 이 문서의 상당 부분을 재작성해야 한다.

### D-A. 두 선행 문서의 권위 관계 — 절충

- 아키텍처 **지향점**은 `baseline-v0.1.md`를 유지한다.
- 실행 **순서**는 `review-v0.1.md` §8의 마일스톤 M0~M6을 따른다.
- 리뷰 §6의 결함 C-1~C-7은 논쟁 대상이 아니라 반영 대상이다.
- 하네스는 불변식 **전체**를 카탈로그로 들고 있되, 각 평가자는 해당 마일스톤에 도달할 때 활성화한다.

### D-B. 스택 — Java 21 + Spring Boot 3

`baseline-v0.1.md` §32의 Spring Boot metrics 전제와 일치한다.
결정적 근거는 **ArchUnit**이다. §37이 요구한 "아키텍처 평가자"(프로바이더 개념이 코어 도메인에 누출되지 않을 것)를 테스트로 강제하는 도구가 이 생태계에서 가장 강력하다.
부수 도구: Gradle, JUnit 5, Testcontainers, Flyway, ArchUnit.

**대가로 인정하는 것:** LLM/임베딩 라이브러리 생태계가 Python보다 얇고, MCP 서버를 직접 구현해야 한다.

### D-C. log.md — 2단 단일 파일

상단 HEAD 블록(항상 덮어쓰기) + 하단 append-only 세션 기록. 상세는 §4.

### D-D. 루프 게이트 — 기계는 자동 반송, 리뷰는 교차 검증

기계 게이트 실패는 구현 에이전트로 자동 반송한다.
리뷰 게이트는 구현하지 않은 도구가 수행하고, 결과는 사람이 최종 판단한다. 상세는 §5.

### D-E. 테스트 — 3계층 분리

L1 단위(fake) / L2 통합(Testcontainers + 녹화 재생) / L3 평가(실 LLM). 상세는 §6.

### D-F. 하네스 구축 범위 — Walking Skeleton

지금 짓는 것은 M0부터 M6까지 **전 구간에서 공통으로 쓰이는 것만**이다.
`baseline-v0.1.md` §37이 요구한 평가자 8종은 카탈로그로 전부 확정하되, 실행 가능한 코드는 검사 대상이 생기는 마일스톤에 구현한다.

**근거:** 리뷰 §3의 bake-off가 M2에 있고, 지면 canonical 파이프라인의 절반이 폐기된다. Canonical invariant / Routing / Projection 평가자는 정확히 그 폐기 대상 위에 세워진다. 반면 log.md·AGENTS.md·verify·CI·로그 가드레일은 bake-off 결과와 무관하게 M0부터 M6까지 전부 쓰인다.

**이 선택의 위험과 완화:** 평가자를 나중에 붙일 때 코드가 이미 검사 불가능한 형태로 굳어 있을 수 있다. 이를 막기 위해 **불변식 카탈로그를 코드보다 먼저** 작성한다(§7). 무엇을 검사할지 알고 짜면 검사 가능한 형태로 짜게 된다.

---

## 2. 저장소 구조

### 2.1 모듈 구조 — 단일 Gradle 모듈 + 패키지 경계 + ArchUnit

`baseline-v0.1.md` §34의 4계층을 Gradle 멀티모듈로 쪼개면 의존 방향 위반이 컴파일 에러가 되어 가장 강력하다. 그러나 M0는 `observation` 테이블 하나와 MCP 툴 2개가 전부이므로, 모듈 5개는 빌드 설정 비용만 내고 이득이 없다.

**MVP 결정:** 단일 모듈 + 패키지 경계 + ArchUnit 강제.
**트레이드오프:** 컴파일 타임 강제 대신 테스트 타임 강제. 대신 경계를 옮기는 비용이 0이다.
**Post-MVP 업그레이드 트리거:** 워커를 애플리케이션과 독립적으로 배포해야 할 때 모듈을 분리한다.

```
com.overmind
├─ domain/          순수 도메인. Spring·JPA 의존 금지
├─ application/     유스케이스 + port/ (LlmPort, EmbeddingPort …)
├─ adapter/
│  ├─ in/  mcp/, http/
│  └─ out/ persistence/, llm/, embedding/
└─ config/
```

### 2.2 M0부터 활성화하는 ArchUnit 규칙

| # | 규칙 |
|---|---|
| AR-1 | `domain`은 `org.springframework`, `jakarta.persistence`, `com.overmind.adapter`를 참조하지 않는다 |
| AR-2 | `application`은 `com.overmind.adapter`를 참조하지 않는다 |
| AR-3 | LLM/임베딩 SDK 클래스는 `com.overmind.adapter.out` 밖에서 참조되지 않는다 |
| AR-4 | 프로바이더 고유명(`Claude`, `ChatGPT`, `OpenAI`, `Anthropic`, `Gemini`)이 `domain`·`application`의 타입명·식별자·문자열 리터럴에 등장하지 않는다 |

AR-4가 INV-01(§7)의 구현체다. 검사 대상 코드가 아직 없어도 규칙은 지금 켤 수 있다.

### 2.3 파일 배치

```
AGENTS.md      단일 소스 진입점 (상한 120줄). Codex 및 기타 도구가 읽는다
CLAUDE.md      "AGENTS.md를 읽어라" + Claude Code 전용 사항만 (상한 40줄)
log.md         작업 로그 (§4)
docs/
├─ harness/
│  ├─ 00-start-here.md      세션 시작 절차 (필독)
│  ├─ 10-repo-map.md        어디에 무엇이 있는가
│  ├─ 20-build-and-test.md  모든 커맨드
│  ├─ 30-loop.md            루프 규약 (§5)
│  ├─ 40-guardrails.md      로그·시크릿·마이그레이션·파괴적 작업 (§8)
│  ├─ 50-review-protocol.md 교차 검증 프로토콜 (§5.4)
│  └─ 60-invariants.md      불변식 카탈로그 (§7)
├─ arch/
│  ├─ baseline-v0.1.md      ← gptsol-plan.md 이동 (읽기 전용 사료)
│  ├─ review-v0.1.md        ← opus-review.md 이동 (읽기 전용 사료)
│  ├─ decisions.md          확정 결정 레지스터
│  └─ adr/NNNN-*.md
├─ requirements/
│  └─ R1-R6.md              요구사항 + acceptance criteria (리뷰 §2)
├─ eval/                    L3 평가 결과 기록 (§6.4)
├─ log-archive/             마일스톤별로 잘라낸 세션 기록 (§4.2)
└─ superpowers/specs/       이 문서를 포함한 스펙
```

**기존 파일 이동:** 현재 루트의 `gptsol-plan.md`, `opus-review.md`를 위 경로로 옮기고 개명한다. 루트에는 진입 파일만 남긴다.

---

## 3. 에이전트 문서 계층

### 3.1 원칙 — 얇은 진입점, 단일 실체

여러 도구를 넘나들 때 실패하는 지점은 항상 **문서 복제 후 동기화 실패**다. `CLAUDE.md`와 `AGENTS.md`에 같은 규약을 두 벌 쓰면 반드시 갈라진다.

따라서 규약의 실체는 `docs/harness/`에 한 벌만 둔다. 진입 파일은 규약을 담지 않고 **라우팅 테이블**만 담는다 — "무엇을 할 때 무엇을 읽어라".

부수 효과로 컨텍스트 예산도 지켜진다. 매 세션 전체 규약을 읽히면 토큰을 낭비하고, 긴 문서일수록 지켜지지 않는다.

### 3.2 AGENTS.md 구성 (상한 120줄)

1. 프로젝트 1줄 정의
2. **세션 시작 절차** — `log.md` HEAD 블록 읽기 → 현재 마일스톤 확인 → 해당 태스크 스펙 읽기
3. **절대 규칙 5개** — 읽지 않고 넘어가면 안 되는 것 (§8.2 금지 목록 요약)
4. **라우팅 테이블** — 상황별로 읽어야 할 `docs/harness/*.md`
5. **세션 종료 절차** — `log.md` 갱신 방법 (§4.1)

### 3.3 CLAUDE.md 구성 (상한 40줄)

`AGENTS.md`로의 포인터 + Claude Code 전용 사항(스킬 사용 규약, 서브에이전트 사용 지침)만.

**동기화 강제:** "두 문서가 일치하는가"는 기계적으로 검사할 수 없다. 대신 **`CLAUDE.md` 40줄 상한을 CI로 검사**한다. 규약 내용이 흘러들어가면 길이로 잡힌다.

---

## 4. log.md 규약

### 4.1 형식

```markdown
# OverMind 작업 로그

<!-- ===== HEAD — 항상 최신으로 덮어쓴다 ===== -->
## 현재 상태
- **마일스톤:** M0 — observation 테이블 + remember/recall MCP 툴
- **최근 갱신:** 2026-09-01 21:40 · Claude Code
- **브랜치:** feat/m0-observation
- **verify:** 통과 (2026-09-01 21:40)

### 진행 중
- [ ] T-003 ObservationRepository 통합 테스트 — 절반. Testcontainers는 뜨는데 pgvector 확장이 안 올라옴

### 다음 할 일
1. T-003 마무리
2. T-004 remember_memory 핸들러

### 열려 있는 결정
- (D-01) idempotency_key 시간버킷 폭 — 리뷰 A-3. T-002에서 5분으로 임시 확정, 재검토 필요

### 막힌 것
- 없음

<!-- ===== 세션 기록 — append-only, 최신이 위 ===== -->
## 세션 기록

### 2026-09-01 21:40 · Claude Code · T-003 · a3f9c1e
- **한 일:** ObservationRepository 통합 테스트 골격 작성
- **결과:** verify 통과 / 리뷰 미실시
- **함정:** Testcontainers는 pgvector/pgvector:pg16 이미지를 써야 함. 기본 postgres 이미지에는 확장이 없음
- **다음:** T-003 나머지 케이스
```

세션 기록 항목의 필드는 5개로 고정한다: **제목줄(날짜·도구·태스크·커밋 SHA) / 한 일 / 결과 / 함정 / 다음**.

### 4.2 규약

| # | 규약 |
|---|---|
| L-1 | 세션 시작 시 **HEAD 블록만** 읽는다. 파일 전체가 아니다. 세션 기록은 필요할 때만 검색한다 |
| L-2 | 세션 종료 시 HEAD를 덮어쓰고 세션 기록 1개를 맨 위에 추가한다. 둘은 같은 커밋에 들어간다 |
| L-3 | 세션 기록은 **역순**(최신이 위)이다. 파일 앞부분만 읽어도 최근 맥락이 잡힌다 |
| L-4 | **git이 기록하는 것은 쓰지 않는다.** diff·파일 목록·커밋 메시지는 git에 있다. log.md에는 git이 기록하지 못하는 것만 쓴다 — 왜 그렇게 했는지, 시도했다 버린 것, 함정, 열려 있는 결정 |
| L-5 | 마일스톤 종료 시 세션 기록을 `docs/log-archive/M<N>.md`로 잘라내고 log.md에는 3줄 요약만 남긴다 |
| L-6 | 과거 세션 기록 항목은 편집하지 않는다(append-only). 정정이 필요하면 새 항목에 쓴다 |

L-5가 "단일 파일 유지"와 "무한 증식 방지"를 동시에 만족시키는 유일한 장치다.

### 4.3 강제

**CI 게이트:** `src/**`가 변경된 PR에서 `log.md`가 함께 변경되지 않으면 실패한다.

이 방식을 고른 이유는 **도구 중립성**이다. Claude Code hooks는 Claude Code에서만 돈다. CI는 Codex든 웹 LLM이든 사람이든 똑같이 걸린다.

로컬 pre-commit hook은 `--no-verify`로 우회되므로 편의 장치일 뿐이며, 진짜 게이트는 CI다.

---

## 5. 루프 엔지니어링

작업 단위는 태스크 `T-NNN` 하나다.

### 5.1 루프

```
[0] 세션 시작 — log.md HEAD + 태스크 스펙 읽기
     ↓
[1] 실패하는 테스트 작성 (TDD)
     ↓
[2] 구현  ←──────────────────┐
     ↓                       │ 자동 반송 (최대 3회)
[3] ./gradlew verify ────────┘
     compile → L1 → L2 → ArchUnit → 활성 불변식 평가자
     ↓ 통과
[4] 커밋 (태스크 단위, verify 통과 상태만)
     ↓
[5] 교차 리뷰 — 구현하지 않은 도구가 수행
     ↓
[6] 사람 판단 — BLOCKING 수용/기각
     ↓  (수용 시 [2]로, 기각 시 사유를 log.md에)
[7] log.md 갱신 → 태스크 종료
```

### 5.2 설계 판단

| # | 판단 | 근거 |
|---|---|---|
| P-1 | 자동 반송은 **[3]→[2] 구간에만** 존재한다 | 기계 게이트만 판정이 객관적이다. LLM 리뷰어의 "통과"는 자동 반송의 근거가 되기에 신뢰도가 부족하다 |
| P-2 | 반송 **상한 3회**. 초과하면 멈추고 log.md "막힌 것"에 기록한다 | 같은 실패를 3번 못 고치면 원인 가설이 틀린 것이다. 계속 시도하는 것보다 systematic-debugging으로 전환하는 편이 낫다 |
| P-3 | **커밋이 리뷰보다 앞선다** | 리뷰어에게 줄 diff가 필요하고, verify 통과 상태를 커밋으로 고정해야 반송 시 되돌아갈 지점이 생긴다 |
| P-4 | 교차 리뷰 담당은 **log.md 세션 기록의 도구 이름**으로 결정된다 | 같은 모델이 자기 코드를 리뷰하면 통과 편향이 생긴다. 누가 구현했는지가 로그에 있으므로 다음 세션이 누가 리뷰해야 하는지 안다 |

### 5.3 교차 검증 매핑

| 구현 도구 | 리뷰 도구 |
|---|---|
| Claude Code | `/codex` (codex review) |
| Codex | Claude Code 서브에이전트 |
| 기타 LLM | Claude Code 서브에이전트 또는 `/codex` 중 택1, 선택 결과를 log.md에 기록 |

### 5.4 리뷰 프로토콜 (`50-review-protocol.md`)

**리뷰어에게 주는 컨텍스트 — 이것만 준다:**

1. 태스크 스펙 (acceptance criteria 포함)
2. diff
3. 이 태스크에 걸리는 불변식 목록 (`60-invariants.md`에서 발췌)
4. 판정 형식

**판정 형식:** `BLOCKING` / `NON-BLOCKING` / `의견` 3분류.

**BLOCKING 목록 v1 — 4종으로 한정한다:**

1. acceptance criteria 미충족
2. 불변식 카탈로그 위반
3. 데이터 손실 또는 프라이버시 결함
4. 테스트가 이름과 달리 실제로는 아무것도 검증하지 않음

그 외는 전부 NON-BLOCKING 또는 의견이다. 스타일 선호는 BLOCKING이 될 수 없다.

**한정하는 이유:** LLM 리뷰어를 붙일 때 실제로 발생하는 문제는 놓친 버그가 아니라 **과잉 지적**이다. 스타일 선호가 BLOCKING으로 올라오면 루프가 진흙탕이 된다.

### 5.5 BLOCKING 목록 확장 절차

목록에 버전을 붙인다(`v1: 4종`). 확장 규칙:

1. 리뷰 중 "이건 막았어야 했다"는 사례가 나오면 log.md **열려 있는 결정**에 후보로 올린다.
2. 같은 유형이 **2회 반복**되면 목록에 추가한다. 1회는 우연일 수 있다.
3. 추가하기 전에 먼저 묻는다 — **기계 게이트로 만들 수 있는가.** 만들 수 있으면 BLOCKING 목록이 아니라 `verify`로 보낸다.

3번이 이 절차의 핵심이다. 사람 판단을 요구하는 게이트보다 자동 반송되는 게이트가 언제나 낫다.

좁게 시작해서 넓히는 것은 쉽지만, 넓게 시작하면 루프가 처음부터 막혀서 되돌리기 어렵다.

---

## 6. 테스트 전략

OverMind의 특수성은 파이프라인 한가운데에 LLM이 있다는 것이다(extraction, relation classification). 같은 입력에 다른 출력이 나오는 컴포넌트를 어느 테스트 경계에 두느냐가 피라미드 전체를 결정한다.

### 6.1 3계층

| | 이름 | 범위 | 외부 의존 | 실행 시점 |
|---|---|---|---|---|
| **L1** | unit | domain·application 순수 로직 | 없음. `LlmPort`/`EmbeddingPort`는 손으로 쓴 fake | 항상 (`verify`) |
| **L2** | integration | adapter + DB + 파이프라인 | Testcontainers pgvector + 녹화된 LLM 응답 재생 | 항상 (`verify`) |
| **L3** | evaluation | 골든셋 / bake-off | 실 LLM, 실 비용 | 태그 수동 + 야간 스케줄 |

### 6.2 L1 — 단위

- `@SpringBootTest` 사용 금지. 네이밍 규칙과 ArchUnit으로 강제한다.
- `LlmPort` fake는 시나리오별로 **손으로 쓴다**. 무작위성이 없다.

**금지하는 이유:** Spring 컨텍스트가 뜨는 순간 단위 테스트가 아니다. 초 단위 피드백이 깨지면 루프 [3]→[2] 반송이 실용성을 잃는다.

### 6.3 L2 — 통합

- Testcontainers `pgvector/pgvector:pg16`, 컨테이너 재사용(`withReuse`)으로 속도를 확보한다.
- **Flyway 마이그레이션을 실제로 돌린 스키마 위에서** 테스트한다. `ddl-auto`는 `validate`만 허용한다.
- LLM 응답은 `src/test/resources/llm-fixtures/<프롬프트버전>/<케이스>.json`에서 재생한다.
- 녹화 모드: `-Dovermind.llm.record=true`로 실행하면 실제 호출 후 픽스처를 갱신한다. **재녹화 diff는 리뷰 대상이다** — 프롬프트를 고쳤을 때 출력이 어떻게 달라졌는지가 그 diff에 그대로 드러난다.

**버전 삼중 연결:**

> 픽스처 디렉터리 이름 = 코드 안의 프롬프트 버전 상수 = 리뷰 A-4의 `observation.extractor_version`

셋이 어긋나면 L2가 실패한다. 프롬프트를 고치고 픽스처 재녹화를 잊는 것이 이 구조의 가장 흔한 실패인데, 이렇게 묶으면 기계가 잡는다.

### 6.4 L3 — 평가

- `@Tag("evaluation")`으로 `verify`에서 제외한다.
- 판정은 pass/fail이 아니라 **점수 + 임계값 + 직전 실행 대비 추세**다.
- 결과를 `docs/eval/YYYY-MM-DD-<milestone>.md`로 커밋해 추세가 git에 남게 한다.
- **비용 상한:** 실행 전 예상 호출 수와 예상 토큰을 출력하고, 상한을 넘으면 중단한다. 리뷰 B-3이 요구한 하드 비용 상한이 여기에 들어간다.
- 리뷰 §3의 bake-off(50문항: CURRENT 20 / HISTORICAL 20 / NONE 10)가 L3의 첫 번째 스위트다. M1에 구축한다.

---

## 7. 불변식 카탈로그 (`60-invariants.md`)

### 7.1 항목 형식

```
### INV-NN — <이름>
- 진술: <검증 가능한 한 문장>
- 근거: <baseline/review 참조>
- 검사 방법: <ArchUnit | L2 테스트 | 정적 스캔 | 수동>
- 활성 마일스톤: <M0~M6 | 폐기>
- 상태: <문서화됨 | 구현됨 | 폐기됨>
```

### 7.2 카탈로그

| ID | 불변식 | 출처 | 검사 방법 | 활성 |
|---|---|---|---|---|
| INV-01 | 프로바이더 개념이 코어 도메인에 누출되지 않는다 | baseline §37 아키텍처 평가자 | ArchUnit AR-4 | **M0** |
| INV-02 | 로그에 메모리 페이로드·대화 원문·canonical 값·토큰·Authorization 헤더가 나타나지 않는다 | baseline §37 로깅 평가자, §30 | ArchUnit + L2 로그 캡처 | **M0** |
| INV-09 | 동일 `idempotency_key`로 observation이 중복 적재되지 않는다 | review A-3 | L2 | **M0** |
| INV-07 | READ_INTENT=NONE 질의는 메모리 검색을 호출하지 않는다 | baseline §37 retrieval 평가자, §31 | L2 + L3 골든셋 | M1 |
| INV-03 | 일반 canonicalization은 observation을 변경하지 않는다. FORGET만 예외 | baseline §33-3, §37 observation 평가자, review A-2 | L2 (전후 체크섬 비교) | M2 |
| INV-04 | SINGLE 슬롯에 상호배타적인 current 사실이 공존할 수 없다 | baseline §33-6, §37 canonical 평가자 | L2 + DB 제약 | M2 |
| INV-05 | Snapshot은 Canonical Memory만으로 재구축 가능하다 | baseline §33-7, §37 projection 평가자 | L2 (재구축 → diff) | M2 |
| INV-06 | `(subject_id, slot_id)` 단위로 직렬화되며 `observed_at` 순서가 보장된다 | review C-1 | L2 (역순 도착 시나리오) | M2 |
| INV-08 | FORGET 이후 어떤 검색 경로로도 해당 정보가 재출현하지 않는다 | baseline §37 프라이버시 평가자, review R4 | L2 (전 경로 스윕) | M6 |
| ~~INV-10~~ | ~~Async-required 조건이 Fast eligibility를 항상 이긴다~~ | baseline §37 라우팅 평가자 | — | **폐기** |

**INV-10 폐기 사유:** review A-1이 MVP에서 Fast path를 제거했다. 항목을 삭제하지 않고 폐기 표시로 남긴다 — baseline의 "Deferred Alternatives를 버리지 말라"는 원칙이 불변식에도 적용된다. Fast path가 부활하면 이 항목이 근거가 된다.

### 7.3 M0 활성 불변식 3종

M0에 실제로 켜지는 것은 INV-01, INV-02, INV-09이며 셋 다 M0 코드에 검사 대상이 존재한다. 카탈로그가 빈 껍데기로 시작하지 않는다.

**INV-02 검사는 세 겹으로 건다:**

1. ArchUnit — `log.*` 호출 인자에 도메인 엔티티 타입이 직접 전달되면 실패
2. ArchUnit — 도메인 엔티티의 `toString()`이 민감 필드를 포함하면 실패
3. L2 — 로그 appender를 캡처해 시나리오 실행 후, 테스트가 심어둔 매직 스트링이 로그에 없는지 확인

3번이 실질적인 방어선이다. 1·2번은 흔한 실수를 값싸게 걸러내는 장치다.

---

## 8. 가드레일 (`40-guardrails.md`)

### 8.1 자동 검사

| 영역 | 규칙 | 검사 |
|---|---|---|
| 마이그레이션 | Flyway forward-only. 이미 적용된 `V*__*.sql`은 수정 불가 | 파일 해시 저장 후 CI 비교 |
| 마이그레이션 | `spring.jpa.hibernate.ddl-auto: validate` 고정 | 설정 파일 검사 |
| 시크릿 | `.env`는 git 무시. 테스트는 더미 키 | gitleaks CI 스캔 |
| 문서 | `CLAUDE.md` 40줄 상한, `AGENTS.md` 120줄 상한 | CI 줄 수 검사 |
| 로그 | `src/**` 변경 시 `log.md` 동반 변경 | CI diff 검사 |

### 8.2 금지 목록

- main/master 브랜치에 `git push --force`
- `git reset --hard`
- 마이그레이션 파일 밖에서의 `DROP` / `TRUNCATE`
- 프로덕션 DB 직접 접속
- `docs/arch/baseline-v0.1.md`, `docs/arch/review-v0.1.md` 수정 — 사료이므로 읽기 전용. 결정 변경은 `docs/arch/decisions.md`와 ADR에 기록한다
- `log.md` 과거 세션 기록 항목 편집 (L-6)

### 8.3 에이전트 권한 3단계

| 단계 | 대상 |
|---|---|
| **자동 허용** | 읽기·검색, `./gradlew` 실행, `src/**` 편집, `log.md` 편집 |
| **확인 필요** | 커밋, 푸시, PR 생성, 의존성 추가, 마이그레이션 파일 생성, 실 LLM 호출(L3 실행) |
| **금지** | §8.2 전체 |

---

## 9. CI

단일 워크플로, 3잡.

| # | 잡 | 내용 | PR 차단 | 트리거 |
|---|---|---|---|---|
| 1 | `verify` | compile + L1 + L2 + ArchUnit + 활성 불변식 | 예 | PR, push |
| 2 | `guardrails` | log.md 동반 변경, 문서 줄 수 상한, 마이그레이션 해시, gitleaks | 예 | PR, push |
| 3 | `evaluation` | L3 | 아니오 | 야간 스케줄 + 수동 |

### 9.1 로컬–CI 동치

`./gradlew verify`가 잡 1과, `./gradlew guardrails`가 잡 2와 **정확히 같은 것**을 실행한다.

**이것이 협상 불가능한 이유:** 에이전트가 로컬에서 CI 결과를 예측할 수 없으면 루프 [3]의 자동 반송이 성립하지 않는다. 통과했다고 커밋했는데 CI에서 깨지면 반송 지점이 사람에게 넘어가고, 그 순간 루프는 사람이 매번 개입하는 워크플로로 퇴화한다.

---

## 10. 하네스 구축 순서

이 스펙에서 `writing-plans`로 넘어갈 때의 태스크 순서다.

| 단계 | 산출물 |
|---|---|
| H-1 | 저장소 재배치 — `docs/` 구조 생성, baseline/review 이동·개명 |
| H-2 | `AGENTS.md`, `CLAUDE.md`, `docs/harness/00`~`50` 작성 |
| H-3 | `log.md` 초기화 (HEAD 블록 + 빈 세션 기록) |
| H-4 | Gradle + Spring Boot 3 스켈레톤, 패키지 구조, `./gradlew verify` 태스크 |
| H-5 | ArchUnit AR-1~AR-4 |
| H-6 | Testcontainers(pgvector) + Flyway 기반 L2 하네스, LLM 픽스처 재생·녹화 장치 |
| H-7 | `docs/harness/60-invariants.md` 카탈로그 작성 + INV-01·02·09 구현 |
| H-8 | `./gradlew guardrails` + GitHub Actions 워크플로 3잡 |
| H-9 | `docs/requirements/R1-R6.md` — 요구사항 + AC (리뷰 §2, 각 2개 이상) |

H-9까지 끝나면 M0 도메인 브레인스토밍으로 넘어간다.

**주의:** H-7의 INV-09(idempotency)와 H-6의 버전 삼중 연결은 M0 도메인 스키마가 없으면 완전히 구현할 수 없다. 이 두 항목은 하네스 단계에서 **검사 장치와 카탈로그 항목만** 만들고, 실제 검사 대상 연결은 M0 첫 태스크에서 완성한다.

---

## 11. Post-MVP 진화 등록부 — 하네스 추가분

`baseline-v0.1.md` §36의 등록부를 하네스 영역으로 확장한 것이다. 사료는 수정하지 않으므로(§8.2) 아래 표가 하네스 영역의 등록부 원본이며, 항목이 늘어나면 `docs/arch/decisions.md`에 반영한다.

| 영역 | MVP | 진화 | 업그레이드 트리거 |
|---|---|---|---|
| 모듈 구조 | 단일 Gradle 모듈 + ArchUnit | 멀티모듈 (컴파일 타임 강제) | 워커를 독립 배포해야 할 때 |
| 리뷰 게이트 | 교차 검증 + 사람 최종 판단 | 리뷰 자동 반송 | BLOCKING 오판율이 실측으로 낮다고 확인될 때 |
| BLOCKING 목록 | v1 4종 | 확장 (§5.5 절차) | 동일 유형 누락이 2회 반복될 때 |
| log.md | 2단 단일 파일 + 마일스톤 아카이브 | 구조화 포맷(YAML front-matter 등) | 로그를 기계가 질의해야 할 때 |
| L2 LLM | 녹화 픽스처 재생 | 계약 테스트 / 모델 간 교차 검증 | 프로바이더를 복수로 운용할 때 |
| CI | GitHub Actions 3잡 | 셀프호스트 러너 / 캐시 계층 | verify가 10분을 넘길 때 |
| 하네스 강제 | CI 게이트 | 도구별 hook 자동화 | Claude Code 단일 도구로 수렴할 때 |

---

## 12. 이 스펙이 다루지 않는 것

- 메모리 도메인 설계 (observation·canonical 스키마, 파이프라인) — M0 스펙에서 다룬다
- 리뷰 §4의 A-1~A-4 결정 확정 — 도메인 브레인스토밍에서 다룬다. 단, A-3(idempotency)과 A-4(파이프라인 버저닝)는 이 스펙의 INV-09와 §6.3 버전 삼중 연결이 이미 전제하고 있다
- 리뷰 §6의 C-1~C-7 반영 방법 — 설계 결함이며 M2 이후 도메인 스펙에서 다룬다. 이 스펙은 INV-06으로 C-1만 카탈로그에 선등록한다
- 배포·운영 (`baseline-v0.1.md` §30) — M0 이후

---

## 13. 요구사항 추적

이 세션에서 제시된 네 가지 요구사항의 반영 위치.

| 요구 | 반영 |
|---|---|
| 1. 하네스 엔지니어링 + 도구 공통 문서 | §2 저장소 구조, §3 문서 계층 (얇은 진입점 + 단일 실체) |
| 2. log.md — 착수 전 참고, 모든 작업물 기록 | §4 전체, §4.3 CI 강제 |
| 3. 루프 엔지니어링 | §5 전체 |
| 4. 테스트 | §6 3계층, §7 불변식 카탈로그, §9 CI |
