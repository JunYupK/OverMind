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
