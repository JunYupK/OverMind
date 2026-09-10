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
- Auth0를 쓴다면 **테넌트 Default Audience를 API Identifier와 같게 설정한다.**
  Claude는 OAuth 요청에 `resource`만 보내고 `audience`를 보내지 않아서, 이 설정이
  없으면 Auth0가 JWT 대신 opaque 토큰을 발급하고 `NimbusJwtDecoder`가 파싱조차
  하지 못한다. D8이 이것을 확인한다.

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
| 1 | 원격 MCP 클라이언트 **두 개**를 같은 endpoint에 붙인다. 같은 인증 사용자로 | 둘 다 initialize 성공, 도구 목록에 `remember_memory`·`recall_memory` **둘만** 보인다 | 2026-09-10 | 김준엽 |
| 2 | client A에서 `remember_memory`로 USER observation 하나 저장 | `status: STORED`, `created: true` | | |
| 3 | client A에서 PROJECT observation 하나 저장 | 같음 | 2026-09-10 | 김준엽 |
| 4 | client B에서 `recall_memory`를 project key와 함께 호출 | USER와 PROJECT가 **함께**, `observed_at` 최신순 | 2026-09-10 | 김준엽 |
| 5 | 같은 `idempotency_key`로 2번을 다시 호출 | `created: false`, 같은 `observation_id` | 2026-09-10 | 김준엽 |
| 6 | `memory:read`만 가진 토큰으로 `remember_memory` 호출 | `PERMISSION_DENIED` | | |
| 7 | `memory:write`만 가진 토큰으로 `recall_memory` 호출 | `PERMISSION_DENIED` | | |
| 8 | 위 필수 설정 넷 중 하나를 비우고 production 프로파일로 기동 | **기동 실패.** 뜬 채로 요청을 받으면 안 된다 | 2026-09-10 | 김준엽 |
| 9 | 애플리케이션 로그를 훑는다 | content·source id·idempotency key·project key·토큰·claim·cursor 원문이 **하나도 없다** | | |
| 10 | 애플리케이션 포트로 직접 접속 시도 | 외부에서 닿지 않는다 | | |

## 배포 검증 — 깨뜨려서 확인한다

설계 근거는 `docs/superpowers/specs/2026-09-04-overmind-deploy-design.md` §12에 있다.

**"확인했다"가 아니라 "막히는 것을 봤다"를 채운다.** 방어가 통과하는 것만 보면
그 방어가 실제로 무언가를 막는지 알 수 없다. 아래 항목 중 여럿이 일부러
깨뜨려 보라고 요구하는 이유다.

