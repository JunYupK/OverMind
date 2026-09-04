# OverMind 작업 로그

작성 규약은 `docs/harness/00-start-here.md`에 있다.
세션 시작 시에는 아래 HEAD 블록만 읽는다. 세션 기록은 필요할 때만 검색한다.

<!-- ===== HEAD — 항상 최신으로 덮어쓴다 ===== -->

## 현재 상태

- **마일스톤:** **M0 — Task 0~9 병합 완료, Task 10 커밋 완료, Task 11 구현·검증 완료(미커밋)**
- **최근 갱신:** 2026-09-03 · Codex (Task 11 완료)
- **브랜치:** `codex/m0-t10-t14` (`origin/master`의 `fc7abdd`에서 시작)
- **verify:** L1 114건 / L2 72건 통과(로컬 Docker) · **guardrails:** 11건 통과.
  실패·오류·스킵 0건. gitleaks 미설치로 로컬 시크릿 스캔은 생략됐다.

### 진행 중

- **Task 10은 `360024b`로 커밋했다. T11은 같은 브랜치에서 구현·검증했고 미커밋이다.**
  `remember_memory`·`recall_memory`를 실제 유스케이스·DB와 연결했다. Streamable HTTP로
  저장·멱등 재시도·페이지 조회·응답 필드·오류 코드와 원문 비노출을 검증했다.
  T12~T14는 아직 착수하지 않았다.
- **Task 8은 PR #9(`a21b6ac`), Task 9는 PR #10(`fc7abdd`)으로 master에 병합됐다.**
  T9가 분리한 AR-3B는 실제 SDK 침투 프로브를 잡았고, 프로브 제거 후 다시 통과했다.
  - 스펙 `docs/superpowers/specs/2026-09-02-overmind-m0-design.md` (사용자 승인)
  - 플랜 `docs/superpowers/plans/2026-09-02-overmind-m0.md` — Task 0~14
  - `build.gradle.kts`가 Spring Boot 4.1.1 / Hibernate 7.4.5.Final / jakarta.persistence
    3.2.0 / Flyway 12.4.0 / Spring Framework 7.0.9 위에서 돈다. 자세한 내용은
    `.superpowers/sdd/2026-09-02-overmind-m0/task-0-report.md`

### 다음 할 일

1. 사용자 요청의 T10 커밋과 T11 구현을 완료했다. T11 코드·테스트·플랜·이 로그를
   함께 보존하고 다음 전달 요청에서 커밋한다. 푸시·PR 생성은 아직 하지 않았다.
2. 다음 구현 태스크는 T12(OAuth 정책·필수 설정 검증)다. T11의 커서 키는
   `OVERMIND_CURSOR_SECRET`에서 주입하며 UTF-8 32바이트 이상이 필요하다. 운영 기본 키는
   없고 테스트에만 고정 키를 제공한다. 로그 위생은 T13, 원격 스모크는 T14에서 이어진다.
3. **기동 설정 주의:** jar 메타데이터의 protocol 기본값은 `streamable`이지만 실제
   Streamable 조건은 `matchIfMissing=false`, SSE 조건은 `true`다. 명시한 키를 지우면
   SSE가 활성화된다. 기본 `type=sync`, `stdio=false`, endpoint `/mcp`는 유지한다.
4. **MCP 입력 변환 주의:** 직접 등록한 `SyncToolSpecification`과 DTO 검증을 사용한다.
   SDK 선행 검증은 `validateToolInputs(false)`로 끄고, servlet customizer에 먼저 위임한다.
   해당 빈은 웹 환경에서만 생성한다. MCP 전용 JSON 매퍼는 map-content inclusion을
   `ALWAYS`로 유지해야 명시적인 null이 변환 중 사라지지 않는다. 이후 도구도 자체 검증이
   필수다. T11 HTTP 테스트의 인증 허용 설정은 테스트 코드에만 있으며 T12를 대신하지 않는다.

### 확정된 결정

- **작업 절차 — 교차 리뷰 선택 사항** (2026-09-02, 사용자 결정).
  Claude Code↔Codex 리뷰를 자동 요구하지 않는다. 스펙·diff 자체 대조와 두 기계
  게이트는 유지한다. 상세 규칙은 `docs/harness/30-loop.md`를 따른다.
- **A-1~A-4** — M0 설계가 닫았다. Async only / Replay 불변식 / 호출자 제공 idempotency
  key / 실행된 단계의 버전만 기록
- **D-G — Spring Boot 4.1.1로 올린다** (사용자 승인). D-B의 Boot 3 부분을 대체한다.
  Java 21 유지(Boot 4 기준선은 Java 17). Spring AI 2.0.1 BOM을 사용한다.
  T10 실측에서 transport는 2.0.1, MCP core는 2.0.0으로 확인했다. 결정 문서에 정정 기록.
전부 `docs/arch/decisions.md`에 있다.

### 열려 있는 결정

- **B-1·B-2·B-3 — 기한이 M0로 확정됐다** (사용자 결정). 구현은 M2 이후지만 **결정은
  M0가 끝나기 전에** 한다. M0를 매일 써 본 경험이 있어야 slot registry 범위·snapshot
  시점·bootstrap 수치를 근거를 갖고 정할 수 있다
- **B-4 — L3 비용 상한을 강제하는 장치** (기한 M5 이전). 여전히 산문뿐이다
- **스펙 §5.4의 2 MiB 예산은 공개 API로 도달 불가다.** `limit` 최대 100(§5.2) ×
  content 최대 16 KiB(§4.2) = 1,638,400 bytes. Task 7은 스펙 수치를 그대로 두되
  예산을 주입 가능하게 만들어 로직만 검증하고, **도달 불가라는 사실을 못 박는 검사**를
  넣었다 — 수치가 바뀌면 그 검사가 실패하며 알려 준다. 수치 조정은 결정 사항


### 이월된 결함 — 닫히지 않았고 각각 이유가 있다

- `@Nested` 내부 클래스의 `@SpringBootTest`는 계층 게이트를 여전히 우회한다
  (google-java-format으로는 도달할 수 없는 형태라 우선순위를 낮췄다)
- `@Tag(상수)` 거짓 양성 — 안전한 방향으로 실패하므로 의도적으로 남겼다
- 바닥 검사는 **발견된** 테스트를 세지 **실행된** 것을 세지 않는다
- AR-3의 LLM SDK 쪽은 대상 의존성이 없어 아직 실물 검증 전이다.
  MCP/Spring AI 쪽은 T10의 실제 SDK 코어 침투 프로브로 AR-3B 발화를 확인했다.
- **`FixtureLlmPort`는 M0에서 살아나지 않는다 — M2로 미룬다.** 인수인계에는 "M0 첫 L2
  태스크의 acceptance criterion으로 묶어야 죽지 않는다"고 되어 있었으나, 스펙 §9는 M0에
  실 LLM L3가 없다고 못 박았고 §11은 LLM extraction을 범위 밖으로 뒀다. **M0에는 LLM을
  부를 곳이 아예 없어 묶을 자리가 없다.** 억지로 묶으면 스펙과 충돌한다
- `OVERMIND_LLM_API_KEY` 시크릿 미등록 — 실 L3 실행 전에 필요하다
- **원격 브랜치 3종 미삭제 — 이 환경에서 지울 수 없다.** `feat/harness`,
  `feat/vendor-skills`, `feat/widen-log-guard` 전부 `--cherry-pick`으로 master에 대응
  커밋이 있어 삭제해도 잃는 것이 없다. 그런데 `git push --delete`가 **403**이고
  GitHub MCP에는 브랜치 삭제 도구가 없다. 로컬에서
  `git push origin --delete <브랜치>`로 지워야 한다

### 막힌 것

- 없음. 교차 리뷰는 사용자 요청이 있을 때만 수행한다.

<!-- ===== 세션 기록 — append-only, 최신이 위 ===== -->

## 세션 기록

