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
#
# **최초 1회성이다.** docker-entrypoint-initdb.d는 데이터 디렉터리가 비어
# 있을 때만(즉 볼륨을 처음 만들 때만) 실행된다. overmind-pgdata는 external
# 볼륨이라 이미 데이터가 있는 상태로 이 스크립트를 다시 태울 방법이 없다 —
# 기존 볼륨에 새 앱 역할이 필요하면 `docker compose exec db psql`로 손으로
# 만들어야 한다(README 참조).
#
# 역할 생성은 idempotent하다(\gexec 조건부 생성) — 재실행해도 에러 없이
# 통과한다. 비밀번호를 echo하지 않는다.
set -Eeuo pipefail

: "${POSTGRES_USER:?POSTGRES_USER가 db.env에 없습니다}"
: "${POSTGRES_DB:?POSTGRES_DB가 db.env에 없습니다}"
: "${OVERMIND_DB_USER:?OVERMIND_DB_USER가 db.env에 없습니다}"
: "${OVERMIND_DB_PASSWORD:?OVERMIND_DB_PASSWORD가 db.env에 없습니다}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
	-- 이미 있으면 아무것도 하지 않는다(idempotent). \gexec가 SELECT 결과를
	-- SQL 명령으로 실행한다 — WHERE 조건이 거짓이면 빈 결과라 아무 것도 안 돈다.
	SELECT 'CREATE ROLE "$OVERMIND_DB_USER" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD ''$OVERMIND_DB_PASSWORD'''
	WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$OVERMIND_DB_USER') \gexec

	-- PostgreSQL 15부터 public 스키마가 PUBLIC에게 CREATE를 기본으로 주지
	-- 않는다. 이 GRANT가 없으면 Flyway의 모든 마이그레이션이 permission
	-- denied for schema public으로 실패한다.
	GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO "$OVERMIND_DB_USER";
	GRANT USAGE, CREATE ON SCHEMA public TO "$OVERMIND_DB_USER";
EOSQL
