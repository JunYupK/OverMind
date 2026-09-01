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