### 2026-09-03 18:11 · Codex · M0 Task 11 · 미커밋
- **한 일:** 요청받은 T10을 `360024b`로 커밋하고 같은 브랜치에서 T11을 구현했다.
  도구 두 개를 유스케이스·실제 HMAC 커서와 연결하고 공개 응답·안전한 오류로 변환했다.
  SDK의 타입 선변환을 피하기 위해 진입 어댑터의 직접 tool specification 등록을 선택했다.
- **결과:** `verify` L1 114 / L2 72, `guardrails` 11건 통과. 실패·오류·스킵 0건.
  실 HTTP 계약 테스트는 28건이다. 부모가 스펙·diff를 대조했으며 사용자 결정에 따라
  교차 리뷰는 수행하지 않았다. 로컬 gitleaks 미설치로 시크릿 스캔은 생략됐다.
- **함정:** SDK 선행 스키마 검증은 안전한 오류 매핑 전에 자체 평문 오류를 반환한다.
  이를 끄되 공개 스키마는 유지하고 DTO/도메인에서 저장 전에 모두 검증한다.
  Spring AI customizer 주입은 단일 빈이어서 `@Primary`와 기존 servlet 빈 위임이 필요하다.
  웹 환경 조건이 없으면 기존 비웹 DB 테스트가 기동에 실패하므로 servlet 환경으로 한정했다.
  null 누락은 처음 의심한 클라이언트 문제가 아니었다. 원시 HTTP와 독립 변환 프로브로
  Spring AI 매퍼의 `NON_NULL` map-content 설정이 SDK `convertValue`에서 항목을 지우는
  것을 확인하고 MCP 전용 매퍼만 `ALWAYS`로 변경했다. null을 정상 누락으로 허용하지 않는다.
  사용량 제한으로 중단된 구현을 부모가 이어받아 최종 검증했다.
- **다음:** T11 변경 전체를 함께 커밋할 상태로 남겼다. 다음 구현은 T12이며 기존 키 주입,
  직접 도구 등록과 servlet customizer를 이어받는다. 푸시·PR은 이후 요청에 따른다.

### 2026-09-03 13:48 · Codex · M0 Task 10 커밋 · 본 커밋(git log 참조)
- **한 일:** 사용자 요청에 따라 검증된 T10 구현·문서·인계 로그를 함께 커밋했다.
- **결과:** 커밋 직전 `verify` L1 111건/L2 43건, `guardrails` 11건 재검증 통과(56초).
  로컬 gitleaks는 미설치로 생략됐고, 스펙·diff 자체 대조를 유지했다.
- **함정:** 이전 T10 구현 기록의 미커밋 표기는 당시 상태이므로 보존한다.
  MCP core 2.0.0과 Spring AI transport 2.0.1의 구분 및 명시 protocol 설정을 인계한다.
- **다음:** 같은 브랜치에서 T11을 구현한다. 푸시·PR 생성은 요청 시 진행한다.

### 2026-09-03 13:43 · Codex · M0 Task 10 구현·검증 · 미커밋
- **한 일:** T9 병합 커밋 `fc7abdd`에서 `codex/m0-t10-t14`를 만들고 T10만 구현했다.
  승인된 Spring AI BOM 2.0.1, WebMVC MCP 서버·보안 의존성을 추가하고
  `spring.ai.mcp.server.protocol: streamable`을 명시했다. T10 기동 테스트와 인계 문서를 갱신했다.
- **결과:** 기본 설정에서 Streamable 부재·SSE 활성으로 L2 2건이 먼저 실패했고,
  설정 후 2건 모두 통과했다. 실제 SDK를 참조한 `application.Probe`를 AR-3B가
  지목하는 예상 실패를 확인하고 제거했다. 최종 `verify` L1 111건/L2 43건,
  `guardrails` 11건 통과(57초), 실패·오류·스킵 0건. 로컬 gitleaks는 미설치로 생략.
  스펙·diff 자체 대조를 수행했고 사용자 결정대로 교차 리뷰는 실시하지 않았다.
- **함정:** 메타데이터는 protocol 기본값을 `streamable`이라 적지만 자동설정은
  키가 없으면 SSE를 고른다. `EnabledStreamableServerCondition`의
  `protocol=STREAMABLE`은 `matchIfMissing=false`, SSE 쪽은 `true`이므로 명시 설정이 필요하다.
  Spring AI transport는 2.0.1이지만 그 POM과 실제 runtime의 MCP core는 2.0.0이다.
  별도 SDK 강제 대신 승인된 BOM 구성을 유지하고 `decisions.md`에 기존 표기를 정정했다.
  기본 `type=sync`, `stdio=false`, endpoint `/mcp`는 그대로다. SSE 부재 검사에서
  deprecated-for-removal 타입 경고가 생기지만 해당 transport를 실제로 배제하는 검사다.
  Windows의 SDD 보조 스크립트는 dirname/basename을 못 찾아 PowerShell로 태스크 본문을 추출했다.
- **다음:** 미커밋 T10 변경과 이 기록을 함께 전달한다. 사용자 요청 시 커밋·푸시·PR을
  진행하고 T11부터 이어간다. T12 인증 정책과 T14 원격 HTTPS 검증은 이번 완료 범위가 아니다.

### 2026-09-03 · Claude Code (원격 세션) · Task 9 AR-3 개정 · claude/m0-t9

- **한 일:** AR-3을 방향별로 나눴다. MCP 서버는 바깥이 우리를 부르는 **진입** 어댑터인데
  기존 규칙은 `io.modelcontextprotocol..`을 `adapter.out` 밖 어디서도 금지했다 —
  스펙 §3대로 구현하는 순간 게이트가 실패한다.
  - `AR_3` — LLM SDK 셋(`com.anthropic`/`com.openai`/`dev.langchain4j`)만 `adapter.out`에 가둔다
  - `AR_3A` — MCP SDK는 `adapter..` 안이면 진입·진출 어디든. `config`는 어댑터 밖이라
    여전히 걸린다(빈 등록 시 SDK 타입 직접 참조 금지)
  - `AR_3B` — **domain·application은 여전히 SDK를 모른다.** Spring AI가 MCP 타입을 자기
    패키지로 재노출하므로 `org.springframework.ai..`도 함께 막는다
  - 플랜의 Task 10 Step 5가 참조하던 규칙 이름을 실제 이름에 맞췄다
- **결과:** L1 105건 → **111건**(`@ArchTest` 2 + `@Test` 4, 예상과 정확히 일치).
  `guardrails` 11건, gitleaks 통과. **`verify`는 Docker가 없어 완주하지 못했다** —
  다만 이 태스크는 테스트 코드만 건드리므로 L2 영향은 없다
- **함정:** **규칙 셋이 전부 공허하게 통과하고 있었다.** AR-3이 지키는 SDK 패키지는
  Task 10에서 의존성이 들어오기 전까지 classpath에 아예 없다. 즉 "검사할 대상이 없어서"
  통과하는 중이고, **그 상태는 규칙을 통째로 지운 것과 로그에서 구별되지 않는다.**
  이 저장소가 반복해서 당한 형태 그대로다.
  그래서 `LayerRuleCoverageTest`를 따로 뒀다 — 규칙의 *형태*가 실제 위반을 잡는지
  (`adapter.out` → `jakarta.persistence`는 실제 의존이므로 금지 규칙이 반드시 실패해야 한다),
  그리고 지키는 패키지 목록이 조용히 좁아지지 않았는지. **`@AnalyzeClasses`를 붙이지 않았다** —
  ArchUnit 엔진이 `@Test` 메서드를 어떻게 다루는지에 기대면 그 자체가 조용한 미실행 경로가 된다.
  둘째: **classpath에 없는 패키지로는 썩힘 실험을 못 한다.** 금지 목록에 `java.util..`을
  임시로 더해 규칙의 *대상 선택*이 살아 있는지 확인했다 — `AR_3B`는 application이,
  `AR_3A`는 config·domain이 실제로 `java.util`을 쓰므로 반드시 실패한다. 넷 다 정확히
  해당 검사만 깨뜨렸다. **SDK를 실제로 코어에 끌어들이는 프로브는 Task 10 Step 5 몫이다**
