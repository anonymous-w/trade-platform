-- Preserve V1 and existing rows; reject duplicate physical file references.
ALTER TABLE t_document ADD CONSTRAINT uk_t_document_storage_key UNIQUE (storage_key);
-- Existing composite foreign keys already protect export/item ownership.
-- Detail lookup filters by item alone, so the export-leading index is insufficient.
CREATE INDEX ix_packing_export_lines ON t_packing_list (export_id, packing_list_id, line_no);
CREATE INDEX ix_export_date_page ON t_export (export_date DESC NULLS LAST, export_id DESC);
CREATE INDEX ix_order_date_page ON t_order (order_date DESC, order_id DESC);
CREATE INDEX ix_document_recent ON t_document (created_at DESC, document_id DESC);
