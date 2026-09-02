# 40 · 가드레일

## 자동 검사 (`./gradlew guardrails`)

| 영역 | 규칙 |
|---|---|
| 문서 | `CLAUDE.md` 40줄, `AGENTS.md` 120줄 상한 |
| 스키마 | `spring.jpa.hibernate.ddl-auto: validate` 고정 |
| 마이그레이션 | 이미 커밋된 `V*__*.sql`은 수정 불가 (해시 비교) |
| 로그 | `src/**`, `build.gradle.kts`, `.github/**`, `docs/harness/**` 변경 시 `log.md` 동반 변경 |
| 빈 게이트 | `test`/`integrationTest`/`guardrailTest`가 0건 실행하면 실패 |
| 시크릿 | gitleaks 스캔 |

로그 가드의 범위는 **커밋이 아니라 PR(브랜치) 범위**다 — `baseRef...HEAD`를 본다.
브랜치 안 어딘가에서 `log.md`가 갱신되면 통과한다. AGENTS.md 절대 규칙 3이 같은 범위로
진술되어 있다. 감시 경로에 게이트 기계 자체(`build.gradle.kts`, `.github/`,
`docs/harness/`)가 들어가는 이유는, 게이트를 고치는 변경이야말로
"왜 그렇게 했는지"가 git diff에 남지 않는 변경이기 때문이다.

빈 게이트 가드(`*NotEmpty` 태스크)는 게이트가 **아무것도 실행하지 않고 통과하는 것**을 막는다.
테스트 소스가 사라지면 Gradle이 태스크를 `NO-SOURCE`로 건너뛰고, 태그 철자를 틀리면
필터가 아무것도 못 잡는다. 둘 다 실패가 아니라 초록이었다. 이제는 0건이면 빌드가 깨진다.
`evaluationTest`는 마일스톤에 따라 정당하게 비어 있으므로 제외한다.

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

**이 태스크는 append-only다.** 이미 기록된 파일의 내용이 바뀌었거나 파일이 사라졌으면
갱신하지 않고 그 파일 이름을 대며 실패한다. 새 파일만 뒤에 덧붙는다.

예전에는 이 태스크가 기록 전체를 다시 썼기 때문에, "적용된 V1을 고친다 → 해시 가드가
실패한다 → 실패 메시지가 지목한 이 명령을 실행한다 → 통과한다"가 성립했다.
가드를 우회하는 방법을 가드의 실패 메시지가 알려주고 있었고, 그것을 막는 것은
이 문서의 문장뿐이었다. 지금은 기계가 막는다.

## 로그에 남기면 안 되는 것

메모리 페이로드, 대화 원문, canonical 값, 토큰, Authorization 헤더.
불변식 INV-02이며 `docs/harness/60-invariants.md`에 검사 방법이 있다.
로그에는 메타데이터만 남긴다 — id, 개수, 소요 시간, 상태.
