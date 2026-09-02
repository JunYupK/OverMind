# OverMind 작업 로그

작성 규약은 `docs/harness/00-start-here.md`에 있다.
세션 시작 시에는 아래 HEAD 블록만 읽는다. 세션 기록은 필요할 때만 검색한다.

<!-- ===== HEAD — 항상 최신으로 덮어쓴다 ===== -->

## 현재 상태

- **마일스톤:** H 완료 (전수 리뷰 지적 9건 + I-3 테스트 계층 게이트 우회 수정 완료) → M0 착수 대기
- **최근 갱신:** 2026-09-02 · Claude Code
- **브랜치:** feat/harness
- **verify:** 통과 / **guardrails:** 통과

### 진행 중

- 없음

### 다음 할 일

1. M0 도메인 브레인스토밍 (`superpowers:brainstorming`)
   — review §4의 A-1~A-4를 결정한다
2. M0 스펙 작성 후 별도 플랜

### 열려 있는 결정

- `docs/arch/decisions.md`의 "열려 있음" 표 참조
- 새로 등록: **B-4 — L3 비용 상한을 강제하는 장치** (M5 이전). 스펙 §6.4가 요구하는
  하드 비용 상한이 지금은 산문으로만 존재한다

### 막힌 것

- 없음

<!-- ===== 세션 기록 — append-only, 최신이 위 ===== -->

## 세션 기록

### 2026-09-02 13:40 · Claude Code · I-3 테스트 계층 게이트 우회 · feat/harness HEAD

- **한 일:** `TestTierBoundaryTest`의 소스 스캐너를 줄 단위 정규식에서 **주석/문자열 제거 후
  중괄호 깊이 추적** 방식으로 다시 썼다. 최상위 타입은 깊이 0에서만 인정하고, 애노테이션과
  수식자는 선언 직전 경계까지의 헤더 구간에서만 읽는다. 상속 절은 선언 이름 끝부터 본문 여는
  중괄호까지를 통째로 보고, 애노테이션은 완전 수식 이름과 메타 애노테이션(커스텀 애노테이션
  타입)까지 전이 해석한다. 태그 없이 Testcontainers만 켜는 클래스도 같이 잡는다.
  우회 6종을 스캐너에 직접 먹이는 `known_evasions_are_caught`와 오탐 5종을 못 박는
  `legitimate_shapes_are_not_flagged`를 추가했다
- **결과:** `clean verify` 통과 / `guardrails` 통과. L1 19건. 우회 6종을 각각 프로브 클래스로
  심어 옛 게이트(exit 0, BUILD SUCCESSFUL)와 새 게이트(exit 1, 위반 클래스명 적시)를 대조 확인
- **함정:** **게이트를 무력화한 것은 공격이 아니라 포매터였다.** 옛 패턴은 `^` 앵커를
  `Pattern.MULTILINE` 없이 줄마다 걸었기 때문에, 타입이 등록되려면 선언이 0열에서 시작하고
  `extends`가 **같은 물리적 줄**에 있어야 했다. google-java-format이 긴 선언을 접기만 해도
  상속 링크가 사라져 `@SpringBootTest`를 붙인 부모를 상속한 클래스가 통째로 안 보였다.
  실제로 Spring 컨텍스트와 Postgres 컨테이너가 초록색 `./gradlew test` 안에서 떴다.
  같은 뿌리에서 우회로가 다섯 갈래 더 나왔다 — 메서드 레벨 `@Tag`가 파일 단위 수집 때문에
  클래스 태그로 오인되던 것, 완전 수식 애노테이션, 선언과 같은 줄의 애노테이션, 메타 애노테이션.
  교훈 둘: (1) **소스 스캔 게이트를 줄 단위로 쓰지 말 것.** 자바 선언은 줄 경계와 무관하다.
  최소한 주석/문자열을 지운 뒤 깊이를 추적해야 한다. (2) 주석과 문자열을 먼저 지우면
  "검사기가 자기 문서에 걸리는" 문제가 구조적으로 사라진다 — 줄 앞머리 앵커로 흉내 낼 일이 아니다.
  실제로 `scrub()`을 무력화해 보면 검사기가 자기 예제 문자열에 걸려 **시끄럽게** 실패한다
- **다음:** 브랜치 머지. 이후 M0 도메인 브레인스토밍

### 2026-09-02 13:05 · Claude Code · CI 수정 2

