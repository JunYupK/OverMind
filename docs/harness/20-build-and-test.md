# 20 · 빌드와 테스트

## 커맨드

| 커맨드 | 내용 | 언제 |
|---|---|---|
| `./gradlew test` | L1 단위 + ArchUnit. 외부 의존 없음 | 구현 중 수시로 |
| `./gradlew integrationTest` | L2. Testcontainers + LLM 픽스처 재생 | 커밋 전 |
| `./gradlew verify` | **기계 게이트.** compile + L1 + L2 | 커밋 전 필수 |
| `./gradlew guardrails` | 문서 줄 수, ddl-auto, 마이그레이션 해시, log.md 동반 변경, gitleaks | 커밋 전 |
| `./gradlew evaluationTest` | L3. 실제 LLM 호출. **비용 발생** | 수동 / 야간 CI |
| `./gradlew updateMigrationChecksums` | 새 마이그레이션 추가 후 해시 갱신 | 마이그레이션 추가 시 |

## 로컬–CI 동치의 범위

`verify`는 CI의 `verify` 잡과 **정확히 같은 것**을 실행한다.

`guardrails`는 **gitleaks 한 단계를 뺀 나머지**가 CI의 `guardrails` 잡과 같다.
차이는 이렇다:

| | 로컬 `./gradlew guardrails` | CI `guardrails` 잡 |
|---|---|---|
| 가드레일 테스트 | `guardrailTest` | `guardrailTest` (같음) |
| 시크릿 스캔 | `gitleaksScan` — gitleaks가 PATH에 없으면 **경고만 내고 넘어간다** | `gitleaks/gitleaks-action@v2` (별도 스텝, 항상 실행) |

**즉, gitleaks를 설치하지 않은 로컬에서는 시크릿 스캔이 아예 돌지 않는다.**
그 상태에서 `guardrails`가 초록이어도 시크릿에 대해서는 아무 말도 하지 않은 것이다.
이 한 단계를 로컬에서 강제하지 않는 이유는, 설치되지 않은 도구 때문에 게이트가
상시 빨간불이면 에이전트가 게이트를 무시하기 시작하기 때문이다. 진짜 시크릿 게이트는 CI다.

시크릿을 로컬에서도 잡고 싶으면 gitleaks를 설치하면 된다. 설치되어 있으면
`gitleaksScan`이 실제로 스캔하고 탐지 시 빌드를 깬다.

gitleaks 말고 다른 항목에서 로컬은 통과했는데 CI가 깨지면 그것은 하네스 버그다. 즉시 고친다.

## 게이트가 비어 있으면 실패한다

`test`, `integrationTest`, `guardrailTest`에는 0건 실행 바닥이 붙어 있다
(`testNotEmpty`, `integrationTestNotEmpty`, `guardrailTestNotEmpty`).
테스트가 한 건도 실행되지 않으면 태스크가 성공하지 않는다.

막는 구멍은 둘이다 — 테스트 소스가 사라져 Gradle이 태스크를 `NO-SOURCE`로 건너뛰는 경우,
그리고 태그 철자가 틀려 필터가 아무것도 못 잡는 경우. 둘 다 예전에는 초록이었고
CI 로그가 정상 실행과 구별되지 않았다.

`evaluationTest`에는 바닥이 없다. 마일스톤에 따라 정당하게 비어 있다.

## 테스트 계층

| 계층 | 태그 | 외부 의존 |
|---|---|---|
| L1 | 없음 | 없음. `LlmPort`는 손으로 쓴 fake |
| L2 | `integration` | Testcontainers `pgvector/pgvector:pg16` + 녹화된 LLM 응답 |
| L3 | `evaluation` | 실제 LLM |

**L1에서 `@SpringBootTest`를 쓰지 않는다.** Spring 컨텍스트가 뜨는 순간
단위 테스트가 아니고, 초 단위 피드백이 깨지면 루프가 실용성을 잃는다.

## 사전 준비

### JDK 21 (필수)

Gradle 툴체인이 Java 21을 요구한다. `JAVA_HOME`이 다른 버전을 가리키면
`./gradlew`가 컴파일 전에 `JAVA_HOME is set to an invalid directory`로 죽는다.
(이 기계의 기본 `JAVA_HOME`은 오래된 Zulu 8이라 새 셸에서는 그대로 실패한다.)

이 기계의 검증된 JDK 21 경로:

    C:/Users/top15/.jdks/corretto-21.0.2

Git Bash(MSYS)에서는 세션마다 아래 두 줄을 먼저 실행한다:

    export JAVA_HOME="/c/Users/top15/.jdks/corretto-21.0.2"
    export PATH="$JAVA_HOME/bin:$PATH"

`JAVA_HOME`은 Gradle이 읽으므로 `C:/...` 형식도 통하지만, **`PATH`에 넣는 값은
반드시 `/c/...` 형식이어야 한다.** Git Bash는 `PATH`를 `:`로 자르기 때문에
`C:/...`를 넣으면 `C`와 `/Users/...` 두 조각으로 깨진다.

JDK 21이 아예 없는 기계라면 `settings.gradle.kts`의 foojay 툴체인 리졸버가
Gradle에게 21을 내려받게 한다 (네트워크 필요). 그래도 위 두 줄이 더 빠르고 확실하다.

### 그 밖

- Docker가 떠 있어야 L2가 돈다.
- 컨테이너 재사용을 켜면 L2가 빨라진다. `~/.testcontainers.properties`에
  `testcontainers.reuse.enable=true`를 넣는다. CI에서는 자동으로 무시된다.
- gitleaks가 PATH에 없으면 `guardrails`가 경고만 내고 넘어간다. CI에서는 필수 단계다.
  자세한 것은 위 "로컬–CI 동치의 범위".

## LLM 픽스처

L2는 `src/test/resources/llm-fixtures/<프롬프트버전>/<키>.json`을 재생한다.

프롬프트를 고쳤으면 재녹화한다:

    ./gradlew integrationTest -Dovermind.llm.record=true

**재녹화 diff는 리뷰 대상이다.** 프롬프트를 바꿨을 때 출력이 어떻게
달라졌는지가 그 diff에 그대로 드러난다. 무심코 커밋하지 않는다.

프롬프트 버전은 세 곳이 일치해야 한다:

1. 픽스처 디렉터리 이름
2. `PromptVersions` 상수
3. (M0 이후) `observation.extractor_version` 컬럼 값

어긋나면 `PromptVersionFixtureLinkTest`가 실패한다.
