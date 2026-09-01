# Overmind — 설계 리뷰 및 착수 전 결정 사항

> Overmind: 여러 AI 클라이언트가 공유하는 canonical memory service.
> (설계 문서 상의 기존 명칭 "Canonical Memory Service"와 동일 대상)

> 이 문서는 이전에 작성된 Architecture Baseline v0.1에 대한 외부 리뷰다.
> Baseline 문서를 대체하지 않고, **어떤 순서로 무엇을 결정할지**를 지정한다.
>
> **이 문서를 읽는 에이전트에게:** Baseline v0.1은 완성된 설계가 아니라
> 검증되지 않은 가설의 집합이다. 그것을 전제로 깔고 세부만 파고들지 마라.
> 아래 §2의 요구사항이 먼저이고, §3의 검증 결과에 따라 Baseline의 절반은
> 폐기될 수 있다. 이 리뷰 자체도 실제 데이터 없이 작성된 추측이므로,
> 동의보다 반증을 우선하라.

---

## 1. 프로젝트 한 줄 정의

**Overmind** — 여러 AI 클라이언트(Claude Chat / ChatGPT / Claude Code / Codex)가
공유하는 단일 개인 메모리 서비스. MCP로 노출하며, 대화에서 관측된
사실(observation)을 정규화(canonicalization)해 시점별로 유효한 사실을 관리한다.

명명 의도: 개별 클라이언트 세션은 매번 소멸하고 서로의 기억에 접근할 수 없다.
그 위에 지속하는 단일 상위 기억층을 두는 것이 이 프로젝트의 정체성이다.
`recall_memory` / `remember_memory` / `forget_memory`는 MCP 상에서
`overmind__*` prefix로 노출된다.

**사용자: 1명 (개발자 본인). 이 제약이 모든 설계 판단의 기준이다.**

---

## 2. 요구사항 (설계보다 먼저 고정)

Baseline v0.1에는 요구사항 문장이 하나도 없고 전부 solution 형태다.
아래를 먼저 확정하고, 각 아키텍처 요소가 이 중 어느 것에 기여하는지
설명 가능한 상태로 만든다.

### 2.1 사용자 요구사항

- R1. 새 대화를 시작할 때 내 상황(직무, 현재 학습 주제, 진행 중 프로젝트)을 다시 설명하지 않아도 된다.
- R2. 6개월 전에 접은 관심사가 "현재 관심사"로 답변에 섞이지 않는다.
- R3. 명시적으로 "기억해"라고 말하지 않아도 지속적으로 유효한 사실은 남는다.
- R4. 잊어달라고 한 정보는 어떤 검색 경로로도 다시 나오지 않는다.
- R5. Claude에서 말한 내용이 ChatGPT에서도 동일하게 반영된다.
- R6. 과거 대화 아카이브가 있으므로 콜드 스타트 없이 시작한다.

### 2.2 요구사항별 acceptance criteria (예시 형식)

각 요구사항은 실행 가능한 테스트로 환산되어야 한다. "Canonical correctness"는
테스트가 아니다.

```
R2-AC1:
  given  canonical: learning.primary_focus = "Kafka" (valid_until = 6개월 전)
         canonical: learning.primary_focus = "CKAD"  (valid_from = 1개월 전)
  when   recall_memory(mode=CURRENT, scope=learning)
  then   응답에 "Kafka"가 현재 사실로 포함되지 않는다

R4-AC1:
  given  forget_memory("일본 여행") 실행 완료
  when   recall_memory(mode=HISTORICAL, query="여행")
         AND raw fallback 검색까지 도달
  then   일본 여행 관련 문자열이 응답 어디에도 없다
```

writing-plans 단계 전까지 R1~R6 각각에 최소 2개씩 AC를 작성한다.

---

## 3. 검증되지 않은 핵심 가정 — 최우선 과제

Baseline 전체가 하나의 가정 위에 서 있다.

> **canonicalization된 메모리가 raw chunk RAG보다 낫다.**

이 가정에 대한 검증 계획이 문서에 없다. golden set은 §33에 KPI 이름만
나열되어 있고 순서상 마지막이다. **순서를 뒤집는다.**

### 3.1 Bake-off 설계

- 질문 세트 50개: CURRENT 20 / HISTORICAL 20 / 메모리 불필요(NONE) 10
- 실제 개인 대화 아카이브에서 추출한 진짜 질문일 것
- Baseline(대조군): 대화 청크 embedding + recency weight, top-k
- Treatment: canonical 파이프라인

