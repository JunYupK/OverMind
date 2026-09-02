# 벤더링된 에이전트 스킬

여기 있는 디렉터리들은 **superpowers** 스킬 모음을 저장소 안으로 복사한 것이다.
어떤 도구로 들어와도 같은 작업 절차를 따를 수 있게 하려고 둔다.

- **출처:** `claude-plugins-official` 플러그인의 `superpowers`
- **버전:** 6.3.0
- **라이선스:** MIT — `LICENSE` 참조. Copyright (c) 2025 Jesse Vincent
- **변경 여부:** 없음. 원본 그대로 복사했다. 고치지 않는다

## 도구별로 무엇을 쓰는가

| 도구 | 무엇을 쓰나 |
|---|---|
| **Claude Code** | 플러그인이 설치돼 있으면 `superpowers:<이름>`을 쓴다. 그쪽이 최신이다 |
| **Codex, 그 밖의 도구** | 여기 있는 파일을 직접 읽는다. 각 스킬의 `SKILL.md`가 본문이다 |

Claude Code에서는 두 벌이 모두 보인다 — 플러그인의 `superpowers:brainstorming`과
프로젝트 스킬 `brainstorming`. **플러그인 쪽을 쓴다.** 이 복사본은 6.3.0에 고정돼 있고
플러그인은 계속 갱신되기 때문이다.

## 스킬이 곧 능력은 아니다

각 `SKILL.md`는 **절차를 적은 지시문**이다. 그것을 실행할 수 있는지는 도구가 어떤
기능을 갖고 있느냐에 달렸다.

예를 들어 `subagent-driven-development`는 "태스크마다 새 서브에이전트를 띄우고
구현하지 않은 도구가 리뷰한다"고 지시한다. 서브에이전트를 띄우는 기능이 없는 도구라면
컨텍스트 격리는 포기하고 같은 루프(태스크 → `./gradlew verify` → 커밋 → 교차 리뷰)를
한 세션 안에서 순차로 돌면 된다. 절차의 값은 대부분 게이트 순서에 있지 격리에 있지 않다.

무엇이 되고 무엇이 안 되는지는 도구마다 다르니, **읽고 판단해서 할 수 있는 만큼 따른다.**
할 수 없는 단계는 건너뛰되 `log.md`에 무엇을 건너뛰었는지 적는다.

## 이 프로젝트에서 실제로 쓰는 것

| 언제 | 스킬 |
|---|---|
| 새 마일스톤 설계 (M0, M2, M6) | `brainstorming` |
| 설계가 끝나 구현 계획을 만들 때 | `writing-plans` |
| 계획을 실행할 때 | `executing-plans`, 또는 `subagent-driven-development` |
| 기능·버그픽스를 구현할 때 | `test-driven-development` |
| 버그·테스트 실패를 만났을 때 | `systematic-debugging` |
| 리뷰를 요청하거나 받을 때 | `requesting-code-review`, `receiving-code-review` |
| "다 됐다"고 말하기 직전 | `verification-before-completion` |
| 브랜치를 마무리할 때 | `finishing-a-development-branch` |

교차 리뷰 규칙(구현한 도구가 자기 코드를 리뷰하지 않는다)은 이 스킬들이 아니라
`docs/harness/50-review-protocol.md`가 정한다. 충돌하면 그쪽이 우선이다.

## 갱신 방법

플러그인 캐시에서 다시 복사한다.

```bash
P="$HOME/.claude/plugins/cache/claude-plugins-official/superpowers/<버전>"
rm -rf .claude/skills/*/
cp -r "$P/skills/." .claude/skills/
cp "$P/LICENSE" .claude/skills/LICENSE
# 이 README의 '버전' 줄을 고칠 것
```

**자동 검사는 일부러 두지 않았다.** 벤더링본과 플러그인을 비교하는 검사는 플러그인이
없는 환경(CI)에서 조용히 건너뛰게 되는데, 이 프로젝트는 그 실패 유형 —
통과하지만 아무것도 검사하지 않는 게이트 — 을 이미 두 번 겪었다.
갱신은 사람이 의도적으로 한다.
