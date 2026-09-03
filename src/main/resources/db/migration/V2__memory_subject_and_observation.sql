-- M0 schema. observation is an append-only event log (spec §2.2).
-- Ordinary processing exposes no update/delete operation; no trigger is needed here.

CREATE TABLE memory_subject (
    id          uuid        PRIMARY KEY,
    type        text        NOT NULL,
    subject_key text,
    created_at  timestamptz NOT NULL,

    CONSTRAINT memory_subject_type_known
        CHECK (type IN ('USER', 'PROJECT')),

    CONSTRAINT memory_subject_key_matches_type
        CHECK ((type = 'USER' AND subject_key IS NULL)
            OR (type = 'PROJECT' AND subject_key IS NOT NULL)),

    CONSTRAINT memory_subject_project_key_form
        CHECK (subject_key IS NULL
            OR (char_length(subject_key) BETWEEN 1 AND 128
                AND subject_key ~ '^[a-z0-9][a-z0-9._-]*$')),

    CONSTRAINT memory_subject_type_key_unique
        UNIQUE (type, subject_key)
);

-- PostgreSQL UNIQUE permits multiple NULL values, so USER needs a partial unique index.
CREATE UNIQUE INDEX memory_subject_single_user
    ON memory_subject ((true))
    WHERE type = 'USER';

CREATE TABLE observation (
    id                     uuid        PRIMARY KEY,
    subject_id             uuid        NOT NULL,
    idempotency_key        text        NOT NULL,
    content                text        NOT NULL,
    observed_at            timestamptz NOT NULL,
    created_at             timestamptz NOT NULL,
    source_client          text        NOT NULL,
    source_conversation_id text        NOT NULL,
    source_message_id      text        NOT NULL,
    ingestion_type         text        NOT NULL,
    input_schema_version   integer     NOT NULL,

    -- Do not cascade: deleting a subject must not silently erase observations.
    CONSTRAINT observation_subject_fk
        FOREIGN KEY (subject_id) REFERENCES memory_subject (id),

    CONSTRAINT observation_idempotency_key_unique
        UNIQUE (idempotency_key),

    -- This is exactly Java String.isBlank() whitespace on Java 21, excluding NBSP.
    CONSTRAINT observation_idempotency_key_not_blank
        CHECK (btrim(idempotency_key, U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\2028\2029\205F\3000') <> ''),

    CONSTRAINT observation_content_not_blank
        CHECK (btrim(content, U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\2028\2029\205F\3000') <> ''),

    CONSTRAINT observation_content_size
        CHECK (octet_length(content) <= 16384),

    CONSTRAINT observation_idempotency_key_size
        CHECK (octet_length(idempotency_key) BETWEEN 1 AND 256),

    CONSTRAINT observation_source_client_not_blank
        CHECK (btrim(source_client, U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\2028\2029\205F\3000') <> ''),

    CONSTRAINT observation_source_client_size
        CHECK (octet_length(source_client) BETWEEN 1 AND 128),

    CONSTRAINT observation_source_conversation_id_not_blank
        CHECK (btrim(source_conversation_id, U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\2028\2029\205F\3000') <> ''),

    CONSTRAINT observation_source_conversation_id_size
        CHECK (octet_length(source_conversation_id) BETWEEN 1 AND 512),

    CONSTRAINT observation_source_message_id_not_blank
        CHECK (btrim(source_message_id, U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\2028\2029\205F\3000') <> ''),

    CONSTRAINT observation_source_message_id_size
        CHECK (octet_length(source_message_id) BETWEEN 1 AND 512),

    CONSTRAINT observation_ingestion_type_known
        CHECK (ingestion_type IN ('DIRECT_MCP')),

    CONSTRAINT observation_input_schema_version_positive
        CHECK (input_schema_version >= 1)
);

-- Recall's keyset ordering (spec §4.4, §5.3).
CREATE INDEX observation_recall_keyset
    ON observation (subject_id, observed_at DESC, created_at DESC, id DESC);
