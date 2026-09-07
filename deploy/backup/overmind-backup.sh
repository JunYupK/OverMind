#!/usr/bin/env bash
# OverMind DB 백업. systemd timer가 하루 한 번 부른다.
#
# 앱은 상태가 없다 -- 이미지는 GHCR에, 설정은 /etc/overmind에 있다.
# 이 스크립트가 실제로 여는 파일은 db.env뿐이지만, /etc/overmind/db.env와
# /etc/overmind/app.env 둘 다 박스 밖에 사본을 따로 둬야 한다. 둘 다 이
# 스크립트가 만드는 .dump.gpg 안에는 없다(DB 안의 데이터일 뿐이다):
#   - db.env: 이 덤프를 복원할 때 붙을 계정(POSTGRES_USER/PASSWORD,
#     OVERMIND_DB_USER/PASSWORD)을 담는다. 이게 없으면 복호화된 덤프가
#     있어도 무슨 계정으로 pg_restore를 부를지 알 수 없다.
#   - app.env: OVERMIND_CURSOR_SECRET을 담는다. DB를 통째로 복원해도 이
#     값이 바뀌면 기존에 발급된 커서를 쓸 수 없다 -- 이건 백업이 아니라
#     복구 전제조건이다.
#
# pg_dump는 부트스트랩 superuser(db.env의 POSTGRES_USER)로 돌린다. 앱
# 전용 역할(OVERMIND_DB_USER)은 자기가 만든 테이블을 전부 소유해 오늘은
# 문제없이 다 보이지만, pg_dump는 그 롤이 SELECT할 수 있는 객체만 담는다
# -- 소유권에 기대는 방식은 나중에 다른 계정이 만든 스키마·확장·테이블이
# "실패" 없이 조용히 빠지는 구멍이 된다. superuser는 권한 검사를 우회하므로
# 무엇이 있든 전부 담긴다. 이건 오늘의 정확성이 아니라 미래의 침묵을 막는
# 선택이다.
set -euo pipefail

COMPOSE_FILE=${COMPOSE_FILE:-/opt/overmind/compose.yaml}
BACKUP_DIR=${BACKUP_DIR:-/var/backups/overmind}
PASSPHRASE_FILE=${PASSPHRASE_FILE:-/etc/overmind/backup.pass}
DB_ENV_FILE=${DB_ENV_FILE:-/etc/overmind/db.env}
KEEP_DAILY=${KEEP_DAILY:-7}

# db.env에서 DB 자격(POSTGRES_USER/POSTGRES_DB)을 읽는다. 값을 echo하지
# 않는다 (C-8).
set -a
# shellcheck disable=SC1090
source "$DB_ENV_FILE"
set +a

install -d -m 0700 "$BACKUP_DIR"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$BACKUP_DIR/overmind-$stamp.dump.gpg"

# pg_dump가 실패해도 gpg는 EOF를 그냥 빈 평문으로 받아 유효한(0바이트가
# 아닌!) 암호화 컨테이너를 만들어 낸다 -- 그래서 set -o pipefail이 파이프
# 실패를 잡아 set -e로 스크립트를 여기서 바로 끝내더라도, 그 직전에 이미
# $target에는 "성공한 것처럼 보이는" 껍데기 파일이 만들어져 있다. 이 트랩이
# 없으면 그 파일이 지워지지 않고 남아 다음 날짜의 정상 백업과 나란히
# KEEP_DAILY 슬롯 하나를 차지한 채 복원 드릴 때까지 조용히 버틴다. 검사를
# 마치고 유효성이 확인되면 바로 아래에서 트랩을 해제한다.
trap 'rm -f "$target"' ERR

# -Fc: 압축 내장 + pg_restore로 선택적 복원 가능.
# 파이프 실패를 놓치지 않으려고 set -o pipefail을 켜 뒀다 -- 이게 없으면
# pg_dump가 죽어도 gpg의 exit status만 보여 실패가 감춰진다.
docker compose -f "$COMPOSE_FILE" exec -T db \
    pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB" \
  | gpg --batch --yes --symmetric --cipher-algo AES256 \
        --passphrase-file "$PASSPHRASE_FILE" \
  > "$target"

chmod 0600 "$target"

# 빈 파일이 조용히 쌓이는 것을 막는다. pg_dump가 아무것도 못 만들었는데도
# gpg가 그 위에서 성공해 버리는 경우를 이 검사가 잡는다(위 트랩이 잡는
# "0바이트 아닌 껍데기"와는 다른 경로 -- 둘 다 막아야 한다).
if [ ! -s "$target" ]; then
    echo "backup produced an empty file: $target" >&2
    rm -f "$target"
    exit 1
fi

# 여기까지 왔으면 $target은 유효한 백업이다. 이후 실패(보존 정리 등)가
# 이미 만든 정상 백업을 지우면 안 되므로 트랩을 해제한다.
trap - ERR

# 보존: 일간 KEEP_DAILY개. 그 밖은 삭제.
find "$BACKUP_DIR" -name 'overmind-*.dump.gpg' -type f -printf '%T@ %p\n' \
  | sort -rn | tail -n "+$((KEEP_DAILY + 1))" | cut -d' ' -f2- \
  | xargs -r rm -f

echo "backup ok: $target ($(stat -c%s "$target") bytes)"