| # | 확인할 것 | 어떻게 | 확인일 | 확인자 |
|---|---|---|---|---|
| D1 | 앱 포트가 외부에 안 보인다 | 외부 호스트에서 `curl http://<공인IP>:8080/mcp` → 거부. **그다음 compose의 `127.0.0.1:` 접두사를 빼고 재기동해 외부에서 응답이 오는 것을 확인한 뒤 되돌린다** | | |
| D2 | DB가 외부에 안 보인다 | 외부 호스트에서 `nc -vz <공인IP> 5432` → 거부 | | |
| D3 | pgvector 두 계정 분리가 실제로 막는다 | 운영 `overmind-pgdata`가 아닌 임시 볼륨으로, `deploy/initdb`에서 `01-vector.sql`만 빼고(`02-app-role.sh`는 그대로 두고) 기동 → 앱은 `OVERMIND_DB_USER`(NOSUPERUSER)로 붙으므로 Flyway V1(`CREATE EXTENSION IF NOT EXISTS vector`)이 permission denied로 실패하는 것을 확인. 볼륨을 지우고 다시 만들어 `01-vector.sql`을 되돌린 뒤 재기동해 통과를 확인하고 임시 볼륨을 정리한다 | | |
| D4 | 어느 게이트가 기동을 막는가 | issuer를 비우고 production 프로파일로 기동 → 실패 확인. 그다음 `SPRING_PROFILES_ACTIVE=production`을 빼고 반복. **기대: 둘 다 실패한다** — `SecurityConfig.jwtDecoder`가 싱글턴 빈이라 기동 시 `requireComplete()`가 프로파일과 무관하게 동기 호출되기 때문이다(그러면 `RequiredSettings.Validation`은 중복 방어이고 스펙 §13 D-M이 맞다). **프로파일을 뺐을 때 기동에 성공하면** `Validation`이 진짜 게이트였다는 뜻이고 D-M은 틀렸다 — 스펙을 고친다 | | |
| D5 | 디스커버리 체인 | 토큰 없이 `curl -i -X POST https://overmind.<도메인>/mcp` → 401 + `WWW-Authenticate`에 `resource_metadata="https://..."`. 그 URL을 `curl` → 응답 JSON의 `authorization_servers`에 Auth0 issuer, `tls_client_certificate_bound_access_tokens`는 `true`가 아니다(mTLS를 쓰지 않으므로) | 2026-09-10 | 김준엽 |
| D6 | 공개 URL이 루프백이 아니다 | D5에서 받은 401의 `resource_metadata` 값과 메타데이터 문서의 `resource` 값이 **둘 다 `https://overmind.<도메인>`으로 시작한다.** `127.0.0.1`이나 `http://`가 보이면 forwarded 헤더가 안 먹은 것이다 — **원인은 거의 항상 `internal-proxies`에 Docker 브리지 대역(`172.16.0.0/12`)이 빠진 것이지 `forward-headers-strategy` 자체가 아니다**(app이 브리지 네트워크 위 컨테이너로 뜨므로 Caddy 연결이 컨테이너 안에서는 `172.x.0.1`로 보인다 — `127.0.0.1`이 아니다). **추측하지 말고 잰다:** `docker inspect -f '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}' $(docker compose -f /opt/overmind/compose.yaml ps -q app)`로 **app 컨테이너가 실제로 보는** 게이트웨이를 뽑아(compose가 네트워크 이름에 프로젝트명을 접두하므로 `docker network inspect overmind-net`은 이름이 어긋나기 쉽다) `application.yml`의 `internal-proxies`가 그 값을 포함하는지 확인한다(현재 커버 범위: `172.16.0.0/12`, `192.168.0.0/16`, 루프백). 둘 어디에도 없으면 Docker의 default-address-pools가 손대져 있다는 뜻이므로, 정규식을 더 넓히지 말고 `compose.yaml`의 `overmind-net`에 `ipam.config.subnet`을 고정해 게이트웨이를 확정한다. 어느 경우에도 정규식을 `.*`로 넓혀 "일단 통과시키고 보자"로 우회하지 않는다 — 그러면 D7이 아무것도 증명하지 못하는 상태로 green이 된다 | 2026-09-10 | 김준엽 |
| D7 | forwarded 헤더 신뢰 경계 | **D6이 먼저 green이어야 이 항목에 의미가 있다.** D6이 실패한 채(즉 forwarded 헤더가 아무것도 신뢰되지 않는 채)로는 어떤 헤더도 반영되지 않으니 D7은 손대지 않아도 트리비얼하게 통과한다 — 그건 신뢰 경계를 확인한 게 아니라 **아무것도 신뢰하지 않는다는 것만 확인한 것**이다. D6이 green인 상태에서: 박스 밖에서 `curl -H 'X-Forwarded-Host: evil.example' https://overmind.<도메인>/.well-known/oauth-protected-resource/mcp` → 응답의 `resource`에 `evil.example`이 **없어야 한다** | 2026-09-10 | 김준엽 |
| D8 | Auth0가 JWT를 준다 (opaque가 아니라) | 토큰이 `.`으로 세 조각인지 확인하고 payload를 디코드해 `aud`/`sub`/`scope`를 본다. **세 조각이 아니면 테넌트 Default Audience가 안 걸린 것이다** | 2026-09-10 | 김준엽 |
| D9 | sub allowlist가 막는다 | Auth0에 두 번째 사용자를 만들어 토큰을 받고 `/mcp` 호출 → 401. 막지 못하면 allowlist는 장식이다 | 2026-09-10 | 김준엽 |
| D10 | 백업 복원 드릴 | `deploy/README.md`의 "복원 드릴 — 이걸 해야 백업이다" 절을 그대로 따른다(별도 컨테이너 `overmind-restore-drill`에 복원 후 `observation` 행 수를 원본과 대조). 운영 `db` 서비스나 그 아래 "실제 복구 순서" 절과 혼동하지 않는다 | | |
| D11 | 재부팅 생존 | `sudo reboot` 후 사람 개입 없이 Caddy·docker·compose가 모두 복귀하는지 | | |
| D12 | 이미지가 게이트를 통과한 것인가 | 돌고 있는 태그의 sha로 GitHub Actions를 찾아 `verify`·`guardrails`가 초록인지 확인 | 2026-09-10 | 김준엽 |
| D13 | 계정 동일 가드가 실제로 막는다 | `db.env`에서 `OVERMIND_DB_USER`를 `POSTGRES_USER`와 같은 값으로 맞춘 임시 볼륨으로 기동 → `02-app-role.sh`가 즉시 `exit 1`로 컨테이너 초기화를 중단하는 것을 `docker compose logs db`에서 확인한다. **로그에 정확히 `02-app-role.sh: POSTGRES_USER와 OVERMIND_DB_USER가 둘 다 '<값>'로 같습니다.`가 찍혀야 한다** — 이 문구가 없으면 뭔가 다른 이유(예: `: "${VAR:?}"` 스타일의 필수값 누락 가드가 먼저 걸린 것)로 exit 1이 난 것일 수 있어, "가드가 걸렸다"와 "다른 이유로 실패했다"가 로그만 보고는 구분되지 않는다. 두 값을 다시 다르게 하고 정상 기동을 확인한다 | | |

