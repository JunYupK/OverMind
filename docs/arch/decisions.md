# 결정 레지스터

확정된 결정과 아직 열려 있는 결정을 한 곳에 모은다.
사료(`baseline-v0.1.md`, `review-v0.1.md`)는 수정하지 않는다. 결정이 바뀌면 여기에 기록한다.

## 확정

| ID | 결정 | 근거 | 확정일 |
|---|---|---|---|
| D-A | 문서 권위: baseline은 아키텍처 지향점, review는 실행 순서(M0~M6). review §6 C-1~C-7은 반영 대상 | 하네스 스펙 §1 | 2026-09-01 |
| D-B | 스택: Java 21 + Spring Boot 3. ArchUnit으로 아키텍처 불변식 강제 | 하네스 스펙 §1 | 2026-09-01 |
| ~~D-B의 Boot 버전~~ | **D-G가 대체한다** | | |
| D-C | log.md: 2단 단일 파일 (HEAD 덮어쓰기 + append-only 세션 기록) | 하네스 스펙 §4 | 2026-09-01 |
| D-D | 루프: 기계 게이트 자동 반송(상한 3회), 리뷰는 도구 교차 검증 후 사람 판단 | 하네스 스펙 §5 | 2026-09-01 |
| D-E | 테스트 3계층: L1 fake / L2 Testcontainers+녹화재생 / L3 실 LLM | 하네스 스펙 §6 | 2026-09-01 |
| D-F | 하네스 구축 범위: Walking Skeleton. 평가자는 마일스톤별 활성화 | 하네스 스펙 §1 | 2026-09-01 |
| A-1 | **Async only.** fast/async 이중 쓰기 경로를 만들지 않는다. observation 저장까지 동기, extraction·canonicalization만 M2 이후 비동기 | M0 설계 §2.1 | 2026-09-02 |
| A-2 | **Replay 불변식 채택.** observation은 append-only event log, canonical memory는 재구축 가능한 materialized view. 정정은 새 observation. forget·개인정보 삭제는 명시적 예외이며 M6에서 설계 | M0 설계 §2.2 | 2026-09-02 |
| A-3 | **호출자가 `idempotency_key`를 제공한다.** 재시도는 같은 키. 동일 키+동일 요청은 기존 observation 반환, 의미 필드가 다르면 conflict. 서버 생성 UUID나 시간 버킷 hash로 대체하지 않는다 | M0 설계 §2.3 | 2026-09-02 |
| A-4 | **실행된 단계의 버전만 기록한다.** M0는 `ingestion_type`(DIRECT_MCP)과 `input_schema_version`(1)만. 아직 없는 extractor·classifier·embedding 컬럼은 만들지 않는다 | M0 설계 §2.4 | 2026-09-02 |
| D-G | **Spring Boot 4.1.1로 올린다** (D-B의 Boot 3 부분을 대체). Java 21 유지 — Boot 4 기준선은 Java 17이다. Spring AI 2.0.1 + MCP SDK 2.0.1을 쓴다 | 사용자 승인 2026-09-02 | 2026-09-02 |

### T5 관측 시각 정밀도 보완 — 2026-09-03

M0 `remember_memory`는 microsecond 단위로 정확히 표현할 수 있는 `observed_at`만
받는다. 더 작은 유효 소수 부분은 `INVALID_ARGUMENT`로 거부한다. PostgreSQL
`timestamptz`에 반올림되어 저장된 값과 원래 입력을 비교하면 같은 재시도도 conflict가
되므로, 정확 비교 계약을 유지하면서 입력 경계를 명시했다. 반올림·절삭이나 V2 변경은
하지 않는다. 이 제한은 Codex가 T5 구현 중 정한 보완이며, 클라이언트가 더 정밀한 시각을
보내야 한다면 원래 값을 보존하는 별도 스키마 설계가 필요하다.

## 열려 있음

| ID | 안건 | 출처 | 결정 기한 |
|---|---|---|---|
| B-1 | Slot Registry 범위 (dynamic slot 폐기 여부) | review §5 | **M0 완료 전** |
| B-2 | Snapshot 테이블화 시점 | review §5 | **M0 완료 전** |
| B-3 | Bootstrap 범위와 비용 상한 **수치** | review §5 | **M0 완료 전** |
| B-4 | L3 비용 상한을 **강제하는 장치** — 호출 수·토큰·금액을 무엇이 세고, 어디서 실행을 중단시키는가 | 스펙 §6.4, R6-AC1 | M5 이전 |

**B-1·B-2·B-3의 기한을 M0로 다시 묶었다** (사용자 결정 2026-09-02). 원래 기한은
"M0 브레인스토밍"이었는데 M0 설계가 이들을 닫지 않고 §11에서 **구현 범위** 밖으로 미루면서
기한이 조용히 사라졌다.

**구현이 M0 밖인 것과 결정이 M0 안인 것은 모순되지 않는다.** 셋 다 M2 이후에 만들지만,
무엇을 만들지는 M0가 끝나기 전에 정해야 한다 — M0의 목적 자체가 "매일 써보며 실제로 뭐가
필요한지" 확인하는 것이고, slot registry 범위·snapshot 시점·bootstrap 수치는 그 사용
경험이 있어야 근거를 갖고 정할 수 있다. M0를 다 쓰고도 안 정하면 M2 스키마를 근거 없이
짜게 된다.

**B-4가 B-3과 별개인 이유:** B-3은 숫자를 정하는 결정이고, B-4는 그 숫자를 지키게 만드는
기계를 정하는 결정이다. 지금 트리에 있는 것은 `docs/requirements/R1-R6.md` R6-AC1의
산문뿐이고 `evaluationTest`는 상한 없이 실 LLM을 부른다. 하네스 전수 리뷰가 확인한 것이
바로 이것이다 — 산문으로만 존재하는 규칙은 강제되지 않는다. L3 스위트가 실제로 비용을
쓰기 시작하기 전에 장치가 있어야 한다.

## 반영 대기 결함 — M2 이후 도메인 스펙

C-1 async job 순서 보장 / C-2 N:M evidence purge 정책 / C-3 청크 단위 삭제 /
C-4 snapshot 시간 무효화 / C-5 canonical_text 템플릿 버전 / C-6 PENDING UX 구멍 /
C-7 동시성·nullable·confidence 컬럼

C-1은 불변식 INV-06으로 카탈로그에 선등록되어 있다.
