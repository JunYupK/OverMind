# CLAUDE.md

**먼저 `AGENTS.md`를 읽어라.** 이 프로젝트의 규약은 전부 거기서 라우팅된다.
이 파일에는 Claude Code 전용 사항만 있다.

## Claude Code 전용

- 구현 작업은 `superpowers:test-driven-development`를 따른다.
- 버그·테스트 실패를 만나면 `superpowers:systematic-debugging`을 먼저 쓴다.
- 새 기능·설계 변경은 `superpowers:brainstorming`을 먼저 쓴다.
- 리뷰 수행 여부와 담당은 `docs/harness/30-loop.md`를 따른다.
  요청받은 리뷰의 판정 규칙은 `docs/harness/50-review-protocol.md`.
- `.claude/skills/`에 같은 스킬들이 복사돼 있어 프로젝트 스킬로도 보인다.
  **플러그인의 `superpowers:*`를 쓴다** — 복사본은 6.3.0 고정이고 플러그인이 최신이다.
  복사본은 플러그인이 없는 도구(Codex 등)를 위한 것이다.

## 권한

- 자동 허용 — 읽기·검색, `./gradlew` 실행, `src/**` 편집, `log.md` 편집
- 확인 필요 — 커밋, 푸시, PR 생성, 의존성 추가, 마이그레이션 파일 생성,
  실 LLM 호출(`./gradlew evaluationTest`)
- 금지 — `docs/harness/40-guardrails.md`의 금지 목록

## 이 파일의 제약

40줄을 넘기지 않는다. CI가 검사한다.
규약을 여기 복제하면 `AGENTS.md`와 갈라진다.
