
CREATE TABLE system_code (
                             system_code_id numeric(15,0) NOT NULL,
                             row_version numeric(8,0) NOT NULL,
                             create_user varchar NOT NULL,
                             create_oper varchar NOT NULL,
                             create_ts timestamptz(6) NOT NULL,
                             update_user varchar NOT NULL,
                             update_oper varchar NOT NULL,
                             update_ts timestamptz(6) NOT NULL,
                             row_source varchar(85) NOT NULL DEFAULT 'UNK',
                             system_code_custom_class varchar,
                             system_code_custom_data jsonb,
                             table_name varchar(50) NOT NULL,
                             code_order numeric(8,0) NOT NULL,
                             code_value varchar(40) NOT NULL,
                             code_label varchar(100),
                             descr_text varchar,
                             conversion_categoryid int4,
                             alternate_value varchar(30),
                             init_data jsonb,
                             CONSTRAINT systemcode_pk PRIMARY KEY (system_code_id),
                             CONSTRAINT systemcode_code_un UNIQUE (table_name, code_value),
                             CONSTRAINT systemcode_order_un UNIQUE (table_name, code_order),
                             CONSTRAINT systemcode_alternate_un UNIQUE (table_name, alternate_value)
);
CREATE INDEX systemcode_alternate_ix ON system_code USING btree (
    alternate_value COLLATE pg_catalog.default pg_catalog.text_ops ASC NULLS LAST
    );
CREATE INDEX systemcode_codevalue_ix ON system_code USING btree (
    code_value COLLATE pg_catalog.default pg_catalog.text_ops ASC NULLS LAST
    );
COMMENT ON COLUMN system_code.code_label IS 'English code label';
COMMENT ON TABLE system_code IS 'System code table. Codes are grouped by table_name.';