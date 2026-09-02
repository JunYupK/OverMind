# AGENTS.md — OverMind

OverMind는 여러 AI 클라이언트(Claude Chat / ChatGPT / Claude Code / Codex)가 공유하는
단일 개인 메모리 서비스다. MCP로 노출하며, 대화에서 관측된 사실을 정규화해
시점별로 유효한 사실을 관리한다.

이 파일은 규약을 담지 않는다. **어디를 읽어야 하는지만** 알려준다.

## 세션 시작 절차 (예외 없음)

1. `log.md`의 HEAD 블록을 읽는다. `<!-- ===== 세션 기록` 아래로는 읽지 않는다.
2. HEAD의 "현재 마일스톤"과 "다음 할 일"을 확인한다.
3. 지금 할 태스크의 스펙/플랜 문서를 읽는다 (`docs/superpowers/`).
4. `docs/harness/00-start-here.md`를 읽는다.

## 절대 규칙

1. `docs/arch/baseline-v0.1.md`와 `docs/arch/review-v0.1.md`는 **읽기 전용 사료**다.
   결정이 바뀌면 `docs/arch/decisions.md`에 기록하고 사료는 건드리지 않는다.
2. `log.md`의 과거 세션 기록 항목은 편집하지 않는다. 정정은 새 항목에 쓴다.
3. `src/`, `build.gradle.kts`, `.github/`, `docs/harness/`를 고쳤으면
   **같은 PR(브랜치 범위) 안에서** `log.md`를 갱신한다. CI가 강제한다.
4. 커밋 전에 `./gradlew verify`와 `./gradlew guardrails`가 **둘 다** 통과해야 한다.
   CI는 두 잡을 따로 돌린다. `verify`만 보면 CI에서 빨간불을 만난다.
5. 다음은 금지다 — `master`에 `git push --force`, `git reset --hard`,
   마이그레이션 밖의 `DROP`/`TRUNCATE`, 프로덕션 DB 직접 접속.

## 라우팅 테이블

| 지금 하려는 것 | 읽을 문서 |
|---|---|
| 세션을 막 시작함 | `docs/harness/00-start-here.md` |
| 파일이 어디 있는지 모름 | `docs/harness/10-repo-map.md` |
| 빌드·테스트를 돌리려 함 | `docs/harness/20-build-and-test.md` |
| 태스크를 구현하려 함 | `docs/harness/30-loop.md` |
| 커밋·푸시·마이그레이션·시크릿 | `docs/harness/40-guardrails.md` (커맨드는 `./gradlew guardrails`) |
| 남의 코드를 리뷰하려 함 | `docs/harness/50-review-protocol.md` |
| 이 변경이 무엇을 깨면 안 되는지 확인 | `docs/harness/60-invariants.md` |
| 아키텍처 배경이 궁금함 | `docs/arch/baseline-v0.1.md`, `docs/arch/review-v0.1.md` |
| 무엇이 이미 정해졌는지 확인 | `docs/arch/decisions.md` |
| 이 기능이 왜 필요한지 확인 | `docs/requirements/R1-R6.md` |

## 세션 종료 절차

1. `log.md` HEAD 블록을 현재 상태로 덮어쓴다.
2. `## 세션 기록` 바로 아래에 새 항목을 추가한다. 필드는 5개 고정:
   제목줄(날짜 · 도구 · 태스크 · 커밋 SHA) / 한 일 / 결과 / 함정 / 다음.
3. **git이 기록하는 것은 쓰지 않는다.** diff·파일 목록은 git에 있다.
   log.md에는 왜 그렇게 했는지, 시도했다 버린 것, 함정, 열려 있는 결정만 쓴다.
4. 두 변경을 같은 커밋에 넣는다.

## 이 파일의 제약

120줄을 넘기지 않는다. CI가 검사한다.
규약 본문이 이 파일로 흘러들어오면 도구별 문서가 갈라지기 시작한다.
내용은 `docs/harness/`에 한 벌만 둔다.
