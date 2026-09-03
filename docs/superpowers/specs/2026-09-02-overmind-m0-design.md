# OverMind M0 설계

**상태:** 사용자 승인  
**작성일:** 2026-09-02  
**범위:** observation 영속화와 원격 `remember_memory`/`recall_memory` MCP 도구

## 1. 목적과 범위

M0는 canonical memory의 축소판이 아니다. 원시 observation을 저장하고 다시 읽으며 실제 사용 패턴을 확인하는 첫 번째 수직 슬라이스다.

구현 범위:

- PostgreSQL의 `memory_subject`, `observation` 테이블
- 원격 HTTPS Streamable HTTP MCP 서버
- `remember_memory`, `recall_memory` 도구
- USER와 PROJECT subject
- 관리형 OAuth 2.1/OIDC 인증
- `memory:read`, `memory:write` scope
- subject 필터와 최신순 keyset pagination

M0 recall은 canonicalization이나 의미 검색을 하지 않는다. USER와 선택한 PROJECT의 raw observation을 `observed_at` 최신순으로 반환한다.

## 2. 확정된 선행 결정

### 2.1 쓰기 경로

`remember_memory` 성공은 observation이 PostgreSQL에 내구성 있게 저장됐다는 의미다.

- observation 저장까지는 동기 처리한다.
- M2 이후 extraction과 canonicalization만 비동기로 추가한다.
- fast/async 이중 쓰기 경로는 만들지 않는다.

### 2.2 Replay 불변식

- 일반 도메인 처리에서 observation은 append-only event log다.
- 정정은 기존 observation 수정이 아니라 새 observation으로 표현한다.
- 향후 canonical memory는 observation으로부터 재구축 가능한 materialized view다.
- 사용자 forget과 개인정보 삭제는 append-only 불변식의 명시적인 예외다.
- forget의 구체적인 정책은 M6에서 별도로 설계한다.

### 2.3 멱등성

- 호출자는 논리적인 remember 작업마다 `idempotency_key`를 반드시 제공한다.
- 재시도에는 같은 키를 사용한다.
- 동일 키와 동일 요청은 기존 observation을 반환한다.
- 동일 키에 의미 필드가 다른 요청은 conflict다.
- 서버 생성 UUID나 시간 버킷 hash로 호출자 멱등성을 대체하지 않는다.

### 2.4 파이프라인 버전

- 실제로 실행된 단계의 버전만 기록한다.
- M0는 direct MCP ingestion과 입력 schema version 1만 기록한다.
- 아직 존재하지 않는 extractor, classifier, embedding의 컬럼이나 테이블은 만들지 않는다.

## 3. 아키텍처

M0는 최소 Hexagonal Vertical Slice로 구현한다.

```text
Streamable HTTP MCP adapter
  → RememberMemory / RecallMemory application use cases
    → domain values and policies
      → SubjectRepository / ObservationRepository ports
        → JPA/PostgreSQL adapters
```

### Domain

도메인이 소유하는 개념:

- `Observation`
- `MemorySubject`
- `SubjectType`: `USER`, `PROJECT`
- `SourceReference`
- `ObservationContent`
- `IdempotencyKey`
- `ObservedAt`

도메인은 Spring, JPA, MCP, OAuth/OIDC 타입에 의존하지 않는다.

도메인 생성 규칙:

- content는 공백만으로 구성될 수 없다.
- content는 UTF-8 기준 최대 16 KiB다.
- subject type은 USER 또는 PROJECT다.
- PROJECT에는 유효한 안정 key가 필요하다.
- `observed_at`은 서버 시각보다 정확히 5분 미래까지 허용한다.
- 5분을 초과한 미래 시각은 거부한다.
- provider-neutral source reference의 모든 필드가 필요하다.
- 생성된 observation을 일반 도메인 흐름에서 수정하지 않는다.
- 시간 검증에는 테스트에서 교체 가능한 `Clock`을 사용한다.

### Application

application은 다음 use case를 제공한다.

- `RememberMemory`
- `RecallMemory`

필요한 최소 port:

- USER subject 조회 또는 생성
- PROJECT subject 조회 또는 원자적 생성
- idempotency key를 이용한 observation 조회
- observation 저장
- USER와 선택한 PROJECT에 대한 keyset 조회
- adapter-neutral transaction boundary
- 테스트에서 교체 가능한 `Clock`

generic repository, event bus, canonicalization port, async job abstraction은 미리 만들지 않는다.

### Adapters

- inbound MCP adapter는 MCP schema를 application command/query로 변환한다.
- security adapter는 OAuth issuer, audience, subject와 scope를 검증한다.
- persistence adapter는 JPA mapping과 PostgreSQL keyset query를 구현한다.
- config는 Spring bean과 필수 외부 설정을 fail-closed로 연결한다.

