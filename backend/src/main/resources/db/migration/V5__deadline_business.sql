-- One shared business source for sales and monitoring. Unknown business fields stay null.
CREATE TABLE t_deadline_contract (
 contract_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 contract_no TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_deadline_contract_no ON t_deadline_contract(contract_no);
CREATE TABLE t_deadline_equipment (
 equipment_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 contract_id BIGINT NOT NULL REFERENCES t_deadline_contract(contract_id),
 product_name TEXT, model_no TEXT
);
CREATE TABLE t_deadline_permit (
 permit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 equipment_id BIGINT NOT NULL REFERENCES t_deadline_equipment(equipment_id),
 import_key CHAR(64) NOT NULL UNIQUE,
 export_permit_no TEXT, service_permit_no TEXT,
 permit_date DATE, valid_until DATE, installation_date DATE, report_due_date DATE, bl_date DATE,
 person_in_charge TEXT, responsible_person TEXT, conditions_text TEXT,
 source_data JSONB NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 created_by VARCHAR(50) NOT NULL REFERENCES m_user(user_id),
 updated_by VARCHAR(50) NOT NULL REFERENCES m_user(user_id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_deadline_permit_equipment ON t_deadline_permit(equipment_id);
CREATE INDEX ix_deadline_permit_expiry ON t_deadline_permit(valid_until);
CREATE TABLE t_deadline_year (
 year_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 permit_id BIGINT NOT NULL REFERENCES t_deadline_permit(permit_id),
 year_no INTEGER NOT NULL CHECK(year_no BETWEEN 1 AND 5),
 source_due_date DATE, source_planned_date DATE, source_completed_date DATE, source_text TEXT,
 -- Future user-maintained values are independent of imported source fields.
 manual_planned_date DATE, manual_completed_date DATE, manual_status TEXT, manual_notes TEXT,
 UNIQUE(permit_id,year_no)
);
CREATE TABLE t_deadline_import (
 import_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 file_name VARCHAR(255) NOT NULL, file_sha256 CHAR(64) NOT NULL, submission_key CHAR(64) NOT NULL UNIQUE,
 file_content BYTEA NOT NULL CHECK(octet_length(file_content)<=10485760),
 file_type VARCHAR(5) NOT NULL CHECK(file_type IN ('xlsx','xls')),
 sheet_name TEXT NOT NULL, parser_version TEXT NOT NULL,
 added_count INTEGER NOT NULL, updated_count INTEGER NOT NULL, skipped_count INTEGER NOT NULL, failed_count INTEGER NOT NULL,
 created_by VARCHAR(50) NOT NULL REFERENCES m_user(user_id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE t_deadline_import_row (
 import_row_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 import_id BIGINT NOT NULL REFERENCES t_deadline_import(import_id),
 source_row INTEGER NOT NULL,
 permit_id BIGINT REFERENCES t_deadline_permit(permit_id),
 action VARCHAR(10) NOT NULL CHECK(action IN ('ADD','UPDATE','SKIP','ERROR')),
 source_data JSONB NOT NULL,
 UNIQUE(import_id,source_row)
);
CREATE INDEX ix_deadline_import_row_permit ON t_deadline_import_row(permit_id,import_id DESC);

CREATE INDEX ix_deadline_import_file ON t_deadline_import(file_sha256);