### 3.2 사전 예측 (검증 시 이 예측을 먼저 기록하고 비교)

canonical이 이길 것으로 예상되는 영역:
- 모순/변경 처리 (과거 관심사와 현재 관심사가 동시에 검색되는 문제)
- 토큰 효율 (snapshot 1개 vs 청크 10개)

canonical이 질 것으로 예상되는 영역:
- 뉘앙스/맥락 (canonical_text로 압축하면 "왜 그랬는지"가 소실)
- extraction 손실 (LLM이 뽑지 않은 정보는 영구 소실)

### 3.3 판단 기준

이 결과가 slot registry를 만들지, snapshot을 테이블로 팔지, relation
classification을 구현할지를 결정한다. **결과가 나오기 전에는 §6의
컴포넌트를 구현하지 않는다.**

---

## 4. [A] 착수 전 확정 필요 — 나중에 바꾸면 마이그레이션이 아니라 재설계

### A-1. Fast path 유지 vs Async only

**권고: MVP는 Async only.**

근거:
- 단일 사용자, 저부하. Fast의 지연 예산 이득이 정량화되지 않음
- remember 직후 같은 대화에서 recall할 이유가 없음 (해당 정보는 이미 컨텍스트에 있음)
- 다른 대화로 넘어갈 때 수 초 지연은 무의미
- Baseline §37-④가 이미 "Fast 실패 → Async fallback"을 정의함
  = **async는 어차피 구현해야 함**. Fast는 순수한 추가 코드 경로/실패 모드/테스트 표면

제거되는 것: `WriteRouter`, Write Routing Policy v0.1, fast-path fallback,
fast/async 간 일관성 버그 클래스 전체.

Upgrade trigger: 실측된 read-after-write staleness 불만 발생.

### A-2. Replay 불변식 채택 여부

Baseline의 가장 강력한 아이디어인데 문서에 이름조차 없다:

> **observation = event log, canonical = materialized view**

LLM 기반 추출/분류 로직은 매달 바뀐다. "observation만으로 canonical 전체를
재구축 가능"을 불변식으로 확정하면, 로직 개선 때마다 전체 재계산이 가능해진다.

현재 이 불변식은 반쯤 깨져 있다:
- §11 PrivacyPurger가 observation을 물리 삭제
- canonical_memory의 in-place mutation (`updated_at`)

결정 필요: 불변식을 채택하고 purge를 tombstone 방식으로 처리할 것인가,
아니면 replay를 포기할 것인가. 채택 시 "재구축 → diff" 테스트를 M2에 포함.

### A-3. 멱등성 (idempotency)

현재 없음. MCP 클라이언트 재시도, LLM의 동일 턴 중복 호출 시 중복
observation이 그대로 적재된다.

`observation.idempotency_key` 추가:
- 클라이언트 제공 값, 또는
- `hash(raw_statement + source_conversation_id + 시간버킷)`

나중에 붙이면 기존 데이터 정리가 불가능하다. 지금 스키마에 넣는다.

### A-4. 파이프라인 버저닝

extraction 프롬프트와 relation classifier는 사실상 데이터 파이프라인 코드다.
버전 기록이 없으면 "이 기억이 왜 이렇게 됐는지"를 6개월 뒤 설명할 수 없고,
로직 개선 후 백필도 불가능하다.

추가:
- `observation.extractor_version`
- `canonicalization_resolution.classifier_model`, `.classifier_version`
- `memory_embedding.text_template_version` (§C-5 참조)

---

## 5. [B] 스키마에 자리만 잡고 판단은 §3 이후로 보류

### B-1. Slot Registry 범위

Slot이 실제로 값을 하는 지점은 하나뿐이다: **cardinality=SINGLE에 의한
결정론적 자동 supersession.** LLM 판단 없이 처리되는 유일한 부분이므로 유지.

DYNAMIC slot은 문제가 있다:
- slot 이름을 LLM이 짓는다 → `learning.focus` / `learning.primary_focus` /
  `study.main_topic`이 같은 의미로 난립
- 어차피 CandidateFinder가 semantic similarity로 찾아야 함
  → dynamic slot key는 **검색 기여도가 거의 0이면서 write path 복잡도만 증가**

**권고:** registered slot 8~10개만 두고 거기서만 cardinality를 강제.
나머지는 slot을 부여하지 않고 `subject + text + embedding`으로 처리.
원칙은 **LLM이 내려야 할 판단 수를 줄이는 것**.

