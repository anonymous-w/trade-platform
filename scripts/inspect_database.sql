-- Read-only checks for trade-platform-v0.2 in DBeaver.
-- Connection: 127.0.0.1:5433 / trade_platform / trade_user.

-- 1. Confirm the database and PostgreSQL version.
SELECT current_database(), current_user, version();

-- 2. List application tables and Flyway history.
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;

-- 3. Confirm successful migrations.
SELECT version, description, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;

-- 4. Count the existing records (does not change data).
SELECT 'exports' AS record_type, count(*) AS total FROM t_export
UNION ALL SELECT 'synthetic_exports', count(*) FROM t_export WHERE export_no LIKE 'SYN26-%'
UNION ALL SELECT 'orders', count(*) FROM t_order
UNION ALL SELECT 'documents', count(*) FROM t_document;

-- 5. Inspect recent exports and their customers.
SELECT e.export_id, e.export_no, e.export_date, e.export_status, c.customer_name
FROM t_export e
JOIN m_customer c ON c.customer_id = e.customer_id
ORDER BY e.export_id DESC
LIMIT 20;