**D4는 2026-09-10에 절반만 관측했다.** production 프로파일에서 issuer를 비웠을 때
`Invalid overmind.security setting: issuer is required`로 기동이 막히는 것은 확인했고,
죽인 빈이 `RequiredSettings$Validation`(`@Profile("production")`)이라는 것까지 스택트레이스로
봤다. **프로파일을 뺀 쪽은 실행하지 않았다** — 값을 채우고 나서야 필요를 알아차렸다.
D-M은 여전히 열려 있다. 다시 하려면 값을 일부러 비워야 한다.

**D8은 통과했다.** Auth0가 JWT를 발급했고(불투명 토큰이었으면 `NimbusJwtDecoder`가 파싱에
실패했을 것이다), 토큰의 `aud`가 `https://overmind.flight-friend.com/mcp`, `scope`가
`memory:read memory:write offline_access`, `sub`이 `google-oauth2|…`임을 Auth0 테넌트
로그로 확인했다. 다만 **§8.3이 상정한 실패 경로와는 다른 것이 걸렸다** — Default Audience
미설정이 아니라 API Identifier가 `resource` 값과 달라서(`Service not found`) 막혔다.
스펙 §8.3·§8.4를 그에 맞춰 정정했다(D-P).

**원래 문구:** D4와 D8은 결과를 모르는 검사다. 나머지는 확인이지만 이 둘은 발견이 될 수 있다.
결과가 예상과 다르면 스펙 §13의 해당 결정을 고친다.

**D7은 자동 테스트가 대신할 수 없다.** MockMvc는 Tomcat `RemoteIpValve`를 거치지
않아서 `internal-proxies` 값을 `.*`로 바꿔도 L1이 통과한다. 신뢰 경계는 여기서만
확인된다.

## 실패했을 때

**절차를 고치지 말고 구현을 고친다.** 이 표는 스펙 §10을 사람 손으로 재확인하는 것이고,
표가 통과하도록 기대를 낮추면 확인 자체가 무의미해진다.

9번이 실패하면 새는 지점을 찾아 `LogHygieneTest`에 그 흐름을 **먼저 추가한다.**
수동 확인만 고치면 다음 회귀를 또 손으로 잡게 된다.