- **다음:** Task 10(MCP·보안 의존성). **사용자 승인 필요** — 의존성 추가. L2라 Docker 필요


### 2026-09-03 13:10 · Codex · M0 Task 8 커밋·푸시·PR · 본 커밋(git log 참조)
- **한 일:** 사용자 요청으로 보관 중이던 T8 구현·테스트·문서·로그를 커밋하고,
  `codex/m0-t8`을 푸시해 master 대상 PR을 생성한다.
- **결과:** 원격 master가 기준 커밋 `ad19993` 그대로이며 별도 병합이 필요 없음을
  확인했다. 커밋 전 재실행한 `verify`(L1 105건/L2 41건)와 `guardrails`(11건)가
  통과했다. 실패·오류·스킵 0건이며 로컬 gitleaks는 미설치로 생략됐다.
- **함정:** 이전 구현 세션의 미커밋 기록은 당시 상태를 나타내므로 수정하지 않는다.
  교차 리뷰는 사용자 결정에 따라 미실시하며 자체 스펙·diff 대조와 두 게이트를 유지한다.
- **다음:** 새 PR의 CI 결과를 확인한다. PR 병합과 T9 착수는 별도 요청에 따른다.

### 2026-09-03 12:43 · Codex · M0 Task 8 · 미커밋
- **한 일:** T6~T7 병합 커밋 `ad19993`에서 `codex/m0-t8`을 만들고 실제 keyset 조회를
  구현했다. observation과 subject를 한 native query로 읽어 모든 도메인 값을 복원한다.
  다음 페이지 확인용 한 행은 응답에서 제외하며, 조회는 read-only transaction이다.
- **결과:** 빈 페이지 구현에서 조회 관련 L2 6건이 실패함을 확인한 뒤 T8 L2 8건이
  통과했다. `id DESC`를 제거하면 고정 UUID 정렬 검사가 실제로 실패했고 복원했다.
  최종 `verify`(L1 105건/L2 41건)와 `guardrails`(11건)는 실패·오류·스킵 0건으로
  통과했다. 스펙·diff 자체 대조 실시, 교차 리뷰는 사용자 결정에 따라 미실시.
  로컬 gitleaks는 미설치로 생략됐다.
- **함정:** 구현 에이전트가 테스트 작성 후 사용량 제한에 걸려, 사용자 재개 요청 후
  본 에이전트가 이어받았다. SQLException은 Iterable이기도 해서 AssertJ overload가
  모호했다. Throwable 단언으로 명시한 뒤 실제 미구현 실패를 확인했다. USER를 쓰는
  테스트끼리 행이 섞이지 않도록 테스트 소유 ID만 정리한다. 두 시각을 모두 고정하고
  PostgreSQL UUID 정렬의 부호 경계까지 확인해야 마지막 tie-breaker가 검증된다.
  기본 2 MiB 예산은 현 API 상한에서 도달 불가하므로 기존 T7의 예산 주입을 사용해
  HMAC 커서가 실제 반환된 마지막 행부터 누락 없이 순회하는지 검증했다.
- **다음:** T8 구현·검증 범위로 마무리한다. 미커밋 변경과 로그를 보존하며,
  커밋·푸시·PR 및 T9 착수는 사용자 요청에 따른다.

### 2026-09-03 · Claude Code (원격 세션) · Task 6 HMAC cursor · Task 7 RecallMemory · claude/m0-t6-t7

- **한 일:** `master`(`d902741`)에서 브랜치를 따 Task 6·7을 구현했다.
  - **Task 6** — `RecallCursor.filterFingerprint(List<UUID>)`, `CursorCodec` 포트,
    `HmacCursorCodec` 어댑터. 토큰은 `v1.<payload>.<signature>`이고 payload에
    content·source id·raw subject id를 넣지 않는다. 서명 비교는 `MessageDigest.isEqual`
  - **Task 7** — `RecallQuery`/`RecallItem`/`RecallResult`/`RecallMemory`.
    `SubjectRepository.findUser()`를 포트·어댑터·fake에 추가했다(플랜이 예고한 확장).
    `InMemoryRepositories`의 `findPage`와 seed 헬퍼도 채웠다
- **결과:** L1 83건 → **105건**. `guardrails` 11건 통과. ArchUnit 통과.
  gitleaks 직접 실행 `no leaks found`. **`verify`는 완주하지 못했다** — 이 컨테이너에
  Docker가 없어 L2를 못 돌린다. Task 6·7은 순수 L1이라 로직 자체는 전부 검증됐지만,
  `SubjectRepository`에 메서드를 더했으므로 **기존 L2가 여전히 도는지는 CI가 판정한다**
- **함정:** **베이스라인이 먼저 빨갰는데 코드 결함이 아니었다.** 아무것도 안 쓴 상태의
  `master`에서 `./gradlew test`가 죽길래 Task 5 문제인가 했는데, 원인은 Maven Central의
  **HTTP 429(Too Many Requests)**였다 — Boot 4.1.1 플러그인이 캐시에 없는 의존성을 새로
  받아야 했다. 백오프 재시도로 풀렸다. **내 변경 전에 베이스라인을 먼저 돌린 덕에
  구분됐다.** 안 돌렸으면 내 코드 탓으로 몇 십 분을 태웠을 것이다.
  둘째: **테스트와 구현을 같이 쓰면 실패를 못 본다.** 105건이 한 번에 초록이 됐는데
  그것만으로는 검사가 실물인지 알 수 없다. 썩힘 실험 3종을 돌려 각각이 정확히 해당
  테스트만 깨뜨리는 것을 확인했다.
  셋째: **주변 코드는 영어 주석을 쓴다.** 플랜은 한국어로 썼지만 Codex 구현이 영어
  Javadoc에 `Spec §N` 참조 형식이라 거기 맞췄다. 플랜의 코드 블록을 그대로 옮기지 않았다
- **다음:** Task 8(keyset L2). Docker 있는 환경에서


### 2026-09-03 10:20 · Codex · M0 Task 5 · 본 커밋(git log 참조)
- **한 일:** RememberMemory를 구현했다. 입력 검증 뒤 트랜잭션 하나에서 기존 조회,
  subject 생성, insert-or-find와 결과 판정을 수행한다. 기존 행과 경쟁 승자 모두
  같은 의미 필드 비교를 적용한다. 요청 record의 문자열 표현은 원문을 숨긴다.
- **결과:** 미구현 클래스의 컴파일 RED 이후 T5 L1 20건과 실제 DB L2 7건이 통과했다.
  최종 `verify`(L1 71건/L2 33건)와 `guardrails`(11건)가 통과했고 실패·오류·스킵은
  0건이다. PROJECT key 비교를 제거한 L1과 트랜잭션 경계를 제거한 L2가 각각
  실패함을 확인하고 복원 후 전체 검증했다. 자체 스펙·diff 대조 실시, 사용자 결정에
  따라 교차 리뷰는 미실시. 로컬 gitleaks는 생략됐고 CI에서 검사한다.
- **함정:** PostgreSQL은 sub-microsecond 입력을 정확히 보존하지 않는다. 같은 요청의
  재시도가 잘못 conflict가 되지 않도록 microsecond로 표현 불가능한 observed_at은
  트랜잭션 시작 전에 INVALID_ARGUMENT로 거부한다. 반올림·절삭·마이그레이션 없이
  스펙·플랜·결정 레지스터에 입력 제한을 명시했다. 더 정밀한 입력이 필요하면 별도
  저장 설계가 필요하다. L2는 두 최초 조회를 barrier로 동기화해 실제 insert 경쟁을
  보장하고, 다른 PROJECT를 만든 패자의 롤백도 확인했다. 전용 스키마를 사용한다.
- **다음:** 같은 PR #7에 커밋·푸시하고 제목·설명을 T2~T5로 갱신한다. 다음 태스크는
  T6이다. fake findUser/findPage는 T7, 실제 keyset 조회는 T8, MCP 연결은 T11이다.

