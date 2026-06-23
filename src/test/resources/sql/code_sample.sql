-- ============================================================
-- Schema: code_sample
-- ============================================================

CREATE SCHEMA IF NOT EXISTS code_sample;

-- ──────────────────────────────────────────────
-- TABLE: users
-- ──────────────────────────────────────────────
CREATE TABLE code_sample.users (
                                   id             INTEGER       NOT NULL,
                                   source         VARCHAR(30)   NOT NULL DEFAULT 'Knoppen',
                                   type           VARCHAR(30),
                                   createTs       TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
                                   approvedTs     TIMESTAMP,
                                   username       VARCHAR(255)  NOT NULL,
                                   metadata       JSONB         DEFAULT '[]'::jsonb,

                                   CONSTRAINT pk_users PRIMARY KEY (id),
                                   CONSTRAINT uq_users_username UNIQUE (username),
                                   CONSTRAINT ck_users_type CHECK (type IN ('USER', 'SUPERVISOR', 'ADMIN')),
                                   CONSTRAINT ck_users_source CHECK (source ~ '^[A-Za-z0-9_]+$')
    );

-- ──────────────────────────────────────────────
-- TABLE: tag
-- Simple lookup table. ON CONFLICT DO NOTHING.
-- ──────────────────────────────────────────────
CREATE TABLE code_sample.tag (
                                 id             INTEGER       NOT NULL,
                                 name           VARCHAR(100)  NOT NULL,
                                 column_order   INTEGER,

                                 CONSTRAINT pk_tag PRIMARY KEY (id),
                                 CONSTRAINT uq_tag_name UNIQUE (name)
);

-- ──────────────────────────────────────────────
-- TABLE: post
-- ──────────────────────────────────────────────
CREATE TABLE code_sample.post (
                                  id               INTEGER       NOT NULL,
                                  user_id          INTEGER       NOT NULL,
                                  title            TEXT          NOT NULL,
                                  percent_approved NUMERIC(8,2)  NOT NULL,
                                  column_order     INTEGER,

                                  CONSTRAINT pk_post PRIMARY KEY (id),
                                  CONSTRAINT fk_post_user FOREIGN KEY (user_id)
                                      REFERENCES code_sample.users (id)
                                      ON UPDATE CASCADE
);

-- ──────────────────────────────────────────────
-- TABLE: post_tag  (junction — compound PK + compound FK)
-- ──────────────────────────────────────────────
CREATE TABLE code_sample.post_tag (
                                      post_id    INTEGER  NOT NULL,
                                      tag_id     INTEGER  NOT NULL,
                                      column_order INTEGER,

                                      CONSTRAINT pk_post_tag PRIMARY KEY (post_id, tag_id),
                                      CONSTRAINT fk_post_tag_post FOREIGN KEY (post_id)
                                          REFERENCES code_sample.post (id)
                                          ON UPDATE CASCADE,
                                      CONSTRAINT fk_post_tag_tag FOREIGN KEY (tag_id)
                                          REFERENCES code_sample.tag (id)
                                          ON UPDATE CASCADE
);

-- ──────────────────────────────────────────────
-- TABLE: audit_log
-- Append-only. ON CONFLICT DO NOTHING.
-- TIMESTAMP_OFFSET and FOREIGN_CYCLE generators.
-- ──────────────────────────────────────────────
CREATE TABLE code_sample.audit_log (
                                       id          INTEGER       NOT NULL,
                                       user_id     INTEGER,
                                       post_id     INTEGER,
                                       event       VARCHAR(50)   NOT NULL,
                                       event_ts    TIMESTAMP,
                                       notes       TEXT,

                                       CONSTRAINT pk_audit_log PRIMARY KEY (id),
                                       CONSTRAINT fk_audit_log_user FOREIGN KEY (user_id)
                                           REFERENCES code_sample.users (id),
                                       CONSTRAINT fk_audit_log_post FOREIGN KEY (post_id)
                                           REFERENCES code_sample.post (id)
);

-- ──────────────────────────────────────────────
-- TABLE: post_approval
-- Compound unique constraint. FK to post.
-- ──────────────────────────────────────────────
CREATE TABLE code_sample.post_approval (
                                           id           INTEGER       NOT NULL,
                                           post_id      INTEGER       NOT NULL,
                                           approver_id  INTEGER       NOT NULL,
                                           decision     VARCHAR(20)   NOT NULL,
                                           decided_ts   TIMESTAMP,
                                           column_order INTEGER,

                                           CONSTRAINT pk_post_approval PRIMARY KEY (id),
                                           CONSTRAINT uq_post_approval_post_approver UNIQUE (post_id, approver_id),
                                           CONSTRAINT fk_post_approval_post FOREIGN KEY (post_id)
                                               REFERENCES code_sample.post (id),
                                           CONSTRAINT fk_post_approval_approver FOREIGN KEY (approver_id)
                                               REFERENCES code_sample.users (id),
                                           CONSTRAINT ck_post_approval_decision CHECK (decision IN ('APPROVED','REJECTED','ABSTAINED'))
);
