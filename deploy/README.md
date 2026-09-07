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

## 백업

### 설치

```bash
sudo cp -r deploy/backup /opt/overmind/
sudo chmod +x /opt/overmind/backup/overmind-backup.sh

# gpg 패스프레이즈. 값이 셸 히스토리에 남지 않는다.
printf '%s\n' "$(openssl rand -base64 32)" | sudo tee /etc/overmind/backup.pass >/dev/null
sudo chmod 0600 /etc/overmind/backup.pass

sudo cp /opt/overmind/backup/overmind-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now overmind-backup.timer
sudo systemctl start overmind-backup.service    # 한 번 즉시 돌려본다
journalctl -u overmind-backup -n 30
```

`overmind-backup.sh`는 `/etc/overmind/db.env`만 읽는다(`POSTGRES_USER`로
`pg_dump`를 부른다 — 이유는 스크립트 상단 주석 참조: 부트스트랩 superuser는
권한 검사를 우회하므로 앱 역할의 소유권에 기대는 것보다 더 완전한 덤프를
보장한다). `app.env`는 이 스크립트가 열지 않지만 복구 전제조건으로 아래
"박스 밖으로 내보내기"에 포함해야 한다.

**`/etc/overmind/backup.pass`의 사본을 박스 밖에 둔다.** 이걸 잃으면 백업을
복호화할 수 없다.

### 박스 밖으로 내보내기

로컬만으로는 백업이 아니다 — 인스턴스가 죽으면 백업도 같이 죽는다.
OCI Always Free에 20 GB 오브젝트 스토리지가 포함된다. 버킷은 private으로 만들고
(서버측 암호화는 기본), `oci os object put`으로 올린다. gpg는 그 위의 이중 방어라
OCI 콘솔 접근권만으로는 내용을 볼 수 없다.

M0 데이터는 1인 관찰 이벤트 로그라 덤프가 한동안 KB~MB 단위다. 용량은 제약이 아니다.

**`.dump.gpg` 파일만으로는 복구가 끝나지 않는다.** 아래 두 파일의 사본도
박스 밖에 따로 둔다 — 둘 다 DB 덤프 안에는 없는 값이다:

- **`/etc/overmind/db.env`** — 복원할 때 붙을 계정(`POSTGRES_USER`/
  `POSTGRES_PASSWORD`, `OVERMIND_DB_USER`/`OVERMIND_DB_PASSWORD`)이 여기
  있다. 이게 없으면 복호화한 덤프가 있어도 무슨 계정으로 `pg_restore`를
  부를지 알 수 없다.
- **`/etc/overmind/app.env`** — `OVERMIND_CURSOR_SECRET`이 여기 있다. DB를
  통째로 복원해도 이 값이 바뀌면 기존에 발급된 커서를 못 쓴다(데이터
  손실은 아니지만 클라이언트는 페이지네이션을 처음부터 시작해야 한다).
  이건 백업이 아니라 복구 전제조건이다 — 잃으면 DB는 복원돼도 서비스는
  이전과 같은 상태로 복원되지 않는다.

### 복원 드릴 — 이걸 해야 백업이다

**운영 DB에 복원하지 않는다.** 별도 컨테이너에 복원하고 행 수를 대조한다.

```bash
docker run -d --name overmind-restore-drill \
  -e POSTGRES_PASSWORD=drill -e POSTGRES_DB=overmind -e POSTGRES_USER=drill \
  pgvector/pgvector:pg16
sleep 10

gpg --batch --decrypt --passphrase-file /etc/overmind/backup.pass \
    /var/backups/overmind/<최신>.dump.gpg \
  | docker exec -i overmind-restore-drill pg_restore -U drill -d overmind --no-owner

# 원본과 대조 -- 원본 쪽 계정은 db.env의 부트스트랩 superuser다(백업을
# 그 계정으로 떴으므로 조회도 같은 계정으로 맞춘다)
set -a; source /etc/overmind/db.env; set +a
docker compose -f /opt/overmind/compose.yaml exec -T db \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc 'SELECT count(*) FROM observation'
docker exec -i overmind-restore-drill \
  psql -U drill -d overmind -tAc 'SELECT count(*) FROM observation'

docker rm -f overmind-restore-drill
```

두 숫자가 같아야 한다. 다르면 백업이 불완전한 것이고, **절차를 고치지 말고 원인을 찾는다.**

## 절대 하지 않는 것

- `docker compose down -v` — 볼륨이 external이라 삭제되지 않지만, 습관으로 만들지 않는다
- `ports: "8080:8080"` — 접두사를 빼면 Docker가 firewalld를 우회해 인터넷에 연다
- `POSTGRES_USER`와 `OVERMIND_DB_USER`를 같은 값으로 두는 것 — 앱이 superuser로
  접속하게 된다(스펙 §6.2 위반, `01-vector.sql`·`02-app-role.sh`의 전제가 깨짐)
- 복원 드릴을 운영 `db` 서비스에 직접 하는 것 — 반드시 별도 컨테이너
  (`overmind-restore-drill`)에 한다
