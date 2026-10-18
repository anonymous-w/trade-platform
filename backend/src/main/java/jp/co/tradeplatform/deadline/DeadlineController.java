package jp.co.tradeplatform.deadline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.*;
import java.time.LocalDate;
import java.util.*;
import jp.co.tradeplatform.common.SearchText;
import jp.co.tradeplatform.notification.NotificationService;
import org.springframework.http.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DeadlineController {
  private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final NotificationService notifications;
  public DeadlineController(NamedParameterJdbcTemplate db,ObjectMapper json,NotificationService notifications){this.db=db;this.json=json;this.notifications=notifications;}
  private record Upload(String name,String hash,String type,byte[] bytes,DeadlineParser.Parsed parsed){}
  private record Candidate(DeadlineParser.Entry data,String key,Long id,long version,boolean conflict){}
  private Upload upload(MultipartFile file,jakarta.servlet.http.HttpServletRequest request)throws Exception{
    request.setAttribute("notification.fileName",file.getOriginalFilename());String name=NotificationService.fileName(file.getOriginalFilename());
    String type=name.toLowerCase(Locale.ROOT).endsWith(".xlsx")?"xlsx":name.toLowerCase(Locale.ROOT).endsWith(".xls")?"xls":"";
    if(type.isBlank()||file.getSize()>DeadlineParser.MAX_BYTES)throw DeadlineParser.bad("10MB以下の .xlsx / .xls を選択してください");
    byte[] bytes=file.getBytes();return new Upload(name,hash(bytes),type,bytes,new DeadlineParser().parse(bytes));
  }
  private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  private String key(DeadlineParser.Entry e)throws Exception{return hash(json.writeValueAsBytes(List.of(e.contractNo().strip(),e.productName().strip(),e.modelNo().strip(),e.exportPermitNo().strip(),e.servicePermitNo().strip())));}
  private List<Candidate> candidates(Upload upload)throws Exception{
    List<Candidate> result=new ArrayList<>();Map<String,Integer> counts=new HashMap<>();for(var e:upload.parsed().records())counts.merge(key(e),1,Integer::sum);
    for(var e:upload.parsed().records()){
      String key=key(e);var found=db.queryForList("SELECT permit_id,version FROM t_deadline_permit WHERE import_key=:key",Map.of("key",key));
      result.add(new Candidate(e,key,found.isEmpty()?null:((Number)found.get(0).get("permit_id")).longValue(),found.isEmpty()?0:((Number)found.get(0).get("version")).longValue(),counts.get(key)>1));
    }return result;
  }
  private String token(Upload u,List<Candidate> candidates){return hash((u.hash()+candidates.stream().map(c->c.key()+":"+c.id()+":"+c.version()).toList()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  @PostMapping(value="/api/imports/deadlines/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String,Object> preview(@RequestPart("file")MultipartFile file,Principal user,jakarta.servlet.http.HttpServletRequest request)throws Exception{
    Upload u=upload(file,request);var candidates=candidates(u);var duplicates=db.queryForList("SELECT import_id FROM t_deadline_import WHERE file_sha256=:hash",Map.of("hash",u.hash()));
    List<Map<String,Object>> rows=new ArrayList<>();int added=0,existing=0,errors=0;
    for(var c:candidates){boolean invalid=c.conflict()||!c.data().errors().isEmpty();if(invalid)errors++;else if(c.id()==null)added++;else existing++;
      Map<String,Object> row=new LinkedHashMap<>();row.put("data",c.data());row.put("existingId",c.id());row.put("version",c.version());row.put("invalid",invalid);row.put("conflict",c.conflict());row.put("action",invalid||c.id()!=null?"SKIP":"ADD");rows.add(row);}
    notifications.record(user.getName(),"deadline-upload:"+u.hash(),NotificationService.Type.FILE_UPLOAD,u.name()+" の期限管理表をプレビューしました（未保存）。");
    return Map.of("fileName",u.name(),"sheetName",u.parsed().sheetName(),"previewToken",token(u,candidates),"rows",rows,"recordCount",rows.size(),"newCount",added,"existingCount",existing,"errorCount",errors,"alreadyImported",!duplicates.isEmpty());
  }
  @PostMapping(value="/api/imports/deadlines",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional(rollbackFor=Exception.class)
  public Map<String,Object> save(@RequestPart("file")MultipartFile file,@RequestParam String previewToken,@RequestParam String decisions,
      @RequestParam(defaultValue="false")boolean acknowledgeReview,Principal user,jakarta.servlet.http.HttpServletRequest request)throws Exception{
    if(!acknowledgeReview)throw DeadlineParser.bad("原文・要確認事項と取込方法を確認してください");
    Upload u=upload(file,request);
    // Serializes import decisions; protects the same logical record across concurrent files.
    db.query("SELECT pg_advisory_xact_lock(47182591)",Map.of(),rs->{});
    Map<String,String> choices;try{choices=json.readValue(decisions,new TypeReference<Map<String,String>>(){});}catch(Exception e){throw DeadlineParser.bad("取込方法を確認してください");}
    String submission=hash((u.hash()+":"+previewToken+":"+json.writeValueAsString(new TreeMap<>(choices))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var prior=db.queryForList("SELECT import_id,added_count+updated_count+skipped_count+failed_count AS count FROM t_deadline_import WHERE submission_key=:key",Map.of("key",submission));
    if(!prior.isEmpty())return Map.of("importId",prior.get(0).get("import_id"),"added",0,"updated",0,"skipped",prior.get(0).get("count"),"failed",0,"duplicate",true);
    var candidates=candidates(u);if(!token(u,candidates).equals(previewToken))throw new ResponseStatusException(HttpStatus.CONFLICT,"ファイルまたは登録済みデータが変わりました。再度プレビューしてください");

    if(choices.size()!=candidates.size())throw DeadlineParser.bad("全件の取込方法を選択してください");
    int added=0,updated=0,skipped=0,failed=0;List<MapSqlParameterSource> logs=new ArrayList<>();
    for(var c:candidates){var e=c.data();String action=choices.get(String.valueOf(e.sourceRow()));if(!Set.of("ADD","UPDATE","SKIP").contains(action==null?"":action))throw DeadlineParser.bad("取込方法が不正です");
      Long id=c.id();String source=json.writeValueAsString(e);
      if(c.conflict()||!e.errors().isEmpty()){if(!action.equals("SKIP"))throw DeadlineParser.bad("エラー行は登録できません");action="ERROR";failed++;}
      else if(action.equals("SKIP"))skipped++;
      else {
        if(action.equals("ADD")&&id!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"既存記録は追加できません。更新またはスキップを選択してください");
        if(action.equals("UPDATE")&&id==null)throw DeadlineParser.bad("新規記録は更新できません");
        MapSqlParameterSource p=new MapSqlParameterSource("key",c.key()).addValue("export",e.exportPermitNo()).addValue("service",e.servicePermitNo()).addValue("permitDate",e.permitDate()).addValue("expiry",e.validUntil())
          .addValue("installation",e.installationDate()).addValue("report",e.reportDueDate()).addValue("bl",e.blDate()).addValue("person",e.personInCharge()).addValue("responsible",e.responsiblePerson()).addValue("conditions",e.conditions()).addValue("data",source).addValue("user",user.getName());
        if(id==null){
          // Reuse a uniquely matching contract/equipment tuple, never the contract number alone.
          var equipmentMatches=db.queryForList("SELECT e.equipment_id FROM t_deadline_equipment e JOIN t_deadline_contract c ON c.contract_id=e.contract_id WHERE c.contract_no=:c AND e.product_name=:p AND e.model_no=:m",Map.of("c",e.contractNo(),"p",e.productName(),"m",e.modelNo()));
          if(equipmentMatches.size()>1)throw new ResponseStatusException(HttpStatus.CONFLICT,"契約・設備の対応が複数あります。自動結合できません");
          long equipment;
          if(equipmentMatches.size()==1)equipment=((Number)equipmentMatches.get(0).get("equipment_id")).longValue();
          else {
            long contract=db.queryForObject("INSERT INTO t_deadline_contract(contract_no) VALUES(:no) RETURNING contract_id",Map.of("no",e.contractNo()),Long.class);
            equipment=db.queryForObject("INSERT INTO t_deadline_equipment(contract_id,product_name,model_no) VALUES(:c,:p,:m) RETURNING equipment_id",Map.of("c",contract,"p",e.productName(),"m",e.modelNo()),Long.class);
          }
          p.addValue("equipment",equipment);
          id=db.queryForObject("""
            INSERT INTO t_deadline_permit(equipment_id,import_key,export_permit_no,service_permit_no,permit_date,valid_until,installation_date,report_due_date,bl_date,person_in_charge,responsible_person,conditions_text,source_data,created_by,updated_by)
            VALUES(:equipment,:key,:export,:service,:permitDate,:expiry,:installation,:report,:bl,:person,:responsible,:conditions,CAST(:data AS jsonb),:user,:user) RETURNING permit_id
            """,p,Long.class);added++;
        }else{
          p.addValue("id",id).addValue("version",c.version());
          int n=db.update("""
            UPDATE t_deadline_permit SET permit_date=:permitDate,valid_until=:expiry,installation_date=:installation,report_due_date=:report,bl_date=:bl,
            person_in_charge=:person,responsible_person=:responsible,conditions_text=:conditions,source_data=CAST(:data AS jsonb),version=version+1,updated_by=:user,updated_at=CURRENT_TIMESTAMP
            WHERE permit_id=:id AND version=:version
            """,p);if(n!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"登録済みデータが変更されました。再プレビューしてください");updated++;
        }
        for(var year:e.years())db.update("""
          INSERT INTO t_deadline_year(permit_id,year_no,source_due_date,source_planned_date,source_completed_date,source_text)
          VALUES(:id,:year,:due,:planned,:completed,:raw) ON CONFLICT(permit_id,year_no) DO UPDATE SET
          source_due_date=EXCLUDED.source_due_date,source_planned_date=EXCLUDED.source_planned_date,source_completed_date=EXCLUDED.source_completed_date,source_text=EXCLUDED.source_text
          """,new MapSqlParameterSource("id",id).addValue("year",year.year()).addValue("due",year.dueDate()).addValue("planned",year.plannedDate()).addValue("completed",year.completedDate()).addValue("raw",year.rawText()));
      }
      logs.add(new MapSqlParameterSource("row",e.sourceRow()).addValue("id",id).addValue("action",action).addValue("data",source));
    }
    long batch=db.queryForObject("""
      INSERT INTO t_deadline_import(file_name,file_sha256,submission_key,file_content,file_type,sheet_name,parser_version,added_count,updated_count,skipped_count,failed_count,created_by)
      VALUES(:name,:hash,:submission,:content,:type,:sheet,'deadline-v1',:added,:updated,:skipped,:failed,:user) RETURNING import_id
      """,new MapSqlParameterSource("name",u.name()).addValue("hash",u.hash()).addValue("submission",submission).addValue("content",u.bytes()).addValue("type",u.type()).addValue("sheet",u.parsed().sheetName()).addValue("added",added).addValue("updated",updated).addValue("skipped",skipped).addValue("failed",failed).addValue("user",user.getName()),Long.class);
    for(var log:logs){log.addValue("batch",batch);db.update("INSERT INTO t_deadline_import_row(import_id,source_row,permit_id,action,source_data) VALUES(:batch,:row,:id,:action,CAST(:data AS jsonb))",log);}
    notifications.record(user.getName(),"deadline-import:"+batch,NotificationService.Type.DATA_IMPORT,u.name()+"：追加 "+added+"、更新 "+updated+"、スキップ "+skipped+"、エラー "+failed+" 件。");
    return Map.of("importId",batch,"added",added,"updated",updated,"skipped",skipped,"failed",failed,"duplicate",false);
  }
  @GetMapping("/api/imports/deadlines/history")
  public Map<String,Object> history(@RequestParam(defaultValue="0")int page){if(page<0)throw DeadlineParser.bad("ページが不正です");
    return Map.of("rows",db.queryForList("SELECT import_id,file_name,sheet_name,added_count,updated_count,skipped_count,failed_count,created_by,created_at FROM t_deadline_import ORDER BY import_id DESC LIMIT 20 OFFSET :offset",Map.of("offset",(long)page*20)),"total",db.queryForObject("SELECT count(*) FROM t_deadline_import",Map.of(),Long.class));}
  @GetMapping("/api/imports/deadlines/{id}/rows")
  public List<Map<String,Object>> importRows(@PathVariable long id){return db.queryForList("SELECT source_row,permit_id,action,source_data::text FROM t_deadline_import_row WHERE import_id=:id ORDER BY source_row",Map.of("id",id));}
  @GetMapping("/api/imports/deadlines/{id}/file")
  public ResponseEntity<byte[]> original(@PathVariable long id){
    var rows=db.queryForList("SELECT file_name,file_content,file_type FROM t_deadline_import WHERE import_id=:id",Map.of("id",id));if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"元ファイルがありません");var row=rows.get(0);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store")
      .header("Content-Disposition",ContentDisposition.attachment().filename((String)row.get("file_name"),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body((byte[])row.get("file_content"));
  }
  private static final String FROM=" FROM t_deadline_permit p JOIN t_deadline_equipment e ON e.equipment_id=p.equipment_id JOIN t_deadline_contract c ON c.contract_id=e.contract_id";
  @GetMapping("/api/deadlines")
  public Map<String,Object> list(@RequestParam(defaultValue="")String contract,@RequestParam(defaultValue="")String product,@RequestParam(defaultValue="")String model,
      @RequestParam(defaultValue="")String permit,@RequestParam(defaultValue="")String person,@RequestParam(defaultValue="permitDate")String dateType,
      @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) @RequestParam(required=false)LocalDate dateFrom,@org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) @RequestParam(required=false)LocalDate dateTo,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){
    if(page<0||size<1||size>100||List.of(contract,product,model,permit,person).stream().anyMatch(s->s.length()>200))throw DeadlineParser.bad("検索条件を確認してください");
    if(dateFrom!=null&&dateTo!=null&&dateFrom.isAfter(dateTo))throw DeadlineParser.bad("開始日は終了日以前にしてください");
    String dateColumn=switch(dateType){case "permitDate"->"p.permit_date";case "validUntil"->"p.valid_until";case "blDate"->"p.bl_date";default->throw DeadlineParser.bad("日付種類が不正です");};
    StringBuilder where=new StringBuilder(" WHERE 1=1");Map<String,Object>args=new HashMap<>(Map.of("limit",size,"offset",(long)page*size));
    filter(where,args,"c.contract_no","contract",contract);filter(where,args,"e.product_name","product",product);filter(where,args,"e.model_no","model",model);filter(where,args,"p.export_permit_no","permit",permit);filter(where,args,"p.person_in_charge","person",person);
    if(dateFrom!=null){where.append(" AND "+dateColumn+">=:from");args.put("from",dateFrom);}if(dateTo!=null){where.append(" AND "+dateColumn+"<=:to");args.put("to",dateTo);}
    long total=db.queryForObject("SELECT count(*)"+FROM+where,args,Long.class);
    return Map.of("rows",db.queryForList("SELECT p.permit_id,c.contract_id,e.equipment_id,c.contract_no,e.product_name,e.model_no,p.export_permit_no,p.permit_date,p.valid_until,p.person_in_charge,'要確認' AS monitor_status,NULL::date AS next_check_date"+FROM+where+" ORDER BY p.permit_id DESC LIMIT :limit OFFSET :offset",args),"total",total,"page",page,"size",size);
  }
  private static void filter(StringBuilder w,Map<String,Object>a,String col,String key,String value){if(!value.isBlank()){w.append(" AND "+col+" ILIKE :"+key);a.put(key,SearchText.contains(value.strip()));}}
  @GetMapping("/api/deadlines/{id}")
  public Map<String,Object> detail(@PathVariable long id)throws Exception{
    var rows=db.queryForList("SELECT p.*,c.contract_id,c.contract_no,e.product_name,e.model_no"+FROM+" WHERE p.permit_id=:id",Map.of("id",id));if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"記録が見つかりません");var data=rows.get(0);data.put("source_data",json.readTree(data.get("source_data").toString()));data.remove("import_key");
    return Map.of("record",data,"years",db.queryForList("SELECT year_no,source_due_date,source_planned_date,source_completed_date,source_text,manual_planned_date,manual_completed_date,manual_status,manual_notes FROM t_deadline_year WHERE permit_id=:id ORDER BY year_no",Map.of("id",id)),
      "attachments",db.queryForList("SELECT DISTINCT b.import_id,b.file_name,b.created_at FROM t_deadline_import b JOIN t_deadline_import_row r ON r.import_id=b.import_id WHERE r.permit_id=:id ORDER BY b.import_id DESC",Map.of("id",id)));
  }
}
