package jp.co.tradeplatform.config;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Supporting read-only lists. No export/sales search behavior is changed. */
@RestController
@RequestMapping("/api/records")
public class ReadOnlyRecordsController {
  private final NamedParameterJdbcTemplate db;
  public ReadOnlyRecordsController(NamedParameterJdbcTemplate db) {this.db = db;}

  @GetMapping("/monitoring")
  public List<Map<String,Object>> monitoring() {
    return db.queryForList("""
      SELECT m.monitor_id,e.export_id,e.export_no,ei.equipment_name,ei.model_no,
             m.monitor_start_date,m.monitor_end_date,m.monitor_status,
             m.last_check_date,m.next_check_date,m.remarks
      FROM t_export_monitor m
      JOIN t_export e ON e.export_id=m.export_id
      JOIN t_export_item ei ON ei.export_item_id=m.export_item_id
      ORDER BY m.monitor_start_date DESC,m.monitor_id DESC LIMIT 100
      """,Map.of());
  }
  @GetMapping("/documents")
  public List<Map<String,Object>> documents() {
    return db.queryForList("""
      SELECT document_id, export_id, order_id, document_type, file_name,
             file_size_bytes, created_at
      FROM t_document ORDER BY created_at DESC,document_id DESC LIMIT 80
      """,Map.of());
  }
}
