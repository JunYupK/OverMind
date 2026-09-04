# 70 · M0 원격 스모크 절차

M0가 실제로 도는지 **사람이 손으로** 확인하는 절차다.
자동 테스트가 대신할 수 없는 것만 남겼다 — 실제 원격 MCP 클라이언트 두 개, 실제 OIDC
발급자, 실제 HTTPS 종단.

각 항목을 확인하면 표의 칸을 채운다. **비어 있는 칸은 "안 해봤다"는 뜻이다.**
채우지 않은 채로 "돌아간다"고 말하지 않는다.

## 자동 테스트가 이미 보는 것

여기서 다시 확인하지 않는다. 겹치면 손이 게을러지고, 게을러진 손은 표를 거짓으로 채운다.

| | 어디서 |
|---|---|
| 도구 계약, 오류 코드, 프로토콜 | `McpTransportIntegrationTest` (L2) |
| 토큰 검증, scope, 경로 노출 | `McpAuthorizationTest` (L2) |
| 두 MCP 클라이언트 교차 저장·조회 | `CrossClientAcceptanceTest` (L2) |
| 민감 값 로그 부재 | `LogHygieneTest` (L2) |
| 필수 설정 누락 시 기동 실패 | `RequiredSettingsTest` (L1) |

## 사전 준비

- HTTPS 종단(리버스 프록시 또는 배포 플랫폼) 뒤에 애플리케이션을 띄운다.
  **애플리케이션 포트를 외부에 직접 노출하지 않는다.**
- forwarded 헤더는 지정한 프록시에서만 신뢰한다.
- 관리형 OIDC 발급자에서 다음 설정을 채운다. **production 프로파일에서 하나라도 비면
  기동이 실패해야 한다** — 그것이 8번 항목이다.

```
overmind.security.issuer           HTTPS 절대 URI
overmind.security.audience         이 리소스 서버의 audience
overmind.security.allowed-subject  허용할 sub 정확히 하나
overmind.security.cursor-secret    UTF-8 32 bytes 이상
```

`cursor-secret`은 **로그·티켓·PR 본문 어디에도 붙여 넣지 않는다.** 저장소의 gitleaks가
`key=<고엔트로피 문자열>` 모양을 잡는다.

## 절차

| # | 확인할 것 | 기대 | 확인일 | 확인자 |
|---|---|---|---|---|
| 1 | 원격 MCP 클라이언트 **두 개**를 같은 endpoint에 붙인다. 같은 인증 사용자로 | 둘 다 initialize 성공, 도구 목록에 `remember_memory`·`recall_memory` **둘만** 보인다 | | |
| 2 | client A에서 `remember_memory`로 USER observation 하나 저장 | `status: STORED`, `created: true` | | |
| 3 | client A에서 PROJECT observation 하나 저장 | 같음 | | |
| 4 | client B에서 `recall_memory`를 project key와 함께 호출 | USER와 PROJECT가 **함께**, `observed_at` 최신순 | | |
| 5 | 같은 `idempotency_key`로 2번을 다시 호출 | `created: false`, 같은 `observation_id` | | |
| 6 | `memory:read`만 가진 토큰으로 `remember_memory` 호출 | `PERMISSION_DENIED` | | |
| 7 | `memory:write`만 가진 토큰으로 `recall_memory` 호출 | `PERMISSION_DENIED` | | |
| 8 | 위 필수 설정 넷 중 하나를 비우고 production 프로파일로 기동 | **기동 실패.** 뜬 채로 요청을 받으면 안 된다 | | |
| 9 | 애플리케이션 로그를 훑는다 | content·source id·idempotency key·project key·토큰·claim·cursor 원문이 **하나도 없다** | | |
| 10 | 애플리케이션 포트로 직접 접속 시도 | 외부에서 닿지 않는다 | | |

## 실패했을 때

**절차를 고치지 말고 구현을 고친다.** 이 표는 스펙 §10을 사람 손으로 재확인하는 것이고,
표가 통과하도록 기대를 낮추면 확인 자체가 무의미해진다.

9번이 실패하면 새는 지점을 찾아 `LogHygieneTest`에 그 흐름을 **먼저 추가한다.**
수동 확인만 고치면 다음 회귀를 또 손으로 잡게 된다.