MCP handler는 JPA repository를 직접 호출하지 않는다. JPA entity도 domain/application API로 노출하지 않는다.

별도 REST remember/recall controller와 stdio adapter는 만들지 않는다.

## 4. 데이터 모델

### 4.1 `memory_subject`

```text
id           UUID primary key
type         USER | PROJECT
subject_key  nullable string
created_at   timestamptz
```

제약:

- USER는 정확히 하나만 존재한다.
- USER의 `subject_key`는 `NULL`이다.
- PROJECT의 `subject_key`는 필수다.
- PROJECT key는 1~128자의 소문자 ASCII 안정 키다.
- 정규식은 `[a-z0-9][a-z0-9._-]*`다.
- `(type, subject_key)` unique constraint를 둔다.
- USER 단일성을 보장하는 partial unique index를 둔다.
- USER와 PROJECT는 application의 원자적 find-or-create 경로에서 생성한다.
- PROJECT 표시 이름은 identity와 분리한다.
- PROJECT rename, merge, delete 및 관리 도구는 M0 범위가 아니다.

### 4.2 `observation`

```text
id                       UUID primary key
subject_id               UUID foreign key → memory_subject
idempotency_key          string
content                  text
observed_at              timestamptz
created_at               timestamptz
source_client            string
source_conversation_id   string
source_message_id        string
ingestion_type           DIRECT_MCP
input_schema_version     integer = 1
```

제약:

- `idempotency_key`: 필수, UTF-8 최대 256 bytes, 전역 unique
- `content`: 필수, nonblank, UTF-8 최대 16 KiB
- `source_client`: 필수, UTF-8 최대 128 bytes
- conversation/message ID: 필수, 각각 UTF-8 최대 512 bytes
- `observed_at`: 호출자가 제공하는 RFC 3339 timestamp with offset
- `created_at`: 서버가 기록하는 저장 시각
- timestamp는 UTC instant로 변환할 수 있어야 한다.
- `observed_at`은 microsecond 단위로 정확히 표현할 수 있어야 한다. 그보다 작은
  유효 소수 부분이 있으면 `INVALID_ARGUMENT`로 거부하며 반올림·절삭하지 않는다.
  PostgreSQL `timestamptz` 저장 후에도 §4.3의 정확한 비교와 동일 요청 재시도를
  보장하기 위한 T5 정밀도 보완이다 (2026-09-03). 소수점 표기의 끝 0은 제한하지 않는다.
- subject foreign key에는 cascade delete를 두지 않는다.
- 일반 persistence port에는 observation update/delete 연산을 두지 않는다.
- M0에서는 append-only DB trigger를 추가하지 않는다.

### 4.3 멱등 비교

기존 `idempotency_key`가 있으면 다음 의미 필드를 정확하게 비교한다.

- subject type/key
- content 원문
- `observed_at`
- source reference 전체
- ingestion type
- input schema version

전부 같으면 같은 `observation_id`와 `created: false`를 반환한다.

하나라도 다르면 `IDEMPOTENCY_CONFLICT`다.

### 4.4 조회 인덱스

```text
(subject_id, observed_at DESC, created_at DESC, id DESC)
```

USER와 선택한 PROJECT의 ID를 먼저 구한 뒤 `subject_id IN (...)`으로 합쳐 조회한다.

FTS와 pgvector는 사용하지 않는다.

## 5. MCP 계약

### 5.1 `remember_memory`

입력:

```text
idempotency_key: string
subject:
  type: USER | PROJECT
  key: string | absent
content: string
observed_at: RFC 3339 timestamp with offset
source:
  client: string
  conversation_id: string
  message_id: string
input_schema_version: 1
```

규칙:

- USER에는 `subject.key`를 받지 않는다.
- PROJECT에는 유효한 `subject.key`가 필수다.
- 인증 identity는 요청 body에서 받지 않는다.
- `created_at`과 `ingestion_type`은 서버가 결정한다.
- `observed_at`의 microsecond 정밀도 제한은 §4.2를 따른다.
- 한 번의 remember 호출은 정확히 하나의 raw observation을 만든다.
- 서버는 content를 문장이나 사실 단위로 분해하지 않는다.

성공 응답:

```text
status: STORED
observation_id: UUID
created: true | false
```

- `created: true`: 새 observation 생성
- `created: false`: 동일 요청의 멱등 재시도
- 응답에 content와 source reference를 반복하지 않는다.
- `STORED`는 observation 저장만 보장하며 canonicalization 완료를 의미하지 않는다.

subject 조회/생성, 멱등 검사, observation insert와 결과 결정은 하나의 transaction에서 수행한다.

실패한 remember가 빈 PROJECT subject만 남겨서는 안 된다.

### 5.2 `recall_memory`

입력:

