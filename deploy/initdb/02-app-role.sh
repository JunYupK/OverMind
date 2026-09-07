#!/usr/bin/env bash
# 앱 전용 non-superuser 역할을 만든다. .sql이 아니라 셸인 이유는 db.env로
# 주입된 OVERMIND_DB_USER/OVERMIND_DB_PASSWORD 환경변수를 읽어야 해서다.
#
# 01-vector.sql 다음(파일명 정렬)에, postgres superuser로 실행된다. pgvector
# 확장은 01-vector.sql이 이미 superuser로 만들어 뒀으므로 이 역할은 확장
# 생성 권한이 필요 없다 — NOSUPERUSER로 만들어도 Flyway V1(CREATE EXTENSION
# IF NOT EXISTS vector)은 이미 존재하는 확장을 보고 통과한다.
#
# 스펙 §6.2: "앱 계정 하나를 쓴다. superuser가 아니다." POSTGRES_USER(db.env의
# 부트스트랩 계정)와 이 역할은 반드시 다른 계정이어야 한다 — 같으면 앱이
# superuser로 접속하게 되어 이 스크립트를 만든 이유 자체가 사라진다.
# README·db.env.example·app.env.example의 경고는 산문일 뿐이라 아무것도
# 막지 못한다 — 아래 가드가 이를 실제로 강제한다(exit 1로 초기화를 중단).
#
# **최초 1회성이다.** docker-entrypoint-initdb.d는 데이터 디렉터리가 비어
# 있을 때만(즉 볼륨을 처음 만들 때만) 실행된다. overmind-pgdata는 external
# 볼륨이라 이미 데이터가 있는 상태로 이 스크립트를 다시 태울 방법이 없다 —
# 기존 볼륨에 새 앱 역할이 필요하면 `docker compose exec db psql`로 손으로
# 만들어야 한다(README 참조).
#
# 역할 생성은 idempotent하다(\gexec 조건부 생성) — 재실행해도 에러 없이
# 통과한다. 비밀번호를 echo하지 않는다.
#
# **role/pw 값을 SQL 텍스트에 셸로 접합하지 않고 psql 변수(-v)로 넘긴다.**
# db.env 값은 공격자 입력이 아니라 운영자가 직접 쓴 값이지만, 거기에
# 작은따옴표나 큰따옴표가 하나만 있어도 문자열 접합 방식은 SQL 리터럴/
# 식별자를 그 자리에서 깨뜨린다 — ON_ERROR_STOP=1 + set -e가 즉시 컨테이너
# 초기화를 중단시키고, 볼륨이 external이라 재시도도 손으로 지우고 다시
# 만들어야 한다. format()의 %I(식별자)·%L(리터럴)이 이스케이핑을 정확히
# 하므로, 다음에 이 파일을 고칠 사람은 이걸 "간단하게" 문자열 접합으로
# 되돌리지 말 것.
set -Eeuo pipefail

: "${POSTGRES_USER:?POSTGRES_USER가 db.env에 없습니다}"
: "${POSTGRES_DB:?POSTGRES_DB가 db.env에 없습니다}"
: "${OVERMIND_DB_USER:?OVERMIND_DB_USER가 db.env에 없습니다}"
: "${OVERMIND_DB_PASSWORD:?OVERMIND_DB_PASSWORD가 db.env에 없습니다}"

# 아래 SQL의 WHERE NOT EXISTS(idempotency 체크)는 두 이름이 같으면 "이미
# 있다"고 보고 CREATE ROLE을 그냥 건너뛴다 — 에러가 안 난다. 그러면 이미
# 존재하는 그 역할(=POSTGRES_USER=superuser)에 GRANT만 추가로 얹고 조용히
# 성공한다. SQL의 idempotency 체크에 이 검사를 맡길 수 없는 이유가 이것이다
# — 여기서 셸이 먼저, psql을 부르기 전에 끊어야 한다.
if [ "$POSTGRES_USER" = "$OVERMIND_DB_USER" ]; then
	echo "02-app-role.sh: POSTGRES_USER와 OVERMIND_DB_USER가 둘 다 '$POSTGRES_USER'로 같습니다." >&2
	echo "02-app-role.sh: POSTGRES_USER는 initdb --username으로 만들어지는 클러스터 superuser입니다." >&2
	echo "02-app-role.sh: 두 값이 같으면 앱이 superuser로 접속하게 되어 스펙 §6.2" >&2
	echo "02-app-role.sh: (\"앱 계정 하나를 쓴다. superuser가 아니다\")를 어깁니다. db.env에서" >&2
	echo "02-app-role.sh: OVERMIND_DB_USER를 POSTGRES_USER와 다른 이름으로 바꾸세요." >&2
	exit 1
fi

# 헤레독 구분자를 따옴표로 감쌌다(<<-'EOSQL') — 셸이 안의 $를 전혀 건드리지
# 않게 하려는 의도다. 값은 -v로만 psql에 전달되고, SQL 쪽에서는 :'role'
# 같은 psql 변수 치환이 PostgreSQL이 이해하는 안전한 리터럴로 바꿔 준다.
psql -v ON_ERROR_STOP=1 \
     -v role="$OVERMIND_DB_USER" \
     -v pw="$OVERMIND_DB_PASSWORD" \
     -v db="$POSTGRES_DB" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-'EOSQL'
	-- 이미 있으면 아무것도 하지 않는다(idempotent). \gexec가 SELECT 결과를
	-- SQL 명령으로 실행한다 — WHERE 조건이 거짓이면 빈 결과라 아무 것도 안 돈다.
	-- format()의 %I/%L이 식별자/리터럴을 각각 올바르게 이스케이프하므로
	-- role·pw에 따옴표가 들어 있어도 안전하다.
	SELECT format('CREATE ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', :'role', :'pw')
	WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'role') \gexec

	-- PostgreSQL 15부터 public 스키마가 PUBLIC에게 CREATE를 기본으로 주지
	-- 않는다. 이 GRANT가 없으면 Flyway의 모든 마이그레이션이 permission
	-- denied for schema public으로 실패한다. GRANT에는 format()을 직접 쓸
	-- 수 없어 SELECT ... \gexec로 같은 방식을 적용한다.
	SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'db', :'role') \gexec
	SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'role') \gexec
EOSQL
