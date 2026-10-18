package jp.co.tradeplatform.importdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import jp.co.tradeplatform.common.SearchText;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only management search across imported source batches; no fabricated export records. */
@RestController
@RequestMapping("/api/packing-sources")
public class PackingSourceController {
  private final NamedParameterJdbcTemplate db;
  private final ObjectMapper json;
  public PackingSourceController(NamedParameterJdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}

  @GetMapping
  public Map<String,Object> search(@RequestParam(defaultValue="") String keyword,
      @RequestParam(defaultValue="") String model,@RequestParam(defaultValue="") String container,
      @RequestParam(defaultValue="") String departurePort,@RequestParam(defaultValue="") String destinationPort,
      @RequestParam(defaultValue="false") boolean reviewOnly,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    if(page<0 || size<1 || size>100 || List.of(keyword,model,container,departurePort,destinationPort).stream().anyMatch(s->s.length()>200))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"検索条件は200文字以内、ページサイズは1〜100で指定してください");
    Map<String,Object> params=new HashMap<>(Map.of("limit",size,"offset",(long)page*size));
    StringBuilder where=new StringBuilder(" WHERE 1=1");
    if(reviewOnly)where.append(" AND r.needs_review");
    if(!keyword.isBlank()) {
      params.put("q",SearchText.contains(keyword.strip()));
      where.append("""
         AND (r.contract_no ILIKE :q OR r.equipment_name ILIKE :q OR r.model_no ILIKE :q OR r.part_name ILIKE :q
         OR r.data->>'partCode' ILIKE :q OR r.data->>'containerText' ILIKE :q
         OR r.data->>'departurePort' ILIKE :q OR r.data->>'destinationPort' ILIKE :q)
        """);
    }
    filter(where,params,"r.model_no","model",model);
    filter(where,params,"r.data->>'containerText'","container",container);
    filter(where,params,"r.data->>'departurePort'","departurePort",departurePort);
    filter(where,params,"r.data->>'destinationPort'","destinationPort",destinationPort);
    String from=" FROM t_packing_source_row r JOIN t_packing_import_batch b ON b.batch_id=r.batch_id";
    long total=db.queryForObject("SELECT count(*)"+from+where,params,Long.class);
    var rows=db.query("SELECT r.source_id,r.batch_id,r.data::text,b.file_name,b.created_at"+from+where+
        " ORDER BY r.batch_id DESC,r.source_row,r.source_id LIMIT :limit OFFSET :offset",params,(rs,n)->{
      try{
        ObjectNode row=(ObjectNode)json.readTree(rs.getString("data"));
        row.put("sourceId",rs.getLong("source_id"));row.put("batchId",rs.getLong("batch_id"));
        row.put("fileName",rs.getString("file_name"));row.put("importedAt",rs.getTimestamp("created_at").toInstant().toString());
        return row;
      }catch(JsonProcessingException e){throw new IllegalStateException(e);}
    });
    return Map.of("rows",rows,"total",total,"page",page,"size",size);
  }
  private static void filter(StringBuilder where,Map<String,Object> params,String column,String name,String value) {
    if(!value.isBlank()){where.append(" AND ").append(column).append(" ILIKE :").append(name);params.put(name,SearchText.contains(value.strip()));}
  }
}
