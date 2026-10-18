package jp.co.tradeplatform.importdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.*;
import java.util.*;
import jp.co.tradeplatform.common.SearchText;
import org.springframework.http.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/imports/packing")
public class PackingImportController {
  private final NamedParameterJdbcTemplate db;
  private final ObjectMapper json;
  private final PackingWorkbookParser parser=new PackingWorkbookParser();
  public PackingImportController(NamedParameterJdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
  private record Upload(String name,String fileHash,String contentHash,PackingWorkbookParser.Parsed parsed) {}
  private Upload read(MultipartFile file) throws IOException {
    String name=Optional.ofNullable(file.getOriginalFilename()).orElse("").replaceAll("[\\\\/\\r\\n]","_");
    if(!name.toLowerCase(Locale.ROOT).endsWith(".xlsx") || name.length()>255 || file.getSize()>PackingWorkbookParser.MAX_BYTES)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"10MB以下の .xlsx ファイルを選択してください（ファイル名255文字まで）");
    byte[] bytes=file.getBytes();var parsed=parser.parse(bytes);
    return new Upload(name,hash(bytes),hash(json.writeValueAsBytes(parsed)),parsed);
  }
  private static String hash(byte[] bytes) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }
  private List<Map<String,Object>> duplicates(Upload upload) {
    return db.queryForList("SELECT batch_id,file_name,created_at FROM t_packing_import_batch WHERE file_sha256=:f OR content_sha256=:c",
      Map.of("f",upload.fileHash(),"c",upload.contentHash()));
  }
  @PostMapping(value="/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String,Object> preview(@RequestPart("file") MultipartFile file) throws IOException {
    Upload upload=read(file);var parsed=upload.parsed();Map<String,Object> result=new LinkedHashMap<>();
    result.put("fileName",upload.name());result.put("fileHash",upload.fileHash());result.put("sheetName",parsed.sheetName());
    result.put("rowCount",parsed.rows().size());result.put("contractCount",parsed.contractCount());result.put("reviewRows",parsed.reviewRows());
    result.put("rows",parsed.rows().stream().limit(100).toList());
    result.put("reviewSamples",parsed.rows().stream().filter(r->!r.warnings().isEmpty()).limit(100).toList());
    result.put("duplicates",duplicates(upload));return result;
  }
  @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional(rollbackFor=Exception.class)
  public Map<String,Object> save(@RequestPart("file") MultipartFile file,@RequestParam String previewHash,
      @RequestParam(defaultValue="false") boolean acknowledgeReview,Principal principal) throws IOException {
    Upload upload=read(file);var parsed=upload.parsed();
    if(!upload.fileHash().equals(previewHash))throw new ResponseStatusException(HttpStatus.CONFLICT,"ファイルがプレビュー時と異なります。再度プレビューしてください");
    if(!acknowledgeReview)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"原資料として保存することを確認してください");
    if(!duplicates(upload).isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"同じ内容のExcelは既に保存されています。既存の取込み履歴を確認してください");
    try {
      long id=db.queryForObject("""
        INSERT INTO t_packing_import_batch(file_name,file_sha256,content_sha256,sheet_name,parser_version,row_count,contract_count,review_rows,created_by)
        VALUES(:name,:file,:content,:sheet,'packing-summary-v1',:count,:contracts,:reviews,:user) RETURNING batch_id
        """,new MapSqlParameterSource("name",upload.name()).addValue("file",upload.fileHash()).addValue("content",upload.contentHash())
        .addValue("sheet",parsed.sheetName()).addValue("count",parsed.rows().size()).addValue("contracts",parsed.contractCount())
        .addValue("reviews",parsed.reviewRows()).addValue("user",principal.getName()),Long.class);
      List<MapSqlParameterSource> rows=new ArrayList<>();
      for(var row:parsed.rows())rows.add(new MapSqlParameterSource("batch",id).addValue("row",row.sourceRow()).addValue("contract",row.contractNo())
        .addValue("equipment",row.equipmentName()).addValue("model",row.modelNo()).addValue("part",row.partName())
        .addValue("review",!row.warnings().isEmpty()).addValue("data",json.writeValueAsString(row)));
      db.batchUpdate("""
        INSERT INTO t_packing_source_row(batch_id,source_row,contract_no,equipment_name,model_no,part_name,needs_review,data)
        VALUES(:batch,:row,:contract,:equipment,:model,:part,:review,CAST(:data AS jsonb))
        """,rows.toArray(new MapSqlParameterSource[0]));
      return Map.of("batchId",id,"rowCount",rows.size(),"contractCount",parsed.contractCount(),"reviewRows",parsed.reviewRows());
    }catch(org.springframework.dao.DataIntegrityViolationException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"同じファイルの重複またはデータの競合があります。今回の保存は全て取り消しました");}
  }
  @GetMapping("/batches")
  public List<Map<String,Object>> batches() {
    return db.queryForList("SELECT batch_id,file_name,sheet_name,row_count,contract_count,review_rows,created_by,created_at FROM t_packing_import_batch ORDER BY batch_id DESC LIMIT 50",Map.of());
  }
  @GetMapping("/batches/{id}/rows")
  public Map<String,Object> rows(@PathVariable long id,@RequestParam(defaultValue="") String keyword,
      @RequestParam(defaultValue="false") boolean reviewOnly,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    if(page<0 || size<1 || size>100 || keyword.length()>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"検索条件・ページを確認してください");
    if(db.queryForObject("SELECT count(*) FROM t_packing_import_batch WHERE batch_id=:id",Map.of("id",id),Integer.class)==0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"取込み履歴がありません");
    Map<String,Object> params=new HashMap<>(Map.of("id",id,"limit",size,"offset",(long)page*size,"q",SearchText.contains(keyword.strip())));
    String where=" WHERE batch_id=:id"+(reviewOnly?" AND needs_review":"")+(keyword.isBlank()?"":" AND (contract_no ILIKE :q OR equipment_name ILIKE :q OR model_no ILIKE :q OR part_name ILIKE :q)");
    long total=db.queryForObject("SELECT count(*) FROM t_packing_source_row"+where,params,Long.class);
    var rows=db.query("SELECT data::text FROM t_packing_source_row"+where+" ORDER BY source_row LIMIT :limit OFFSET :offset",params,(rs,n)->{
      try{return json.readTree(rs.getString(1));}catch(JsonProcessingException e){throw new IllegalStateException(e);}
    });
    return Map.of("rows",rows,"total",total,"page",page,"size",size);
  }
}
