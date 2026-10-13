package jp.co.tradeplatform.export;

import java.util.*;
import jp.co.tradeplatform.common.SearchText;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/containers")
public class ContainerController {
  private final NamedParameterJdbcTemplate db;
  public ContainerController(NamedParameterJdbcTemplate db) {this.db=db;}
  @GetMapping public Map<String,Object> history(@RequestParam(required=false) String model,
          @RequestParam(required=false) String type,@RequestParam(required=false) String basis,
          @RequestParam(defaultValue="0") int page) {
    if(page<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page");
    if(basis!=null && !basis.isBlank() && !Set.of("PLANNED","DOCUMENTED","ACTUAL").contains(basis))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid record basis");
    Map<String,Object> p=new HashMap<>();p.put("limit",20);p.put("offset",(long)page*20);
    StringBuilder filter=new StringBuilder(" WHERE 1=1 ");
    if(model!=null && !model.isBlank()) { filter.append(" AND EXISTS(SELECT 1 FROM t_export_item x WHERE x.export_id=e.export_id AND x.model_no ILIKE :model) ");p.put("model",SearchText.contains(model)); }
    if(type!=null && !type.isBlank()) {filter.append(" AND z.container_type ILIKE :type ");p.put("type",SearchText.contains(type));}
    if(basis!=null && !basis.isBlank()) {filter.append(" AND z.record_basis=:basis ");p.put("basis",basis);}
    String from=" FROM t_export_container z JOIN t_export e ON e.export_id=z.export_id JOIN m_customer c ON c.customer_id=e.customer_id ";
    Long total=db.queryForObject("SELECT count(*) "+from+filter,p,Long.class);
    var rows=db.queryForList("SELECT e.export_id,e.export_no,e.export_date,c.customer_name,z.container_type,z.container_qty,z.record_basis,z.source_text, " +
       "(SELECT string_agg(DISTINCT x.model_no,', ') FROM t_export_item x WHERE x.export_id=e.export_id) AS models " +from+filter+" ORDER BY e.export_date DESC NULLS LAST,z.export_container_id DESC LIMIT :limit OFFSET :offset",p);
    return Map.of("rows",rows,"total",total,"page",page);
  }
}
