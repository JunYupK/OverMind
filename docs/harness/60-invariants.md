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
| INV-09 | 동일 idempotency_key로 observation이 중복 적재되지 않는다 | L2 | M0 | 부분 구현 |
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
- **검사:** L2 — 같은 키로 두 번 호출한 뒤 행 수를 센다. DB unique 제약이 1차 방어선.
  `SchemaConstraintTest`가 서로 다른 subject 사이에서도 같은 키의 중복 insert를
  거부하는지 SQL 상태 코드와 제약 이름으로 확인한다.
  `InsertOrFindConcurrencyTest`는 8개 동시 요청의 반환 ID와 실제 행 수, 기존 행 반환,
  예상하지 않은 무결성 오류 전파, 여러 port 호출의 commit/rollback을 검사한다.
- **활성:** M0 · **상태:** 부분 구현 (T3 DB 전역 unique와 T4 어댑터 동시성 검증 구현.
  remember 유스케이스의 멱등 응답·의미 필드 conflict는 후속 태스크에서 검증)

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