### 2026-09-03 09:43 · Codex · M0 Task 4 · 본 커밋(git log 참조)
- **한 일:** 영속화 포트와 PostgreSQL insert-or-find 어댑터, 여러 포트 호출을
  하나로 묶는 트랜잭션 경계를 구현했다. 충돌 대상을 USER partial index,
  PROJECT type/key, observation 멱등 키로 한정하고 나머지 무결성 오류는 전파한다.
  T4에 필요한 RecallCursor 자료형·Clock 빈을 먼저 추가하고 후속 플랜도 맞췄다.
- **결과:** 미구현 포트의 컴파일 RED 이후 통합 테스트 12건이 통과했다.
  PROJECT 충돌 처리 구문을 제거하면 동시 생성 테스트가 실패하는 것도 확인한 뒤
  복원했다. 최종 `verify`(L1 51건/L2 26건)와 `guardrails`(11건)가 통과했고
  실패·오류·스킵은 0건이다. 자체 스펙·diff 대조 실시, 교차 리뷰는 사용자 결정으로
  미실시. 로컬 gitleaks는 생략됐고 PR의 CI에서 검사한다.
- **함정:** 상속된 DynamicPropertySource의 URL이 전용 스키마 URL을 덮어썼다.
  Flyway는 전용 스키마에 성공했지만 Hibernate는 public을 봤다. 테스트에서
  Flyway·Hikari·Hibernate 스키마를 맞추고 JDBC current_schema까지 검증했다.
  8개 동시 요청의 반환 ID뿐 아니라 실제 행 수도 세며, 테스트 소유 행만 정리해
  T3의 public 스키마 검증을 오염시키지 않는다. 새 insert는 호출 객체를 반환하고
  재조회는 DB 정밀도 값을 반환하므로 sub-microsecond 시각 정책은 T5에서 확인해야 한다.
- **다음:** 같은 PR #7에 커밋·푸시하고 범위를 T2~T4로 갱신한다. 다음 구현은
  사용자 인계에 따른 T5이며, 페이지 조회는 T8의 명시적 미구현 상태를 유지한다.

### 2026-09-03 09:19 · Codex · M0 Task 3 · 본 커밋(git log 참조)
- **한 일:** V2 스키마와 JPA 매핑을 구현했다. USER 단일성, PROJECT key,
  전역 멱등 키, UTF-8 크기, 외래 키와 recall 인덱스를 DB에서 검증하고,
  저장·flush·clear·재조회로 모든 매핑 값과 시각의 보존을 확인했다.
  INV-09 카탈로그는 DB 방어선이 구현된 만큼만 부분 구현으로 갱신했다.
- **결과:** 자체 스펙·diff 대조와 최종 `verify`(L1 51건/L2 14건),
  `guardrails`(11건)가 통과했다. 실패·오류·스킵은 0건이다. 사용자 결정에 따라
  교차 리뷰는 미실시. 로컬 gitleaks는 미설치이며 시크릿 스캔은 CI에서 수행한다.
- **함정:** 스키마 테스트를 먼저 썼지만 초기 실행은 보고서 파일 잠금과 Docker
  엔진 정지로 SQL까지 도달하지 못했다. 이를 스키마 RED로 간주하지 않았다.
  보고서 경로를 분리하고 로컬 Docker를 복구한 뒤 L2를 검증했다. JPA는 타입 부재의
  컴파일 RED를 확인했다. 플랜의 `btrim(content)`는 탭·Unicode 공백을 놓치므로
  기존 Java 21 `isBlank()`와 일치시켰다. V2는 검증 후에만 해시를 한 번 기록했고
  V1과 기존 해시는 보존했다. 해시 기록 후 재수정을 제안하는 플랜 예시는 따르지 않았다.
- **다음:** 사용자 승인에 따라 새 브랜치를 푸시하고 PR을 생성한다. 병합과
  T4 착수는 다음 인계에서 정한다. 애플리케이션 영속화 포트의 append-only 계약과
  원자적 find-or-create는 T4 이후에 구현한다.

### 2026-09-03 00:14 · Codex · M0 Task 2 · 본 커밋(git log 참조)
- **한 일:** 병합된 T1을 `origin/master`의 `ecc4fb6`에서 인수하고 새 브랜치에서
  도메인 엔티티와 Clock 기반 관측 시각 정책을 구현했다. 사용자가 T2~T3와
  커밋·푸시·PR 생성을 승인했으며 T4는 이번 범위에 포함하지 않는다.
- **결과:** 테스트 선작성의 실패를 확인한 뒤 구현했다. 자체 스펙·diff 대조와
  `verify`(L1 51건/L2 1건), `guardrails`(11건)가 통과했다. 교차 리뷰는 사용자
  결정에 따라 미실시. gitleaks 미설치로 로컬 시크릿 스캔은 생략됐다.
- **함정:** 재조회한 observation을 복원할 때 현재 시각으로 다시 거부하지 않도록
  생성과 미래 시각 검증을 분리했다. 플랜에 빠진 Observation 필수값·버전·동일성
  테스트를 추가했고, 5분 경계의 바로 다음 1ns까지 검증했다. 도메인 버전은 양수,
  MCP 입력의 버전 1 제한은 T11 계약이다.
- **다음:** T3 V2와 JPA 매핑을 구현·검증한다. V2는 L2 검증을 마친 뒤 해시를
  한 번 기록한다. 기록 후 수정·재해시를 제안하는 플랜 예시는 append-only 가드와
  충돌하므로 따르지 않는다. 기존 V1과 해시는 보존한다.

### 2026-09-02 · Codex · Task 1 커밋·푸시와 교차 리뷰 정책 변경 · 본 커밋(git log 참조)

- **한 일:** 사용자가 앞으로 Claude Code 교차 리뷰 없이 진행하도록 지시하고 commit&push를 승인했다. 필수 교차 리뷰를 작업 루프에서 제거하고, 기존에 보관하던 인계 문서를 Task 1 구현과 함께 포함했다.
- **결과:** 스펙·diff 자체 대조 후 verify·guardrails 통과(exit 0). 제품 코드가 같아 L1 39건 / L2 1건의 기존 성공 결과는 UP-TO-DATE로 재사용됐고, guardrail 11건은 문서 변경을 반영해 다시 통과했다. gitleaks 미설치로 로컬 시크릿 스캔은 생략됐다. Claude Code 교차 리뷰는 사용자 결정에 따라 수행하지 않았다.
- **함정:** 이전 세션의 리뷰 호출 차단은 외부 전송 승인을 우회해 해결한 것이 아니다. 사용자가 교차 리뷰 자체를 필수 절차에서 제외했으며, 앞으로도 별도 요청 없이 다른 도구로 리뷰를 보내지 않는다. 문서 변경을 반영한 뒤 두 게이트를 다시 실행한다.
- **다음:** 현재 브랜치의 기존 PR에서 CI를 확인한다. PR 병합과 Task 2 구현은 이번 요청에 포함하지 않는다.

### 2026-09-02 · Codex · Task 1 도메인 값 객체 구현·검증 · 미커밋(기준 c9e9bd0)