- **한 일:** `.gitignore`의 `out/`을 `/out/`으로 앵커하고, 누락돼 있던 `adapter/out/package-info.java`를 추적에 추가
- **결과:** PR #1의 `verify` 잡에서 `PackageLayoutTest.base_packages_exist()`가 실패하던 원인 제거. `guardrails` 잡은 이미 통과
- **함정:** IntelliJ 출력 디렉터리를 무시하려던 `out/`은 앵커가 없어 **모든 깊이의 `out` 디렉터리**에 적용된다. 그래서 아키텍처 패키지 `src/main/java/com/overmind/adapter/out/`이 통째로 커밋에서 빠졌다 — AR-3이 감시해야 할 바로 그 패키지이고, M0의 영속·LLM·임베딩 어댑터가 전부 들어갈 자리다. 로컬 디스크에는 존재하므로 로컬 `verify`는 계속 초록이었고, 새로 클론한 CI에서만 드러났다. 무시 패턴은 의도한 위치에 앵커할 것
- **다음:** CI 재실행 확인. 머지 전 I-3(`@SpringBootTest` 게이트 우회) 결정 필요

### 2026-09-02 12:55 · Claude Code · CI 수정

- **한 일:** `gradlew`에 실행 비트 부여 (`git update-index --chmod=+x`)
- **결과:** PR #1의 verify/guardrails 두 잡이 `./gradlew: Permission denied` (exit 126)로 실패하던 것을 수정
- **함정:** Windows에서 커밋한 `gradlew`는 모드가 100644로 들어간다. 로컬에서는 Git Bash가 실행해 주므로 절대 드러나지 않고, Linux 러너에서만 터진다. 이 저장소의 모든 게이트가 `./gradlew`로 시작하므로 CI가 통째로 무력화된다
- **다음:** CI 재실행 확인 후 master 브랜치 보호 설정

### 2026-09-02 · Claude Code · 하네스 전수 리뷰 지적 반영 · (커밋 SHA는 아래 참조)

- **한 일:** 브랜치 전수 리뷰가 낸 9건(C-1, C-2, I-1~I-7)을 한 번에 고쳤다.
  - **C-1** 두 기계 게이트가 아무것도 실행하지 않고 초록이 되던 문제. `test`/`integrationTest`/
    `guardrailTest`에 0건 실행 바닥(`*NotEmpty` 태스크)을 붙였다. `evaluationTest`는 제외.
  - **C-2** `updateMigrationChecksums`를 append-only로 바꿨다. 이미 기록된 항목이 바뀌거나
    사라지면 파일 이름을 대며 실패한다.
  - **I-1** AGENTS.md 절대 규칙 4에 `guardrails`를 같이 적었다.
  - **I-2** 로컬–CI 동치 주장을 gitleaks 한 단계만 예외로 좁혔다.
  - **I-3** `TestTierBoundaryTest` 신설 — 태그 없는 테스트의 `@SpringBootTest`를 막는다.
  - **I-4** `ProviderNameLeakTest`를 대소문자 무시로 바꾸고 gpt/llama/mistral/bedrock/vertex 추가.
  - **I-5+I-7** 진술을 강제와 맞췄다(커밋 → PR 범위). 가드 감시 경로에
    `build.gradle.kts`, `.github/`, `docs/harness/`를 추가했다.
  - **I-6** JDK 21을 사전 준비에 명시하고 foojay 툴체인 리졸버를 넣었다.
  - **문서 등록** `decisions.md` 열려 있음 표에 B-4(L3 비용 상한 강제 장치, M5 이전) 추가.
- **함정 1 — 바닥 검사를 Test 태스크 안에 둘 수 없다.** 테스트 소스가 사라지면 태스크가
  `NO-SOURCE`가 되고, 그때는 `doLast`도 같이 건너뛴다. 즉 자기가 안 돌았다는 사실을 자기가
  보고할 수 없다. 그래서 별도 태스크(`*NotEmpty`)를 `dependsOn` + `finalizedBy`로 바깥에 붙였다.
  JUnit XML의 `tests="N"` 합계를 세는데, Gradle이 `NO-SOURCE`일 때 이전 출력물을 지워 주기
  때문에 stale 결과로 통과하는 일이 없다 — 이것은 실제로 소스를 치우고 확인했다.
- **함정 2 — `@SpringBootTest` 검사가 자기 자신을 잡는다.** 파일 본문에 그 문자열이 있는지로
  판정하면, 그 규칙을 설명하는 javadoc과 실패 메시지 때문에 검사 파일 자신이 위반이 된다.
  줄을 trim했을 때 애노테이션으로 시작하는 경우만 세도록 좁혔다.
- **함정 3 — 상속 우회.** 부모에 `@SpringBootTest`를 숨기고 자식은 태그 없이 두는 우회가
  가능해서, `extends` 사슬을 따라 올라가며 컨텍스트 기동과 태그를 둘 다 본다.
- **함정 4 — 리포트의 한글이 콘솔에서 깨져 보인다.** 인코딩 문제인 줄 알고
  `options.encoding = "UTF-8"`을 넣었다가, XML 파일 자체는 정상 UTF-8이고 깨진 것은
  터미널 렌더링뿐임을 확인하고 되돌렸다. JDK 21은 이미 기본이 UTF-8이다.