### B-2. Snapshot을 테이블로 팔 것인가

M2까지는 쿼리로 처리하고, 실측 지연이 문제가 될 때 테이블화.
(§C-4의 시간 무효화 문제도 함께 해결됨)

### B-3. Bootstrap 범위와 비용 상한

Baseline에 비용 산정이 없다. 개략 계산:

```
대화 3,000개 × 청크 10개          = 청크 30,000개
extraction: 청크당 1 call         = 30,000 call
청크당 observation 2개             = observation 60,000개
candidate lookup + classification = 60,000+ call
────────────────────────────────────────────────
합계 약 90,000 LLM call
```

추가로 classification은 candidate set을 프롬프트에 포함하므로, canonical이
쌓일수록 입력 토큰이 증가한다. 실패 시 retry 비용도 누적된다.

또한 **salience 게이트가 없다.** Baseline §29의 "요즘 Kubernetes가 재밌어서"
같은 문장은 기억할 가치가 없는데 모든 observation이 canonicalization
대상이 된다. 실무상 LLM이 추출하는 것의 80~90%는 노이즈다.

필요한 조치:
1. extraction 앞단에 저비용 필터 (규칙 기반 + 소형 모델): "이 청크에 지속적 사실이 있는가"
2. 추출 프롬프트에 durability 테스트 명시: "3개월 뒤에도 참이고 유용한가"
3. **MVP bootstrap은 최근 6개월만.** 전체 import는 파이프라인 품질 검증 후
4. 하드 비용 상한 (call 수 / 토큰 / 금액)

Baseline §37-②는 "전체 import ≠ 전체 raw 보관"으로 해명했으나, 문제는
보관이 아니라 **처리 비용**이다. 자체 모순 검사가 놓친 항목.

---

## 6. [C] 설계 결함 — 논쟁 사항 아님, 반영 대상

Baseline §37의 자체 모순 검사가 놓친 것들이다.

### C-1. Async job 순서 보장 없음 — 심각도 최상

§12의 job queue는 polling + retry만 있고 순서 보장이 없다.
그런데 §6의 TEMPORAL_SUCCESSION은 `old.valid_until = transition time`으로
시간 체인을 구성한다.

```
obs_A (2026-01, Kafka)  →  FAILED  →  5분 후 retry
obs_B (2026-06, CKAD)   →  먼저 처리됨 → CKAD = current
obs_A 재처리            →  CKAD를 닫고 Kafka = current   ← 시간 역행
```

Bootstrap에서는 이것이 예외가 아니라 기본 동작이다. §30에 "Temporal
Reconstruction"이라 적혀 있으나 이를 보장하는 메커니즘이 없다.

필요:
- `(subject_id, slot_id)` 단위 직렬화 (Postgres advisory lock)
- `observed_at` 기준 정렬 처리
- resolution 시 분기: 더 나중 시점의 canonical이 이미 존재하면
  succession이 아니라 **과거 구간 삽입**으로 처리

### C-2. N:M evidence 공유 시 purge 정책 미정의

§18에서 observation ↔ canonical은 N:M으로 명시. 그러나 §11 purge는
"Observation 삭제"만 기술한다.

```
obs_202 ──┬── mem_30 (일본 여행)        ← forget 대상
          └── mem_47 (혼자 여행 선호)    ← forget 대상 아님

삭제하면    → mem_47이 근거를 상실
삭제 안하면 → 프라이버시 실패 (R4 위반)
```

추가로: evidence가 0이 된 canonical memory는 존속시키는가? 정의 없음.

### C-3. 청크 단위 삭제의 over/under deletion

`conversation_chunk`는 대화 슬라이스이므로 하나의 청크에 삭제 대상과
비대상이 공존하는 것이 정상이다.

- 청크째 삭제 → 무관한 정보까지 소실
- 삭제 안 함 → raw fallback 검색에 노출되어 R4 위반

§31이 이 경우를 다루지 않는다.

### C-4. Snapshot의 시간 기반 무효화 없음

§10은 "Canonical 변경 시 version++"이다. 그러나 CURRENT는 시간의 함수다.

```
canonical: valid_until = 2026-06-30
2026-07-01: write 이벤트 없음 → snapshot version 변화 없음
           → snapshot이 만료된 사실을 계속 current로 반환   ← R2 위반
```

읽기 시점 필터링 또는 TTL 중 하나를 확정해야 한다.

### C-5. canonical_text 템플릿 버전 부재