- **한 일:** 사용자 요청대로 Task 0 PR 이후 현재 브랜치에서 Task 1만 이어받았다. 승인된 인계 문서 변경을 보존하며 값 객체를 TDD로 구현했다. 정상 입력은 정규화하지 않아 이후 멱등 비교에서 원문 차이가 유지된다.
- **결과:** 기준 L1 20건 통과 → 새 API 부재로 RED 컴파일 실패 → 도메인 19건과 아키텍처 5건 GREEN. API 호환 record 변이에서 INV-02가 실제 실패한 뒤 원래 final class로 복원했다. 최종 verify·guardrails는 L1 39 / L2 1 / guardrail 11건 통과(exit 0). 코드와 스펙을 자체 대조했으며 Claude Code 교차 리뷰는 자동 승인 거부로 미실시다. gitleaks 미설치로 시크릿 스캔은 생략됐다.
- **함정:** 플랜의 단순 record 예시는 factory와 utf8Size가 없어 컴파일 실패로 끝날 수 있으므로 변이 확인에서는 공개 API를 유지해 toString 게이트의 실패를 확인했다. SDD 셸 스크립트의 basename/dirname 실행이 실패해 PowerShell로 동일한 T1 절을 추출했다. 최종 게이트는 Git Bash 경로를 PATH에 추가하고 결과를 build/codex-t1 아래로 분리해 실행했다. FixtureLlmPort는 M0에 LLM 호출 경로가 없어 기존 결정대로 M2 이월을 유지한다.
- **다음:** Claude Code 교차 리뷰 후 T1과 보존한 인계 문서를 함께 커밋·푸시한다. 이번에는 커밋·푸시·PR 변경이나 Task 2 구현을 하지 않았다.

### 2026-09-02 · Codex · 태스크 인계 규칙 명시 · 미커밋(Task 1에 포함 예정)

- **한 일:** 사용자와 합의한 Claude Code↔Codex 태스크 인계 절차와 조회·리뷰만 한 세션의 파일·로그 수정 예외를 명시했다. 규약 본문은 harness에 두고 AGENTS.md는 해당 문서로 안내한다.
- **결과:** 문서 간 적용 범위와 차이를 검토했다. 제품 코드 변경이 없어 verify·guardrails는 재실행하지 않았다. Task 1 커밋 전에 두 게이트를 실행한다.
- **함정:** 상태 확인 요청을 다음 구현이나 로그 작성 승인으로 해석하지 않는다. 이번 문서 변경은 사용자가 별도로 승인했으며, Task 0 마무리에 끼워 넣지 않고 Codex의 Task 1 커밋·푸시에 포함하도록 미커밋으로 둔다.
- **다음:** Claude Code가 Task 0의 커밋·푸시·PR 병합을 마치고 사용자가 Task 1을 인계하면, 미커밋 문서 변경을 보존한 채 최신 master 기준으로 Task 1을 구현한다.

### 2026-09-02 · Claude Code · Task 0 — Spring Boot 4.1.1 상승 · (커밋 SHA는 아래 참조)

- **한 일:** 플랜 Task 0 브리프대로 상승 전 상태를 먼저 기록한 뒤(초록: L1 20 / L2 1 /
  guardrail 11) `build.gradle.kts`의 `org.springframework.boot` 플러그인을 3.5.0 →
  4.1.1로 올리고 `clean verify guardrails`로 무엇이 깨지는지 관찰했다. 두 가지가 깨졌다.
  - **Jackson 2 → Jackson 3.** Boot 4의 `spring-boot-starter-web`이 이제
    `tools.jackson.core:jackson-databind`(Jackson 3)를 기본으로 끌어오고
    `com.fasterxml.jackson.databind`(Jackson 2)는 더 이상 전이 의존성에 없다.
    테스트 지원 코드 `FixtureLlmPort`(L2 픽스처 재생용, record 두 개만 직렬화)가
    Jackson 2 API를 임포트해 컴파일이 깨졌다. 별도 Jackson 2 의존성을 추가하는 대신
    `import com.fasterxml.jackson.databind.ObjectMapper` →
    `import tools.jackson.databind.ObjectMapper` 한 줄만 바꿨다 — 이미 클래스패스에
    있는 Boot 4 기본 Jackson 3 API로 옮긴 것이고 나머지 코드는 그대로 컴파일된다.
  - **Flyway 자동설정이 별도 아티팩트로 분리됐다.** Boot 4에서
    `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`이
    `spring-boot-autoconfigure`가 아니라 신설된 `org.springframework.boot:spring-boot-flyway`
    (그리고 스타터 `spring-boot-starter-flyway`)로 옮겨갔다. `flyway-core`를 직접
    `implementation`으로 물던 기존 방식은 컴파일도 되고 `flyway-database-postgresql`도
    그대로 통과했지만, **자동설정 클래스 자체가 클래스패스에 없어 Flyway 빈이 하나도
    안 뜨고 마이그레이션이 조용히 스킵됐다.** `FlywayMigrationTest`가
    `pg_extension`에서 `vector` 0건으로 실패해서 드러났다 — 컴파일 에러가 아니라
    런타임 조용한 실패였다. `ApplicationContext.getBeanDefinitionNames()`로 flyway
    관련 빈이 전무함을 직접 확인한 뒤(임시 디버그 코드, 확인 후 원본과 바이트 단위로
    동일하게 복원 — `git diff`로 무변경 확인), `implementation("org.flywaydb:flyway-core")`를
    `implementation("org.springframework.boot:spring-boot-starter-flyway")`로 교체했다.
    `runtimeOnly("org.flywaydb:flyway-database-postgresql")`은 그대로 둔다 — 스타터가
    DB별 모듈까지 끌어오지 않는다. 교체 후 `flywayInitializer`·`flyway` 빈이 뜨고
    마이그레이션이 실행되는 로그(`Creating Schema History table...`)를 직접 봤다.
  - `ddl-auto: validate`, ArchUnit, Testcontainers BOM(1.20.4 고정)은 브리프 예측대로
    영향 없었다 — 엔티티가 아직 없고 컨테이너도 그대로 떴다.
- **결과:** `./gradlew clean verify guardrails` BUILD SUCCESSFUL. 바닥 개수가 상승 전과
  **정확히 동일**하다 — `test` 20건, `integrationTest` 1건, `guardrailTest` 11건(브리프의
  "7건"은 오래된 숫자이고, 실제 상승 전 베이스라인도 11건이었다 — 개수 대조는 브리프 상수가
  아니라 Step 1에서 직접 관찰한 베이스라인과 했다). 해상된 버전: Spring Boot 4.1.1 /
  Spring Framework 7.0.9 / Hibernate 7.4.5.Final / jakarta.persistence 3.2.0 /
  Flyway 12.4.0 — 브리프 표와 일치. `guardrails`는 gitleaks 미설치 경고만 내고 통과.
- **함정:** **컴파일 에러(Jackson)와 조용한 런타임 스킵(Flyway)은 다르게 다뤄야 한다.**
  전자는 빌드가 바로 잡아 주지만, 후자는 `verify`가 "빌드는 성공, 테스트 1건 중 1건
  실패"로만 보여줘서 원인이 Flyway 자동설정 분리라는 것을 스택 트레이스가 알려주지
  않았다. 로그에 Flyway 관련 줄이 **한 줄도 없다**는 부재 자체가 단서였다 — Hikari
  풀 다음에 바로 Hibernate가 뜨는 순서가 상승 전과 달랐다. 빈 목록을 직접 찍어보고서야
  자동설정 클래스 자체가 없다는 것을 확인했다. **의존성이 컴파일되고 러타임 스킵도 없이
  통과하는 형태의 "깨짐"은 이 저장소의 0건 실행 바닥이 못 잡는다** — 바닥은 테스트
  0건 실행만 잡지, 테스트가 도는데 자동설정 빈이 안 뜨는 것은 못 잡는다. 이번에는
  `FlywayMigrationTest` 자체가 그 바닥 역할을 대신했다.
- **다음:** 플랜 Task 1부터 실행

### 2026-09-02 · Claude Code (원격 세션) · Codex 리뷰 5건 반영 · claude/overmind-handover-8njuet

