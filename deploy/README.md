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

## 최초 1회

```bash
sudo mkdir -p /opt/overmind
sudo cp deploy/compose.yaml /opt/overmind/
sudo cp -r deploy/initdb /opt/overmind/
sudo docker volume create overmind-pgdata      # external 볼륨. compose가 만들지 않는다

sudo install -d -m 0700 -o root -g root /etc/overmind
sudo install -m 0600 -o root -g root deploy/overmind.env.example /etc/overmind/overmind.env
sudo "${EDITOR:-vi}" /etc/overmind/overmind.env
printf 'OVERMIND_CURSOR_SECRET=%s\n' "$(openssl rand -hex 32)" \
  | sudo tee -a /etc/overmind/overmind.env >/dev/null

echo "OVERMIND_TAG=<master의 커밋 sha>" | sudo tee /opt/overmind/.env
```

`.env`와 `env_file:`은 다른 기구다. `/opt/overmind/.env`는 compose 파일 안의
`${...}` **치환**에 쓰이고, `env_file:`은 **컨테이너 안으로 주입**된다.
`OVERMIND_TAG`를 `env_file` 쪽에 두면 이미지 태그가 치환되지 않아 pull이 실패한다.

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