```text
project_key: optional string
limit: optional integer = 20
cursor: optional opaque string
```

규칙:

- project key가 없으면 USER observation만 조회한다.
- project key가 있으면 USER와 해당 PROJECT observation을 함께 조회한다.
- 없는 PROJECT는 `SUBJECT_NOT_FOUND`다.
- recall은 subject를 생성하지 않는다.
- PROJECT가 존재하지만 observation이 없으면 정상 빈 결과다.
- limit 기본값은 20이다.
- limit 허용 범위는 1~100이다.
- 여러 PROJECT를 동시에 조회하지 않는다.

응답:

```text
observations:
  - observation_id: UUID
    subject:
      type: USER | PROJECT
      key: PROJECT일 때만 존재
    content: string
    client: string
    observed_at: timestamp
next_cursor: optional opaque string
```

일반 recall 응답에 포함하지 않는 값:

- source conversation ID
- source message ID
- `created_at`
- idempotency key
- USER 내부 key
- OIDC subject
- total count

### 5.3 정렬과 cursor

정렬:

```text
observed_at DESC, created_at DESC, id DESC
```

- persistence adapter는 최대 `limit + 1`개를 읽어 다음 페이지 존재 여부를 판단한다.
- cursor는 stateless opaque token이다.
- cursor에는 version, 마지막 정렬 위치와 subject-filter fingerprint를 넣는다.
- cursor payload에는 content, source ID, USER identity, project key 원문을 넣지 않는다.
- 서버 HMAC으로 cursor를 서명한다.
- 서명, version 또는 filter가 일치하지 않으면 `INVALID_CURSOR`다.
- 첫 페이지로 조용히 되돌리지 않는다.
- 페이지 사이에 신규 observation이 추가될 수 있다.
- keyset cursor는 이미 반환된 항목의 중복을 막지만 여러 페이지가 하나의 DB snapshot이라고 보장하지 않는다.

### 5.4 응답 예산

- 한 recall 페이지의 content 총 UTF-8 크기 예산은 2 MiB다.
- limit에 도달하기 전에 예산이 차면 마지막으로 완전히 포함할 수 있는 observation까지만 반환한다.
- `next_cursor`는 실제로 마지막에 반환한 observation을 기준으로 만든다.
- 단일 observation은 최대 16 KiB이므로 적어도 한 건은 반환할 수 있다.

### 5.5 오류 코드

- `INVALID_ARGUMENT`
- `SUBJECT_NOT_FOUND`
- `IDEMPOTENCY_CONFLICT`
- `INVALID_CURSOR`
- `UNAUTHENTICATED`
- `PERMISSION_DENIED`
- `INTERNAL_ERROR`

오류 응답에 다음을 포함하지 않는다.

- content
- source ID
- idempotency key
- project key 원문
- token/claim 값
- DB 예외
- stack trace

## 6. 인증과 보안

### Transport

- HTTPS Streamable HTTP MCP endpoint를 제공한다.
- legacy SSE 전용 transport는 제공하지 않는다.
- stdio transport는 제공하지 않는다.
- 별도 REST remember/recall endpoint는 제공하지 않는다.
- TLS는 신뢰된 reverse proxy 또는 배포 플랫폼에서 종료할 수 있다.
- 애플리케이션 포트를 외부에 직접 노출하지 않는다.
- forwarded header는 지정된 proxy에서만 신뢰한다.

### OAuth 2.1/OIDC

OverMind는 authorization server를 만들지 않고 관리형 OIDC issuer를 사용하는 resource server로 동작한다.

필수 설정:

- issuer
- required audience
- allowed subject 정확히 하나
- cursor HMAC secret

production profile에서는 하나라도 비어 있으면 애플리케이션 시작을 실패시킨다.

검증 대상:

- token signature
- issuer
- expiry/not-before
- audience
- `sub` allowlist

identity에는 email이나 표시 이름을 사용하지 않는다.

권한:

- `memory:read`: `recall_memory`
- `memory:write`: `remember_memory`

M0에는 delete tool과 `memory:delete` 동작이 없다.

static bearer token fallback과 query parameter token은 허용하지 않는다.

## 7. 트랜잭션과 동시성

PostgreSQL `READ COMMITTED`와 unique constraint를 사용한다.

애플리케이션 전역 lock과 serializable isolation은 사용하지 않는다.

- 동일 PROJECT key 동시 생성은 한 subject로 수렴한다.
- 동일 idempotency key와 동일 요청의 동시 실행은 observation 한 건과 두 멱등 성공으로 수렴한다.
- 동일 idempotency key와 서로 다른 요청은 하나만 저장되고 다른 요청은 conflict다.
- 예상한 unique 충돌만 멱등 경로로 변환한다.
- 다른 무결성 오류를 성공으로 처리하지 않는다.
- unique 경쟁 처리는 실패해서 rollback-only가 된 transaction 안에서 재조회하는 방식으로 구현하지 않는다.
- PostgreSQL `INSERT ... ON CONFLICT DO NOTHING RETURNING` 기반 insert-or-find 방식을 사용한다.
- recall은 read-only transaction이다.

