-- OverMind는 canonical memory의 임베딩 검색에 pgvector를 쓴다.
-- 확장 생성을 마이그레이션에 두어, 테스트가 실제 프로덕션 스키마 절차를 거치게 한다.
CREATE EXTENSION IF NOT EXISTS vector;
