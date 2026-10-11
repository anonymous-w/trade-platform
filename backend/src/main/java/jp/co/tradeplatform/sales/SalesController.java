package jp.co.tradeplatform.sales;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Read-only candidate sales module. Sales creation/approval needs client confirmation. */
@RestController
@RequestMapping("/api/sales")
public class SalesController {
  private final NamedParameterJdbcTemplate db;
  public SalesController(NamedParameterJdbcTemplate db){this.db=db;}
  @GetMapping public Map<String,Object> list(@RequestParam(required=false)String q,@RequestParam(defaultValue="0")int page){
    if(page<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page");
    Map<String,Object> p=new HashMap<>();p.put("limit",20);p.put("offset",(long)page*20);
    String where="";
    if(q!=null&&!q.isBlank()) {where=" WHERE o.order_no ILIKE :q OR c.customer_name ILIKE :q ";p.put("q","%"+q.trim()+"%");}
    String from=" FROM t_order o JOIN m_customer c ON c.customer_id=o.customer_id ";
    Long total=db.queryForObject("SELECT count(*)"+from+where,p,Long.class);
    var rows=db.queryForList("SELECT o.order_id,o.order_no,o.order_date,o.order_status,o.total_amount,o.currency_code,c.customer_name "+from+where+" ORDER BY o.order_date DESC,o.order_id DESC LIMIT :limit OFFSET :offset",p);
    return Map.of("rows",rows,"total",total,"page",page);
  }
  @GetMapping(value="/export.csv",produces="text/csv;charset=UTF-8")
  public org.springframework.http.ResponseEntity<byte[]> csv(@RequestParam(required=false) String q){
    Map<String,Object> p=new HashMap<>();p.put("limit",10000);
    String where="";
    if(q!=null&&!q.isBlank()){where=" WHERE o.order_no ILIKE :q OR c.customer_name ILIKE :q ";p.put("q","%"+q.trim()+"%");}
    var rows=db.queryForList("SELECT o.order_no,o.order_date,c.customer_name,o.total_amount,o.currency_code,o.order_status FROM t_order o JOIN m_customer c ON c.customer_id=o.customer_id "+where+" ORDER BY o.order_date DESC LIMIT :limit",p);
    StringBuilder text=new StringBuilder("\uFEFFOrder No,Date,Customer,Amount,Currency,Status\r\n");
    for(var r:rows){String[] fields={"order_no","order_date","customer_name","total_amount","currency_code","order_status"};
      for(int i=0;i<fields.length;i++){if(i>0)text.append(',');text.append(jp.co.tradeplatform.export.ExportController.csvValue(r.get(fields[i])));}text.append("\r\n");}
    return org.springframework.http.ResponseEntity.ok()
      .header("Content-Disposition","attachment; filename=sales.csv")
      .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
      .body(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable long id){
    var order=db.queryForList("SELECT o.*,c.customer_name FROM t_order o JOIN m_customer c ON c.customer_id=o.customer_id WHERE o.order_id=:id",Map.of("id",id));
    if(order.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
    return Map.of("order",order.get(0),"items",db.queryForList("SELECT * FROM t_order_detail WHERE order_id=:id ORDER BY line_no",Map.of("id",id)),
      "documents",db.queryForList("SELECT document_id,document_type,file_name,file_size_bytes,created_at FROM t_document WHERE order_id=:id",Map.of("id",id)));
  }
}
