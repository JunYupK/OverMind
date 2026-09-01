# OverMind 작업 로그

작성 규약은 `docs/harness/00-start-here.md`에 있다.
세션 시작 시에는 아래 HEAD 블록만 읽는다. 세션 기록은 필요할 때만 검색한다.

<!-- ===== HEAD — 항상 최신으로 덮어쓴다 ===== -->

## 현재 상태

- **마일스톤:** H — 개발 하네스 구축
- **최근 갱신:** 2026-09-02 · Claude Code
- **브랜치:** feat/harness
- **verify:** `./gradlew guardrails` 구축 완료 — 문서 줄 수 상한, ddl-auto validate 고정,
  마이그레이션 해시, log.md 동반 변경, gitleaks(로컬 미설치 시 경고만) 4+1종 가드 녹색
- **CI:** `.github/workflows/ci.yml` 작성 완료 — verify/guardrails가 PR을 막고
  evaluation은 야간 스케줄 + 수동 트리거만. 아직 push 안 함(다음 세션에서 브랜치 전체 리뷰 후 푸시).

### 진행 중

- [ ] H-1~H-9 하네스 구축 (`docs/superpowers/plans/2026-09-01-overmind-harness.md`)

### 다음 할 일

1. `feat/harness` 브랜치 전체 리뷰 후 푸시, GitHub Actions 탭에서 verify/guardrails 초록 확인

### 열려 있는 결정

- 없음 (도메인 결정은 `docs/arch/decisions.md` 참조)

### 막힌 것

- 없음

<!-- ===== 세션 기록 — append-only, 최신이 위 ===== -->

## 세션 기록

### 2026-09-02 · Claude Code · Task 10 · (커밋 SHA는 아래 참조)

- **한 일:** `.github/workflows/ci.yml` 작성 — `verify`/`guardrails`(PR·push 게이트),
  `evaluation`(야간 03:00 KST 스케줄 + `workflow_dispatch`만, 실 LLM 호출이라 PR을 안 막음)
  3잡. 브리프 대비 추가한 것: `guardrail 검사` 스텝에서 `BASE`를 계산한 직후
  `git rev-parse --verify "$BASE"`로 먼저 검증하고, 풀리지 않으면 `::error::`를 찍고
  `exit 1`로 잡을 명시적으로 실패시킨 뒤에야 `./gradlew guardrailTest -PbaseRef="$BASE"`를
  부르도록 했다. 이유: `LogUpdatedGuardTest`는 baseRef가 안 풀리면 실패가 아니라
  스킵하므로, 얕은 체크아웃이나 브랜치 첫 커밋(`HEAD~1` 없음)에서 log.md 가드가 조용히
  꺼진 채 CI가 초록으로 남는 구멍이 있었다.
- **검증:** YAML 파싱(`python -c "import yaml..." ` → `ok`), `./gradlew verify guardrails`
  BUILD SUCCESSFUL, 리포 밖 스크래치 스크립트로 `$BASE` 검증 로직만 떼어내
  (1) 존재하지 않는 PR base_ref → `origin/<ref>`가 안 풀려 exit 1로 실패,
  (2) 현재 저장소에서 push 스타일 `HEAD~1` → 통과, (3) 커밋 1개짜리 임시 repo에서
  `HEAD~1` 부재 → exit 1로 실패, 세 가지를 모두 직접 관찰.
- **결과:** push는 하지 않음(컨트롤러가 브랜치 전체 리뷰 후 푸시하고 그때 CI 확인
  예정). 로컬 커밋만 함 — `git status` clean, `git log origin/master..HEAD` 확인.
- **다음:** `feat/harness` 브랜치 전체 리뷰 → 푸시 → Actions 탭에서 verify/guardrails
  초록 확인. Testcontainers가 러너에서 실패하면 `docs/harness/20-build-and-test.md`에
  기록.

### 2026-09-02 · Claude Code · Task 9 · 4d65583

- **한 일:** 가드레일 검사 4종(`DocLineLimitGuardTest`, `DdlAutoGuardTest`,
  `MigrationChecksumGuardTest`, `LogUpdatedGuardTest`) 작성, `build.gradle.kts`에
  `updateMigrationChecksums` 태스크 추가, `docs/harness/migration-checksums.txt` 생성.
  네 가드 전부 실패/통과를 직접 관찰(문서 30줄 초과, 체크섬 파일 부재, ddl-auto=update,
  log.md 없이 src/만 바뀐 baseRef 범위).
- **결과:** `./gradlew guardrails` 통과 (gitleaks 미설치 경고만 출력) / 리뷰 미실시
- **함정:** `build.gradle.kts`에 `java { toolchain {...} }` 확장이 이미 있어서, 브리프의
  `java.security.MessageDigest.getInstance(...)` 표현이 `java`를 확장 프로퍼티로 해석해
  컴파일 에러(`Unresolved reference: security`)를 낸다. 파일 상단에
  `import java.security.MessageDigest`를 추가하고 본문에서 `MessageDigest`로 바꿔서 해결.
  log.md HEAD 블록의 `브랜치: master`가 실제와 달랐다 — `feat/harness`로 정정.
- **다음:** Task 10 GitHub Actions 워크플로
