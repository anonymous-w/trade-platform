package jp.co.tradeplatform.export;

import java.nio.charset.StandardCharsets;
import java.util.*;
import jp.co.tradeplatform.common.SearchText;
import org.springframework.http.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/exports")
public class ExportController {
  private final NamedParameterJdbcTemplate db;
  public ExportController(NamedParameterJdbcTemplate db) { this.db=db; }

  private record Query(String where, Map<String,Object> params) {}
  private Query criteria(String keyword, String invoice, String model, String equipment, String customer, String from, String to) {
    Map<String,Object> p=new HashMap<>();
    StringBuilder w=new StringBuilder(" WHERE 1=1 ");
    if(notBlank(keyword)) {
      w.append(" AND (e.export_no ILIKE :keyword OR c.customer_name ILIKE :keyword OR c.customer_name_en ILIKE :keyword " +
        "OR EXISTS(SELECT 1 FROM t_invoice i WHERE i.export_id=e.export_id AND i.invoice_no ILIKE :keyword) " +
        "OR EXISTS(SELECT 1 FROM t_export_item x WHERE x.export_id=e.export_id AND (x.equipment_name ILIKE :keyword OR x.model_no ILIKE :keyword))) ");
      p.put("keyword",SearchText.contains(keyword));
    }
    if(notBlank(invoice)) {w.append(" AND EXISTS(SELECT 1 FROM t_invoice i WHERE i.export_id=e.export_id AND i.invoice_no ILIKE :invoice) ");p.put("invoice",SearchText.contains(invoice));}
    if(notBlank(model)) {w.append(" AND EXISTS(SELECT 1 FROM t_export_item ei WHERE ei.export_id=e.export_id AND ei.model_no ILIKE :model) ");p.put("model",SearchText.contains(model));}
    if(notBlank(equipment)) {w.append(" AND EXISTS(SELECT 1 FROM t_export_item ei WHERE ei.export_id=e.export_id AND ei.equipment_name ILIKE :equipment) ");p.put("equipment",SearchText.contains(equipment));}
    if(notBlank(customer)) {w.append(" AND c.customer_name ILIKE :customer ");p.put("customer",SearchText.contains(customer));}
    try {
      if(notBlank(from)) {p.put("from",java.time.LocalDate.parse(from));w.append(" AND e.export_date >= :from ");}
      if(notBlank(to)) {p.put("to",java.time.LocalDate.parse(to));w.append(" AND e.export_date <= :to ");}
    } catch (Exception ex) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid date; use YYYY-MM-DD");}
    if (p.containsKey("from") && p.containsKey("to") && ((java.time.LocalDate)p.get("from")).isAfter((java.time.LocalDate)p.get("to")))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"開始日は終了日以前にしてください");
    return new Query(w.toString(),p);
  }
  private static boolean notBlank(String s){return s!=null && !s.isBlank();}
  private final String select = "SELECT e.export_id,e.export_no,e.export_date,e.export_status,e.ship_from,e.ship_to, " +
    "c.customer_name, (SELECT string_agg(DISTINCT x.model_no,', ' ORDER BY x.model_no) FROM t_export_item x WHERE x.export_id=e.export_id) AS models, " +
    "(SELECT string_agg(DISTINCT x.equipment_name,', ' ORDER BY x.equipment_name) FROM t_export_item x WHERE x.export_id=e.export_id) AS equipment, " +
    "(SELECT i.invoice_no FROM t_invoice i WHERE i.export_id=e.export_id ORDER BY i.invoice_id LIMIT 1) AS invoice_no, " +
    "(SELECT string_agg(z.container_type || ' × ' || z.container_qty || ' (' || z.record_basis || ')', ', ' ORDER BY z.container_type || ' × ' || z.container_qty || ' (' || z.record_basis || ')') FROM t_export_container z WHERE z.export_id=e.export_id) AS containers " +
    "FROM t_export e JOIN m_customer c ON c.customer_id=e.customer_id ";

  @GetMapping
  public Map<String,Object> list(@RequestParam(required=false) String keyword, @RequestParam(required=false) String invoiceNo,@RequestParam(required=false) String model,
      @RequestParam(required=false) String equipment,@RequestParam(required=false) String customer,
      @RequestParam(required=false) String dateFrom,@RequestParam(required=false) String dateTo,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    if(page<0||size<1||size>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
    Query q=criteria(keyword,invoiceNo,model,equipment,customer,dateFrom,dateTo);
    Map<String,Object> params=new HashMap<>(q.params());params.put("limit",size);params.put("offset",(long)page*size);
    long total=db.queryForObject("SELECT COUNT(*) FROM t_export e JOIN m_customer c ON e.customer_id=c.customer_id "+q.where(),q.params(),Long.class);
    var rows=db.queryForList(select+q.where()+" ORDER BY e.export_date DESC NULLS LAST,e.export_id DESC LIMIT :limit OFFSET :offset",params);
    return Map.of("rows",rows,"total",total,"page",page,"size",size);
  }
  @GetMapping("/{id}")
  public Map<String,Object> detail(@PathVariable long id) {
    var ex=db.queryForList("SELECT e.*,c.customer_name,c.customer_name_en FROM t_export e JOIN m_customer c ON c.customer_id=e.customer_id WHERE e.export_id=:id",Map.of("id",id));
    if(ex.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Export not found");
    var items=db.queryForList("SELECT ei.*,p.manufacturer FROM t_export_item ei LEFT JOIN m_product p ON p.product_id=ei.product_id WHERE ei.export_id=:id ORDER BY line_no",Map.of("id",id));
    var packing=db.queryForList("SELECT * FROM t_packing_list WHERE export_id=:id ORDER BY packing_list_id,line_no",Map.of("id",id));
    Map<Object,List<Map<String,Object>>> packingByItem=new HashMap<>();
    for(var row:packing)packingByItem.computeIfAbsent(row.get("export_item_id"),ignored->new ArrayList<>()).add(row);
    List<Map<String,Object>> itemRows=new ArrayList<>();
    for(var item:items){
      Map<String,Object> r=new LinkedHashMap<>(item);
      r.put("packing",packingByItem.getOrDefault(item.get("export_item_id"),List.of()));itemRows.add(r);
    }
    Map<String,Object> detail=new LinkedHashMap<>();detail.put("export",ex.get(0));detail.put("items",itemRows);
    detail.put("invoices",db.queryForList("SELECT * FROM t_invoice WHERE export_id=:id ORDER BY invoice_id",Map.of("id",id)));
    detail.put("packingHeaders",db.queryForList("SELECT * FROM t_packing_list_header WHERE export_id=:id ORDER BY packing_list_id",Map.of("id",id)));
    detail.put("containers",db.queryForList("SELECT * FROM t_export_container WHERE export_id=:id ORDER BY record_basis,container_type",Map.of("id",id)));
    detail.put("documents",db.queryForList("SELECT document_id,document_type,file_name,file_size_bytes,created_at FROM t_document WHERE export_id=:id ORDER BY created_at DESC,document_id DESC",Map.of("id",id)));
    return detail;
  }
  @GetMapping(value="/export.csv",produces="text/csv;charset=UTF-8")
  public ResponseEntity<byte[]> csv(@RequestParam(required=false) String keyword, @RequestParam(required=false) String invoiceNo,@RequestParam(required=false) String model,
        @RequestParam(required=false) String equipment,@RequestParam(required=false) String customer,
        @RequestParam(required=false) String dateFrom,@RequestParam(required=false) String dateTo) {
    Query q=criteria(keyword,invoiceNo,model,equipment,customer,dateFrom,dateTo);
    Map<String,Object> p=new HashMap<>(q.params());p.put("limit",10001);
    var rows=db.queryForList(select+q.where()+" ORDER BY e.export_date DESC NULLS LAST,e.export_id DESC LIMIT :limit",p);
    if(rows.size()>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CSV出力は10000件までです。検索条件を絞り込んでください");
    StringBuilder b=new StringBuilder("\uFEFF");b.append("Export No,Invoice No,Date,Customer,Equipment,Model,Container,Status\r\n");
    for(var r:rows){String[] cols={"export_no","invoice_no","export_date","customer_name","equipment","models","containers","export_status"};
      for(int i=0;i<cols.length;i++){if(i>0)b.append(',');b.append(csvValue(r.get(cols[i])));}b.append("\r\n");}
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=exports.csv")
      .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(b.toString().getBytes(StandardCharsets.UTF_8));
  }
  public static String csvValue(Object v){
    String s=v==null?"":String.valueOf(v);
    // Neutralize spreadsheet formula injection even when a downloaded CSV is opened in Excel.
    if(!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0))>=0)s="'"+s;
    return "\""+s.replace("\"","\"\"")+"\"";
  }
}