- **한 일:** PR #5의 플랜 초안에 Codex가 P1 4건·P2 1건을 냈고 **전부 사실이었다.**
  하나씩 확인한 뒤 반영했다.
  - **체크섬 경로** — Task 3이 없는 경로를 적고 `git add`에서도 빠뜨렸다. 실제는
    `docs/harness/migration-checksums.txt`(`build.gradle.kts:191`). 새 클론 CI가
    `guardrails`에서 죽었을 것이다
  - **MockMvc `jwt()`** — post-processor가 이미 인증된 토큰을 SecurityContext에 꽂아
    `JwtDecoder`와 validator 사슬을 통째로 건너뛴다. issuer·audience·만료·subject 검증이
    **전부 빠져도 초록이다.** 서명 픽스처를 실제 디코더에 통과시키는 방식으로 바꾸고,
    운영과 같은 validator 사슬을 공유하게 했다
  - **트랜잭션 경계** — `handle`이 port를 순서대로 부르기만 해서 subject가 커밋된 뒤
    insert가 실패하면 빈 PROJECT가 남는다. 스펙 §5.1이 명시적으로 금지한 상태다.
    port 목록에 `adapter-neutral transaction boundary`를 적어 놓고 정작 쓰지 않았다
  - **로그 위생** — 다섯 번째 흐름이 인가 실패여야 하는데 "없는 PROJECT" 오류를 넣었다.
    `webEnvironment = NONE`이라 Security 경로를 아예 지나가지 않는다
  - **예산 테스트** — `limit` 100 × 16 KiB = 1,638,400 < 2 MiB. **예산에 닿을 수가 없다.**
    예산 코드를 통째로 지워도 통과하는 검사였다
- **결과:** 플랜 3641줄 → 4034줄. `guardrails` 11건 / L1 20건 통과, gitleaks 깨끗
- **함정:** **다섯 건 중 셋이 "통과하지만 아무것도 검사하지 않는" 유형이었다.** 이 저장소가
  하네스 단계에서 반복해 잡아낸 바로 그 부류를, 그 이력을 다 읽고 쓴 플랜이 다시 만들었다.
  스스로 게이트를 의심하는 절차를 코드에는 붙였으면서 **테스트 자체가 무엇을 우회하는지는
  검증하지 않았다.** MockMvc `jwt()`가 대표적이다 — API 이름만 보면 인증을 테스트하는
  것처럼 보이는데 실제로는 인증을 건너뛴다.
  둘째: **스펙 §5.4의 2 MiB 예산은 §5.2(limit ≤ 100)와 §4.2(content ≤ 16 KiB) 아래에서
  도달 불가다.** 승인된 스펙 안의 수치 불일치이고, 세 수치 중 하나를 바꿀지는 결정 사항이다.
  플랜은 스펙을 그대로 따르되 예산을 주입 가능하게 만들어 로직만 검증하고, **도달 불가라는
  사실 자체를 못 박는 검사**를 넣었다 — 나중에 수치가 바뀌면 그 검사가 실패하며 알려 준다.
  셋째: **리뷰는 도구를 나눈 값을 했다.** `docs/harness/50-review-protocol.md`가 자기가
  구현한 것을 자기가 리뷰하지 말라고 한 이유가 이번에 실물로 나왔다
- **다음:** 사용자에게 §5.4 수치 불일치를 보고. 로컬에서 Task 0부터


### 2026-09-02 · Claude Code (원격 세션) · 감시 경로 세 목록 동기화 검사 · claude/overmind-handover-8njuet

- **한 일:** 감시 경로 목록이 세 곳(가드 코드 / `AGENTS.md` 절대 규칙 3 /
  `40-guardrails.md` 표)에 중복돼 있는데 주석만 "같아야 한다"고 말하던 것을 게이트로 바꿨다.
  **가드 코드가 진실의 원천이고 문서 둘은 사본이다**(사용자 결정). 문서에 `watched-paths`
  마커 블록을 두고 `WatchedPathSyncGuardTest`가 그 안의 백틱 토큰을 모아 코드 상수와
  집합 비교한다. glob 별표만 떼는 정규화를 쓰고, 그 정규화 규칙 자체도 테스트로 못 박았다
- **결과:** guardrail 7건 → 11건. L1 20건 통과. 썩힘 실험 3종이 각각 정확히 해당 검사만
  깨뜨렸다 — 코드에만 경로 추가(문서 둘 다 실패) / `AGENTS.md` 사본에서만 항목 제거
  (그 문서만 실패) / 마커 제거(그 문서만 실패). 복원 후 초록
- **함정:** **검사를 넣자마자 그 검사가 나를 잡았다.** `40-guardrails.md`에 마커를 넣는
  치환이 공백 불일치로 조용히 적용되지 않았는데, 새 테스트가 "마커가 없습니다"로 실패했다.
  검사가 없었으면 문서에 마커가 없는 채로 커밋됐을 것이고, 그러면 이후 어느 세션도
  그 사본이 코드와 갈라진 것을 모른다.
  둘째: **파싱 검사는 "빈 집합 == 빈 집합"으로 통과할 수 있다.** 마커를 지우거나 블록을
  비우면 양쪽이 비어 조용히 통과한다. 그래서 마커 부재를 명시적 실패로 처리하고,
  원천 목록이 5개 미만이면 실패하는 바닥을 따로 뒀다. 이 저장소가 반복해서 당한
  "통과하지만 아무것도 검사하지 않는" 형태를 검사 자체가 재현하지 않게 하는 장치다
- **다음:** 로컬에서 플랜 Task 0(Boot 4 상승)부터


### 2026-09-02 · Claude Code (원격 세션) · Boot 4 상승 결정과 플랜 개정 · claude/overmind-handover-8njuet

- **한 일:** 사용자 승인에 따라 세 가지를 반영했다.
  - **D-G — Spring Boot 4.1.1.** D-B의 Boot 3 부분을 대체한다. 플랜의 플랫폼 상승을
    **Task 10에서 Task 0으로 앞당겼다** — Task 1~8을 Boot 3.5에서 짜고 테스트한 뒤
    Hibernate 6→7, jakarta.persistence 3.1→3.2가 발밑에서 바뀌면 전부 다시 돌려야 한다.
    제품 코드가 한 줄도 없는 지금이 상승 비용이 가장 싸다
  - **B-1·B-2·B-3 기한을 M0로.** 구현이 M0 밖인 것과 결정이 M0 안인 것은 모순되지 않는다
  - 원격 브랜치 3종 삭제 시도 — **403으로 실패했다.** 아래 함정 참조
- **결과:** `guardrails` 7건 / L1 20건 통과, gitleaks `no leaks found`
- **함정:** **Spring AI 2.0에서 MCP 패키지가 옮겨졌다.** 1.1.x의
  `io.modelcontextprotocol.server.transport..`가 2.0에서는
  `org.springframework.ai.mcp.server.webmvc.transport..`이고, 자동설정도
  `...mcp.server.autoconfigure..` → `...mcp.server.webmvc.autoconfigure..`다.
  **1.1.x 기준 예제를 그대로 옮기면 컴파일되지 않는다.** jar를 열어 확인했다.
  둘째, **그래도 AR-3 충돌은 사라지지 않는다.** `mcp-spring-webmvc:2.0.1`이 여전히
  `io.modelcontextprotocol.sdk:mcp-core`에 의존한다 — 프로토콜 타입은 그 패키지에 남는다.
  Boot 4로 올리면 Task 9가 필요 없어질까 기대했지만 아니었다.
  셋째, **gitleaks-action은 워킹트리가 아니라 커밋 범위를 스캔한다.** 앞 세션 기록에
  적은 시크릿 건은 파일만 고쳐서는 지워지지 않았고, CI가 두 번째로 빨개졌다. 그 문자열이
  **어떤 커밋에도 없도록** 브랜치 히스토리를 정리해야 했다. `--no-git` 스캔이 초록이라고
  안심한 것이 오진이었다 — 게이트가 무엇을 스캔하는지 먼저 읽었어야 했다.
  넷째, **이 환경은 원격 브랜치를 지울 수 없다.** `git push --delete`가 403이고
  (프록시 로그에는 github.com 거부 기록이 없으므로 자격증명 범위 문제로 보인다),
  GitHub MCP에도 브랜치 삭제 도구가 없다. push는 되는데 delete는 안 된다
- **다음:** 감시 경로 세 목록 동기화 검사의 형태를 정한다. 그 뒤 로컬에서 Task 0부터


### 2026-09-02 · Claude Code (원격 세션) · M0 구현 플랜 작성 · claude/overmind-handover-8njuet

