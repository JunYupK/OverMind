# 배포

설계 근거는 `docs/superpowers/specs/2026-09-04-overmind-deploy-design.md`에 있다.
여기에는 손 순서만 있다.

## 먼저 채워야 할 값

`mem_limit`이 `compose.yaml`에 주석으로 남아 있다. **채우기 전에는 운영에 쓰지 않는다.**
JVM의 `-XX:MaxRAMPercentage=60`과 짝이라, 한도가 없으면 메모리 압박 때 OOM killer가
앱보다 PostgreSQL을 먼저 죽인다.

인스턴스에서 확인한다:

```bash
nproc
free -m
docker compose version    # v2 플러그인인지 확인
df -h && docker system df # 디스크 여유
```

## 비밀 파일 두 개 — db.env와 app.env

`env_file:`은 파일을 그대로 컨테이너 안에 주입하는 기구이고, `${...}` 치환은
compose 파일 자체의 자리표시자를 채우는 별개 기구다. 이 둘을 섞으면 안 되므로
비밀은 전부 `env_file:` 쪽으로 두고, `${...}` 치환에는 `OVERMIND_TAG` 하나만 쓴다.

- **`db.env`** — postgres 공식 이미지의 부트스트랩 superuser(`POSTGRES_DB`/
  `POSTGRES_USER`/`POSTGRES_PASSWORD`)와, `02-app-role.sh`가 만들 앱 전용
  역할(`OVERMIND_DB_USER`/`OVERMIND_DB_PASSWORD`)을 담는다. **`POSTGRES_USER`는
  `OVERMIND_DB_USER`와 반드시 달라야 한다** — 같으면 앱이 클러스터 superuser로
  접속하게 되어 스펙 §6.2("앱 계정 하나를 쓴다. superuser가 아니다")를 어긴다.
- **`app.env`** — 앱이 실제로 읽는 7개 변수. `OVERMIND_DB_USER`/
  `OVERMIND_DB_PASSWORD`는 `db.env`의 같은 이름의 값과 **정확히 같아야 한다**.

## 최초 1회

```bash
sudo mkdir -p /opt/overmind
sudo cp deploy/compose.yaml /opt/overmind/
sudo cp -r deploy/initdb /opt/overmind/
sudo docker volume create overmind-pgdata      # external 볼륨. compose가 만들지 않는다

sudo install -d -m 0700 -o root -g root /etc/overmind
sudo install -m 0600 -o root -g root deploy/db.env.example /etc/overmind/db.env
sudo install -m 0600 -o root -g root deploy/app.env.example /etc/overmind/app.env
sudo "${EDITOR:-vi}" /etc/overmind/db.env    # POSTGRES_* + OVERMIND_DB_USER/PASSWORD
sudo "${EDITOR:-vi}" /etc/overmind/app.env   # OVERMIND_DB_USER/PASSWORD는 위와 같은 값으로
printf 'OVERMIND_CURSOR_SECRET=%s\n' "$(openssl rand -hex 32)" \
  | sudo tee -a /etc/overmind/app.env >/dev/null

echo "OVERMIND_TAG=<master의 커밋 sha>" | sudo tee /opt/overmind/.env
```

`/opt/overmind/.env`에는 **`OVERMIND_TAG` 하나만** 있어야 한다. 이 파일은
compose 파일 안의 `${...}` **치환**에 쓰이는 것이지, 컨테이너 안으로 주입되는
`env_file:`이 아니다 — 여기에 다른 값을 더 적어도 컨테이너 안에는 나타나지
않는다. DB 비밀은 위에서 이미 `/etc/overmind/{db,app}.env`에 들어갔다.

### 첫 기동

```bash
cd /opt/overmind
sudo docker compose pull && sudo docker compose up -d
sudo docker compose logs -f db app   # db가 healthy, 이어서 app이 Flyway를 통과하는지 확인
```

**앱 전용 역할은 이 첫 기동에서 딱 한 번만 만들어진다.** `docker-entrypoint-
initdb.d`(`01-vector.sql`, `02-app-role.sh`)는 데이터 디렉터리가 비어 있을
때만 실행되기 때문이다. **볼륨이 external이라 재기동해도 이 초기화는 다시
돌지 않는다** — 이미 데이터가 있는 볼륨에 새 앱 역할이 필요해지면(예:
`OVERMIND_DB_USER`를 바꾸는 경우) `docker compose exec db psql`로 손으로
만들어야 한다. `02-app-role.sh`를 그대로 복사해 붙여 넣으면 된다.

## 배포와 롤백

```bash
cd /opt/overmind
sudo sed -i "s/^OVERMIND_TAG=.*/OVERMIND_TAG=<새 sha>/" .env
sudo docker compose pull && sudo docker compose up -d
```

롤백은 sha를 되돌리고 같은 두 줄이다. `latest`로 배포하지 않는다 — 무엇이 돌고
있는지 알 수 없고 롤백 대상도 사라진다.

## 절대 하지 않는 것

- `docker compose down -v` — 볼륨이 external이라 삭제되지 않지만, 습관으로 만들지 않는다
- `ports: "8080:8080"` — 접두사를 빼면 Docker가 firewalld를 우회해 인터넷에 연다
- `POSTGRES_USER`와 `OVERMIND_DB_USER`를 같은 값으로 두는 것 — 앱이 superuser로
  접속하게 된다(스펙 §6.2 위반, `01-vector.sql`·`02-app-role.sh`의 전제가 깨짐)
