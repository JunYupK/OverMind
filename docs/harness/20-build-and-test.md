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

`verify`와 `guardrails`는 CI의 같은 이름 잡과 **정확히 같은 것**을 실행한다.
로컬에서 통과했는데 CI에서 깨지면 그것은 하네스 버그다. 즉시 고친다.

## 테스트 계층

| 계층 | 태그 | 외부 의존 |
|---|---|---|
| L1 | 없음 | 없음. `LlmPort`는 손으로 쓴 fake |
| L2 | `integration` | Testcontainers `pgvector/pgvector:pg16` + 녹화된 LLM 응답 |
| L3 | `evaluation` | 실제 LLM |

**L1에서 `@SpringBootTest`를 쓰지 않는다.** Spring 컨텍스트가 뜨는 순간
단위 테스트가 아니고, 초 단위 피드백이 깨지면 루프가 실용성을 잃는다.

## 사전 준비

- Docker가 떠 있어야 L2가 돈다.
- 컨테이너 재사용을 켜면 L2가 빨라진다. `~/.testcontainers.properties`에
  `testcontainers.reuse.enable=true`를 넣는다. CI에서는 자동으로 무시된다.
- gitleaks가 PATH에 없으면 `guardrails`가 경고만 내고 넘어간다. CI에서는 필수 단계다.

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