`canonical_text`는 derived라고 정의했으나 FTS 인덱스와 embedding이 여기에
의존한다. 파생 템플릿을 변경하면 기존 embedding과 의미가 불일치한다.
`memory_embedding.embedding_model`은 있으나 `text_template_version`이 없다.

### C-6. ACCEPTED/PENDING의 사용자 경험 구멍

remember가 PENDING을 반환하면 LLM은 사용자에게 "기억했다"고 말한다.
이후 `UNRESOLVED_CONFLICT`로 종결되면 실제로는 미반영이나 알릴 경로가 없다.
"Review/Conflict domain"은 Post-MVP로 이연되어 있어, **MVP에 조용한 실패
모드가 내장된다.**

최소 조치: recall(CURRENT) 응답에 unresolved 건수를 포함시켜 모델이
사용자에게 확인하도록 유도.

### C-7. 기타

- 동시성: Claude Code와 Claude Chat의 동시 remember 시 canonical mutation 락 정책 없음
- `canonical_memory.goal_state nullable`이 설명 없이 존재
- ContextPackBuilder는 confidence를 숨기고 보여준다고 하나 `canonical_memory`에 confidence 컬럼 없음

---

## 7. 유지할 결정 (변경하지 말 것)

리뷰에서 문제로 지적되지 않은, 구조적으로 옳은 판단들:

- **Observation / Canonical 분리 + evidence 링크** — 모양이 정확하다
- **모호할 때 overwrite 대신 unresolved 유지** — 대부분의 메모리 시스템이 실패하는 지점
- **CORRECTION(RETRACTED) vs TEMPORAL_SUCCESSION(valid_until) 구분** —
  "틀렸던 사실"과 "그때는 맞았던 사실"의 bi-temporal 분리
- **Job status ≠ semantic resolution** — 재시도 로직이 도메인 판단을 오염시키지 않음
- **Adapter가 provider export 포맷을 도메인에서 격리**
- **Postgres only, 미사용 기술 명시적 배제**
- **Upgrade trigger가 명시된 Evolution Registry**

---

## 8. 마일스톤

Baseline 전체를 writing-plans에 투입하면 태스크 200개짜리 계획이 산출된다.
아래 단위로 잘라서 진행한다.

| | 범위 | 이 단계에서 얻는 답 |
|---|---|---|
| **M0** | observation 테이블 + remember/recall MCP 툴. canonicalization 없음. recall은 subject 필터 + 최근순 반환 | 매일 실사용하며 실제로 필요한 것이 무엇인지 |
| **M1** | golden set 50문항 + raw RAG baseline 구축 | 다음 단계 진행 가치 유무 |
| **M2** | async extraction → canonical_memory, registered slot 8개, SINGLE cardinality 자동 supersession. snapshot은 쿼리 | canonical이 baseline을 이기는가 (§3) |
| **M3** | M2 통과 시: relation classification + ResolutionEngine + evidence | |
| **M4** | HISTORICAL 검색 (FTS + pgvector), ContextPackBuilder | |
| **M5** | bootstrap (최근 6개월 + 비용 상한) | |
| **M6** | forget / PrivacyPurger | |

**forget이 M6인 이유:** 단일 사용자 서비스이므로 M0~M5 구간에는 psql
직접 삭제로 대응 가능하다. C-2, C-3의 정책 결정을 실제 데이터를 본 뒤에
내리는 편이 낫다.

---

## 9. 이 문서 사용 지침

1. §2 요구사항을 먼저 확정한다. Baseline 아키텍처를 전제로 하지 않는다.
2. §4(A-1~A-4)를 결정한다. 이것만 대화로 풀면 된다.
3. §6(C-1~C-7)은 결정 사항이 아니라 결함이다. 설계에 반영한다.
4. §5(B-1~B-3)는 §3 bake-off 결과를 본 뒤 결정한다.
5. M0 스펙만 writing-plans로 넘긴다. 전체를 넘기지 않는다.

**주의:** Baseline v0.1의 자체 모순 검사는 결정을 내린 주체가 자기 결정을
검사해 "되돌릴 것 없음"으로 종결되었다. 이 리뷰도 실제 대화 데이터를
보지 않고 작성된 추측이다. 이 리뷰의 모든 지적에 동의하는 결과가 나온다면
그것은 검증이 아니라 세 번째 동의일 뿐이다. §4와 §6의 각 항목에 대해
반대 논거를 먼저 세워볼 것.