## 8. 로그와 관측성

로그에 허용되는 metadata:

- request/correlation ID
- tool 이름
- 성공/오류 코드
- `created` 여부
- 반환 개수
- 처리 시간

로그에 남기면 안 되는 값:

- content
- source reference
- idempotency key
- project key
- OAuth token
- token claim
- cursor 원문

L2 테스트에서는 각 민감 값에 고유 magic string을 넣고 성공, validation 실패, DB conflict, invalid cursor와 authorization failure 흐름의 전체 캡처 로그에서 해당 문자열이 없는지 검사한다.

민감한 domain value는 자동 생성된 `toString()`으로 원문이 노출되지 않도록 Java record 대신 안전한 immutable class 사용을 고려한다.

## 9. 테스트 전략

### L1

- content UTF-8 16 KiB 경계와 nonblank
- 다중 byte 문자열 byte 단위 검증
- PROJECT key 형식과 128자 경계
- source reference 필수값과 byte 상한
- `observed_at` 정확히 서버 시각 +5분 허용
- +5분 초과 거부
- 새 remember와 동일 요청 재시도
- 동일 idempotency key에 의미 필드가 다른 모든 conflict 변형
- USER와 PROJECT subject 선택
- USER-only recall
- USER+PROJECT recall
- limit 기본 20과 범위 1~100
- cursor 서명, version과 filter 오류

### L2 PostgreSQL/Testcontainers

- 빈 PostgreSQL에 Flyway 전체 migration 적용
- USER 단일성
- PROJECT key 유일성
- idempotency key 유일성
- content와 subject DB check
- cascade delete 부재
- PROJECT 동시 생성
- 동일/상이한 idempotency 요청 동시 실행
- 실패 transaction이 빈 PROJECT를 남기지 않음
- 동일 시각 경계의 안정적인 keyset 정렬
- 페이지 사이 신규 insert에도 기존 결과 중복 없음
- USER와 PROJECT 결과의 전역 최신순 혼합
- 2 MiB 예산 조기 종료 후 cursor로 나머지 순회
- 민감 값 로그 부재

### MCP와 보안

- M0에는 `remember_memory`, `recall_memory` 두 tool만 노출
- issuer, audience, subject, expiry와 scope 성공·실패 조합
- read scope로 remember 불가
- write scope만으로 recall 불가
- production 필수 설정 누락 시 시작 실패
- MCP schema와 오류 코드 contract
- stack trace와 DB 오류 비노출
- 실제 원격 MCP client를 사용한 HTTPS smoke-test 절차

기본 `verify`는 실제 외부 OIDC provider를 호출하지 않는다. JWT fixture와 로컬 test issuer를 사용한다.

M0에는 실제 LLM을 호출하는 L3 evaluation이 없다.

## 10. Acceptance Criteria

1. 서로 다른 MCP client가 같은 인증 사용자로 접속할 수 있다.
2. client A가 저장한 USER observation을 client B가 recall할 수 있다.
3. client A가 저장한 PROJECT observation을 client B가 같은 project key로 recall할 수 있다.
4. PROJECT recall 한 번으로 USER와 해당 PROJECT observation이 함께 최신순 반환된다.
5. 성공한 remember 직후 observation이 PostgreSQL에 내구성 있게 존재한다.
6. 동일 idempotency key 재시도는 observation을 중복 생성하지 않는다.
7. observation 일반 처리 경로에는 update/delete가 없다.
8. keyset cursor로 observation을 중복 없이 순회할 수 있다.
9. 민감한 payload와 식별자가 애플리케이션 로그에 나타나지 않는다.
10. 인증, 권한과 필수 설정 오류는 fail-closed로 동작한다.

## 11. M0 제외 범위

- LLM extraction
- canonical memory와 canonicalization
- async job과 queue
- fast/async 이중 write path
- slot registry
- evidence와 snapshot
- FTS, embedding과 pgvector 검색
- semantic query
- CURRENT/HISTORICAL mode
- raw conversation import와 bootstrap
- forget과 PrivacyPurger
- observation 수정·병합 UI
- PROJECT rename/merge/delete 관리 도구
- 다중 사용자와 RBAC
- USER/PROJECT 이외 subject
- stdio MCP
- 별도 REST remember/recall API
- 실제 LLM을 호출하는 L3 evaluation

기존 pgvector 확장은 M0 recall에서 사용하지 않는다. M1/M2 기능을 M0 구현에 미리 넣지 않는다.
