-- Run on a TEST database after seeding; all counts describe the SYN26 mock fixtures.
SELECT 'customers' kind,count(*) rows FROM m_customer WHERE customer_code LIKE 'SYN26-%'
UNION ALL SELECT 'products',count(*) FROM m_product WHERE product_code LIKE 'SYN26-%'
UNION ALL SELECT 'orders',count(*) FROM t_order WHERE order_no LIKE 'SYN26-%'
UNION ALL SELECT 'exports',count(*) FROM t_export WHERE export_no LIKE 'SYN26-%'
UNION ALL SELECT 'export_items',count(*) FROM t_export_item WHERE export_id IN (SELECT export_id FROM t_export WHERE export_no LIKE 'SYN26-%')
UNION ALL SELECT 'invoices',count(*) FROM t_invoice WHERE invoice_no LIKE 'SYN26-%'
UNION ALL SELECT 'invoice_details',count(*) FROM t_invoice_detail WHERE invoice_id IN (SELECT invoice_id FROM t_invoice WHERE invoice_no LIKE 'SYN26-%')
UNION ALL SELECT 'packing_headers',count(*) FROM t_packing_list_header WHERE packing_no LIKE 'SYN26-%'
UNION ALL SELECT 'packing_lines',count(*) FROM t_packing_list WHERE packing_list_id IN (SELECT packing_list_id FROM t_packing_list_header WHERE packing_no LIKE 'SYN26-%')
UNION ALL SELECT 'containers',count(*) FROM t_export_container WHERE export_id IN (SELECT export_id FROM t_export WHERE export_no LIKE 'SYN26-%')
UNION ALL SELECT 'monitor',count(*) FROM t_export_monitor WHERE export_id IN (SELECT export_id FROM t_export WHERE export_no LIKE 'SYN26-%')
UNION ALL SELECT 'documents',count(*) FROM t_document WHERE file_name LIKE 'SYN26-%';

-- Expected: 0 mismatching PL package counts or totals.
SELECT h.packing_no,h.package_count_reported,count(l.packing_id) rows_count,
       h.total_gross_weight_kg_reported,sum(l.gross_weight_kg) sum_weight,
       h.total_volume_m3_reported,sum(l.volume_m3) sum_volume
FROM t_packing_list_header h JOIN t_packing_list l ON l.packing_list_id=h.packing_list_id
WHERE h.packing_no LIKE 'SYN26-%'
GROUP BY h.packing_list_id
HAVING count(l.packing_id) <> h.package_count_reported
    OR sum(l.gross_weight_kg) <> h.total_gross_weight_kg_reported
    OR abs(sum(l.volume_m3)-h.total_volume_m3_reported) > 0.001;

-- Expected: 0; simulated ACTUAL states only on EXPORTED demos.
SELECT e.export_no,e.export_status,c.record_basis
FROM t_export e JOIN t_export_container c ON c.export_id=e.export_id
WHERE e.export_no LIKE 'SYN26-%' AND e.export_status<>'EXPORTED' AND c.record_basis='ACTUAL';