- **검증:** 새 게이트 5개(C-1 두 시연, C-2, I-3, I-4, I-5/I-7)를 전부 **직접 빨간불로 만들어 보고**
  복원했다. 게이트를 실패시켜 보지 않고 믿은 것이 이번 리뷰 지적의 원인이었으므로 반복하지 않는다.
  증거는 `.superpowers/sdd/2026-09-01-overmind-harness/final-fix-report.md`.
- **결과:** `./gradlew verify`, `./gradlew guardrails` 모두 통과. 로컬 커밋만, push 미수행.
- **다음:** M0 도메인 브레인스토밍. 그 전에 B-4(L3 비용 상한 장치) 결정을 잊지 않는다.

### 2026-09-02 · Claude Code · Task 11 · (커밋 SHA는 아래 참조)

- **한 일:** 요구사항 문서 작성 — R1~R6과 각 2개씩 AC(총 12개), given/when/then 형식,
  활성 마일스톤 표기. docs/requirements/.gitkeep 제거. log.md HEAD 블록 갱신(H 완료 → M0 착수 대기).
- **검증:** `./gradlew verify guardrails` BUILD SUCCESSFUL (8초)
  — docs/requirements/R1-R6.md 포함 R1~R6 6개 요구사항, AC 12개 확인
- **결과:** 로컬 커밋 완료, push 미수행 (컨트롤러가 브랜치 전체 리뷰 후 푸시 예정)
- **다음:** M0 도메인 브레인스토밍

### 2026-09-02 · Claude Code · Task 10 · (커밋 SHA는 아래 참조)

- **한 일:** `.github/workflows/ci.yml` 작성 — `verify`/`guardrails`(PR·push 게이트),
  `evaluation`(야간 03:00 KST 스케줄 + `workflow_dispatch`만, 실 LLM 호출이라 PR을 안 막음)
  3잡. 브리프 대비 추가한 것: `guardrail 검사` 스텝에서 `BASE`를 계산한 직후
  `git rev-parse --verify "$BASE"`로 먼저 검증하고, 풀리지 않으면 `::error::`를 찍고
  `exit 1`로 잡을 명시적으로 실패시킨 뒤에야 `./gradlew guardrailTest -PbaseRef="$BASE"`를
  부르도록 했다. 이유: `LogUpdatedGuardTest`는 baseRef가 안 풀리면 실패가 아니라
  스킵하므로, 얕은 체크아웃이나 브랜치 첫 커밋(`HEAD~1` 없음)에서 log.md 가드가 조용히
  꺼진 채 CI가 초록으로 남는 구멍이 있었다.
- **검증:** YAML 파싱(`python -c "import yaml..." ` → `ok`), `./gradlew verify guardrails`
  BUILD SUCCESSFUL, 리포 밖 스크래치 스크립트로 `$BASE` 검증 로직만 떼어내
  (1) 존재하지 않는 PR base_ref → `origin/<ref>`가 안 풀려 exit 1로 실패,
  (2) 현재 저장소에서 push 스타일 `HEAD~1` → 통과, (3) 커밋 1개짜리 임시 repo에서
  `HEAD~1` 부재 → exit 1로 실패, 세 가지를 모두 직접 관찰.
- **결과:** push는 하지 않음(컨트롤러가 브랜치 전체 리뷰 후 푸시하고 그때 CI 확인
  예정). 로컬 커밋만 함 — `git status` clean, `git log origin/master..HEAD` 확인.
- **다음:** `feat/harness` 브랜치 전체 리뷰 → 푸시 → Actions 탭에서 verify/guardrails
  초록 확인. Testcontainers가 러너에서 실패하면 `docs/harness/20-build-and-test.md`에
  기록.

### 2026-09-02 · Claude Code · Task 9 · 4d65583

- **한 일:** 가드레일 검사 4종(`DocLineLimitGuardTest`, `DdlAutoGuardTest`,
  `MigrationChecksumGuardTest`, `LogUpdatedGuardTest`) 작성, `build.gradle.kts`에
  `updateMigrationChecksums` 태스크 추가, `docs/harness/migration-checksums.txt` 생성.
  네 가드 전부 실패/통과를 직접 관찰(문서 30줄 초과, 체크섬 파일 부재, ddl-auto=update,
  log.md 없이 src/만 바뀐 baseRef 범위).
- **결과:** `./gradlew guardrails` 통과 (gitleaks 미설치 경고만 출력) / 리뷰 미실시
- **함정:** `build.gradle.kts`에 `java { toolchain {...} }` 확장이 이미 있어서, 브리프의
  `java.security.MessageDigest.getInstance(...)` 표현이 `java`를 확장 프로퍼티로 해석해
  컴파일 에러(`Unresolved reference: security`)를 낸다. 파일 상단에
  `import java.security.MessageDigest`를 추가하고 본문에서 `MessageDigest`로 바꿔서 해결.
  log.md HEAD 블록의 `브랜치: master`가 실제와 달랐다 — `feat/harness`로 정정.
- **다음:** Task 10 GitHub Actions 워크플로