- **한 일:** `superpowers:writing-plans`로 M0 구현 플랜을 썼다. 14개 태스크, 각 태스크가
  독립적으로 테스트 가능한 산출물로 끝난다. 쓰기 전에 실제 트리와 Maven Central을 확인해
  **플랜이 존재하지 않는 것을 가리키지 않게** 했다
- **결과:** `docs/superpowers/plans/2026-09-02-overmind-m0.md`. `guardrails` 7건 / L1 20건 통과
- **함정:** 확인하지 않았으면 플랜이 통째로 틀렸을 사실 세 가지가 나왔다.
  1. **AR-3과 MCP 서버가 충돌한다.** 규칙이 `io.modelcontextprotocol..`을 `adapter.out`
     밖에서 금지하는데 MCP 서버는 **진입** 어댑터다. 스펙 §3대로 짜면 게이트가 실패한다.
     규칙을 지우는 대신 LLM SDK와 MCP SDK로 나누고, "코어는 여전히 모른다"를 별도 규칙으로
     못 박는 것으로 플랜에 넣었다(Task 9)
  2. **Spring AI 2.x는 쓸 수 없다.** `spring-ai-starter-mcp-server-webmvc:2.0.1`의 POM이
     `spring-boot-starter-web:4.1.1`을 참조한다 — Boot 4를 요구한다. Boot 3.5에 물리는
     마지막 라인은 **1.1.8**(Boot 3.5.15)이다. 최신 버전을 집었으면 Task 10에서 막혔다
  3. **도메인에 `record`를 쓸 수 없다.** INV-02가 `domain..`의 `toString`을 금지하는데
     record는 `toString`을 바이트코드로 생성한다. 스펙 §8이 "record 대신 안전한 immutable
     class를 고려한다"고 한 것이 이미 게이트로 강제되고 있었다 — 고려가 아니라 제약이다.
  **교훈: 플랜은 스펙만 보고 쓸 수 없다.** 스펙이 옳아도 그것을 받는 트리의 게이트와
  충돌하면 구현 단계에서 멈춘다. 충돌은 스펙에도 트리에도 안 적혀 있고 둘을 겹쳐 볼 때만 보인다.
  넷째: MCP 도구 등록 API와 프로토콜 설정 키는 **확정하지 못했다.** 클래스 이름을 지어내는
  대신 해석된 jar를 직접 열어 보는 명령을 플랜의 첫 스텝으로 넣었다. 모르는 것을 아는 척한
  플랜은 실행자를 더 크게 넘어뜨린다
  다섯째: **플랜 문서 자체가 시크릿 게이트를 밟았다.** 예시로 적은
  `overmind.security.cursor-secret=<32자 hex>` 두 줄이 gitleaks의 `generic-api-key`에
  엔트로피 4.0으로 걸려 CI `guardrails`가 빨개졌다(PR #5, `bab1035`). 로컬에서는
  gitleaks가 없어 조용히 건너뛰므로 초록이었다 — **로컬 초록이 CI 초록이 아니라는 것을
  또 확인했다.** `.gitleaks.toml` 예외로 무마하지 않고, 낱말 반복으로 길이를 채우도록
  테스트 코드를 고쳤다. 잡은 것이 진짜 시크릿이 아니어도 잡은 규칙은 옳다.
  검증은 gitleaks 바이너리를 받아 직접 돌려서 했다 — 푸시된 커밋에서 `leaks found: 2`,
  고친 트리에서 `no leaks found`
- **다음:** 플랜 Task 1 실행. Task 10 전에 의존성 승인을 받는다


### 2026-09-02 · Claude Code (원격 세션) · M0 설계 스펙 확보 · claude/overmind-handover-8njuet

- **한 일:** M0 설계를 `docs/superpowers/specs/`에 저장하고, 스펙 §2가 닫은 A-1~A-4를
  `docs/arch/decisions.md` 확정 표로 옮겼다. 새 결정을 내린 게 아니라 승인된 스펙을
  전사한 것이다. 옮기다 보니 B-1·B-2·B-3의 기한이 조용히 지나 있어 그 사실을 명시했다 —
  셋 다 "M0 브레인스토밍"이 기한인데 M0 설계는 §11에서 범위 밖으로 미뤘다
- **결과:** `guardrails` 7건 / L1 20건 통과. `verify`는 Docker가 없어 미완주
- **함정:** **인터넷이 분리된 환경에서 진행한 설계 작업은 저장소에 도달하지 못한 채로
  사라질 수 있다.** 이번에 Codex 쪽 M0 작업이 그렇게 유실됐고, 사용자가 대화로 다시
  건네주지 않았으면 복구할 방법이 없었다. 도구가 무엇이든 **결론은 푸시할 수 있는 곳에서
  다시 쓴다** — 이 저장소의 log.md·decisions.md 규약이 붙잡으려는 것이 정확히 이 손실이다.
  둘째: 이 커밋이 넓힌 감시 경로에 실물로 걸린 첫 변경이다. `docs/superpowers/`와
  `docs/arch/`만 고쳤고 `src/`는 한 줄도 안 건드렸는데 log.md 동반 갱신이 요구됐다 —
  PR #3이 감시 경로를 넓힌 이유가 바로 이 형태의 세션이다
- **다음:** 이 스펙으로 `superpowers:writing-plans` 실행


### 2026-09-02 · Claude Code (원격 세션) · log.md 가드의 경로 인용 구멍 + 이월 결함 등재 · claude/overmind-handover-8njuet

- **한 일:** 두 가지.
  - **경로 인용 구멍.** `LogUpdatedGuardTest`가 `git diff --name-only`로 변경 경로를 받는데,
    `core.quotePath` 기본값에서 git은 한글이 든 이름을 `"docs/arch/\354\204\244..."`처럼
    따옴표와 8진 이스케이프로 감싼다. 접두사 비교가 전부 빗나가 **감시 경로 변경이 log.md 없이
    통과했다.** `-z`(NUL 구분)로 받고 NUL로 쪼갠다. Codex 리뷰 봇이 PR #4에 남긴 지적이고,
    받아들이기 전에 git 2.43.0에서 직접 재현했다
  - **`settings.gradle.kts`를 감시 경로에 추가.** `build.gradle.kts`는 보면서 빌드 설정의
    나머지 절반은 안 보고 있었다 — 모듈 추가나 툴체인 교체가 기록 없이 지나간다.
    목록이 세 곳(가드 코드 / `AGENTS.md` 절대 규칙 3 / `40-guardrails.md`)에 있어 셋을
    같이 고쳤다. 곁들여 `40-guardrails.md`의 갈라진 표를 복원했다 — #3이 절을 표 중간에
    끼워 넣어 `빈 게이트`·`시크릿` 두 행이 표 밖으로 떨어져 있었다
  - 인수인계 대화에만 있던 이월 결함 7건을 HEAD에 등재했다 (`settings.gradle.kts` 항목은
    이번에 닫혔으므로 6건이 남는다). 같은 세션에서 HEAD 갱신을 따로
    준비했지만 `feat/widen-log-guard`(PR #3)가 같은 일을 더 넓게 하고 있어 그쪽을 기준으로
    삼고 내 재작성은 버렸다
- **결과:** `guardrails` 5건 → 7건. `test`(L1) 20건 통과. 새 테스트 2건은 임시 저장소를
  만들어 실제 git을 돌린다 — 하나는 한글 경로가 필터에 닿는 것을, 다른 하나는 `-z` 없이
  부르면 이름이 인용되어 감시를 빠져나가는 것을 못 박는다. 썩힘 실험 2종(`-z` 제거,
  `splitNulPaths`를 개행 분리로 교체)이 각각 그 테스트를 깨뜨리는 것을 확인했다.
  **`verify`는 완주하지 못했다** — 원격 컨테이너에 Docker가 없어 L2를 못 돌린다. CI가 판정한다
- **함정:** **감시 목록이 맞아도 경로 문자열이 틀리면 뚫리고, 문자열이 맞아도 목록에 없으면
  뚫린다.** 이번에 둘 다 나왔다 — 인용 문제는 전자, `settings.gradle.kts`는 후자다.
  둘 다 "가드는 초록인데 아무것도 안 보는" 같은 부류다.
  **경로를 문자열로 받는 검사는 그 문자열이 파일 이름 그대로라는 것을 먼저
  확인해야 한다.** git은 기본값에서 이름을 인용해서 내보내고, 인용된 이름은 어떤 접두사에도
  걸리지 않는다 — 즉 **오탐이 아니라 미탐**이 나온다. 한국어로 쓰는 저장소에서 `docs/arch/`에
  한글 파일 하나만 놓으면 밟는 구멍이었다.
  둘째: 프로브 파일 이름을 Java로 만들면 안 된다. 파일 이름과 ProcessBuilder 인자는 JVM의
  `sun.jnu.encoding`을 타므로 러너 로케일이 ASCII면 이름이 이미 망가진 채 들어가 검사가
  무의미해진다. 이름을 UTF-8 바이트로 적은 셸 스크립트를 실행해 셸이 바이트를 그대로
  넘기게 했다.
  셋째: 두 세션이 같은 HEAD 블록을 동시에 덮어쓰면 반드시 충돌한다. 세션 기록은 append-only라
  충돌하지 않는데 **HEAD만 overwrite로 정의돼 있어 병렬 세션에 구조적으로 취약하다.**
  지금은 사용자 1명이라 감당되지만, 도구를 병렬로 돌리면 "누가 HEAD를 소유하는가"를 정해야 한다.
  넷째: 인수인계 문서의 환경 지침은 로컬 Windows 기준이라 원격 세션에 적용되지 않는다.
  여기서는 `JAVA_HOME` export도 MSYS 경로 변환도 필요 없고, 대신 Docker와 gitleaks가 없다
- **다음:** Codex의 M0 플랜 검토. A-1·A-2가 실제로 닫혔는지부터 본다

### 2026-09-02 15:10 · Claude Code · log.md 가드 감시 경로 확장

- **한 일:** `LogUpdatedGuardTest`의 감시 경로에 `docs/superpowers/`, `docs/arch/`, `docs/requirements/`, `AGENTS.md`, `CLAUDE.md` 추가. `AGENTS.md` 절대 규칙 3과 `docs/harness/40-guardrails.md`의 목록을 같은 값으로 맞춤. HEAD 블록을 현재 상태로 갱신
- **결과:** 새 경로 5개 전부 발화 확인. 감시 밖 경로는 통과, 감시 경로 + log.md 동반도 통과. `verify`/`guardrails` 모두 통과
- **함정:** 스펙·플랜만 쓰는 세션은 `src/`를 한 줄도 건드리지 않는다. 그래서 확장 전에는 **설계 세션 하나가 통째로 로그에 흔적 없이 지나갈 수 있었다** — 요구사항 2("모든 작업물이 기록된 log.md")와 정면으로 어긋나는 구멍이었다. 감시 목록이 세 곳(가드 코드 / `AGENTS.md` / `40-guardrails.md`)에 중복되어 있으므로, 하나만 고치면 문서가 말하는 규칙과 강제되는 규칙이 갈라진다. 세 곳을 같이 고칠 것
- **다음:** Codex의 M0 플랜 검토

### 2026-09-02 14:30 · Claude Code · 스킬 벤더링

- **한 일:** superpowers 스킬 14종을 `.claude/skills/`에 복사(6.3.0, MIT). `README.md`로 출처·라이선스·갱신 절차를 남기고, `AGENTS.md` 라우팅과 `CLAUDE.md`에 연결
- **결과:** Codex를 비롯한 어느 도구든 같은 작업 절차를 읽을 수 있다. Claude Code는 계속 플러그인(`superpowers:*`)을 쓴다
- **함정:** 스킬 파일이 있다고 그 절차를 실행할 수 있는 것은 아니다. `subagent-driven-development`는 서브에이전트를 띄우는 도구가 있어야 그대로 돌아간다. 없으면 컨텍스트 격리만 포기하고 같은 게이트 순서를 순차로 돌면 된다 — 절차의 값은 대부분 격리가 아니라 게이트 순서에 있다. 선별해서 일부만 넣는 대신 14종 전부 넣은 이유는, 도구별 지원 범위를 지금 단정할 근거가 없었기 때문이다
- **다음:** M0 브레인스토밍

### 2026-09-02 14:05 · Claude Code · I-3 후속 2건(단순 이름 충돌 · @Tag 주석 오독) · feat/harness HEAD

- **한 일:** 머지 직전 재리뷰가 낸 후속 2건을 닫았다.
  - **후속 1 — 단순 이름 충돌로 생기는 조용한 L1 우회.** `parseInto`는 타입을 단순 이름으로
    키잉하고 파일을 정렬 순서로 읽으므로, 서로 다른 패키지가 같은 최상위 이름을 쓰면 뒤에 읽힌
    쪽이 앞을 덮는다. 키잉 방식은 그대로 두고(상속 절이 단순 이름으로 적혀 패키지 키는 임포트
    해석을 요구한다) `top_level_simple_names_are_unique()`를 추가해 **충돌한 두 파일 이름을
    대며** 실패하게 했다
  - **후속 2 — 이번 재작성이 낸 `@Tag` 회귀.** `firstStringArg`가 위치는 지워진 소스에서 찾고
    값은 **원본**에서 읽어, `@Tag(/* was "integration" */ "unit")`의 주석 속 따옴표가 태그 값이
    됐다. `scrub`을 `blankLiterals` 플래그로 갈라 **주석만 지운 소스**(`scrubComments`)를 만들고
    태그 값을 거기서 읽는다. 전부 지운 소스는 문자열 내용까지 비어 값을 못 읽고, 원본은 주석에
    속는다 — 세 소스가 길이가 같아 오프셋을 공유하기에 가능한 분업이다
- **결과:** `clean verify` 통과 / `guardrails` 통과. L1 19건 → 20건. 후속 1은 `probea`/`probez`에
  같은 이름의 더러운/깨끗한 쌍을 심어 **게이트 본체는 그대로 초록인 채** 새 검사만 두 파일 이름을
  대며 실패하는 것을 확인했고(그 실행 로그에 Spring/Hikari 기동·종료가 남아 우회가 실물임을 증명),
  후속 2는 같은 프로브를 옛 게이트(exit 0)와 새 게이트(exit 1, 클래스명 적시)로 대조했다.
  회귀 재확인: 줄바꿈된 `extends`와 메타 애노테이션 프로브가 여전히 잡힌다. 썩힘 실험 3종
  (`TYPE_DECL`·`scrub`·`scrubComments` 무력화)이 각각 다른 테스트를 시끄럽게 깨뜨린다
- **함정:** **우회를 만든 것은 공격자가 아니라 정렬 순서였다.** `com.overmind.probez.CollideTest`가
  `com.overmind.probea.CollideTest`를 덮자 `@SpringBootTest` + `PostgresTestBase`를 단 더러운 쪽이
  맵에서 사라지고, Spring 컨텍스트와 Postgres 컨테이너가 뜨는 채로 `./gradlew test`가 초록이 됐다.
  두 패키지가 우연히 이름을 겹치는 것만으로 게이트가 꺼진다. 교훈: **식별자를 요약해서 키로 쓰는
  검사는 그 요약이 유일하다는 것을 스스로 검사해야 한다.** 요약의 충돌은 오탐이 아니라 미탐을 낸다.
  둘째 함정: 스크러빙은 **무엇을 지우느냐가 용도마다 다르다.** 구조 파싱은 주석+리터럴을 전부
  지운 소스를, 값 읽기는 주석만 지운 소스를 필요로 한다. 하나로 겸용하려다 값을 원본에서 읽으면
  주석이 값이 되고, 지운 소스에서 읽으면 값이 사라진다
- **다음:** 브랜치 머지. 이후 M0 도메인 브레인스토밍

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
