package jp.co.tradeplatform.document;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
  private final NamedParameterJdbcTemplate db;
  private final Path base;
  public DocumentController(NamedParameterJdbcTemplate db,@Value("${app.storage-path}") String storage){
    this.db=db;this.base=Paths.get(storage).toAbsolutePath().normalize();
  }
  @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String,Object> upload(@RequestParam(required=false) Long exportId,
      @RequestParam(required=false) Long orderId,@RequestParam String type,@RequestPart("file") MultipartFile file, java.security.Principal principal) throws IOException {
    if((exportId==null)==(orderId==null))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide exactly one exportId or orderId");
    long ownerId=exportId!=null?exportId:orderId;
    if(ownerId<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"関連IDは正の整数で指定してください");
    String ownerSql=exportId!=null?"SELECT count(*) FROM t_export WHERE export_id=:id":"SELECT count(*) FROM t_order WHERE order_id=:id";
    if(db.queryForObject(ownerSql,Map.of("id",ownerId),Integer.class)==0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"関連する輸出・販売レコードがありません");
    if(!Set.of("INVOICE","PACKING_LIST","ORDER","CONTRACT","OTHER").contains(type))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown document type");
    if(file.isEmpty() || file.getSize()>15*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PDF file size must be 1 byte to 15 MB");
    String name=Optional.ofNullable(file.getOriginalFilename()).orElse("document.pdf").replaceAll("[\\\\/\\r\\n]","_");
    if(name.length()>255)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ファイル名は255文字以内にしてください");
    if(!name.toLowerCase(Locale.ROOT).endsWith(".pdf"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PDFファイルのみ登録できます");
    byte[] content=file.getBytes();
    if(content.length<5 || content[0]!='%'||content[1]!='P'||content[2]!='D'||content[3]!='F'||content[4]!='-')
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid PDF signature");
    String key=UUID.randomUUID()+".pdf";
    Files.createDirectories(base);
    Path target=base.resolve(key);
    Files.write(target,content,StandardOpenOption.CREATE_NEW);
    try {
      Long id=db.queryForObject("INSERT INTO t_document(export_id,order_id,document_type,file_name,storage_key,mime_type,file_size_bytes,created_by) VALUES(:e,:o,:t,:n,:k,'application/pdf',:s,:u) RETURNING document_id",
          new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("e",exportId).addValue("o",orderId).addValue("t",type).addValue("n",name).addValue("k",key).addValue("s",content.length).addValue("u",principal.getName()),Long.class);
      return Map.of("documentId",id,"name",name);
    }catch(Exception ex){Files.deleteIfExists(target);throw ex;}
  }
  @GetMapping("/{id}/content") public ResponseEntity<byte[]> content(@PathVariable long id){return serve(id,false);}
  @GetMapping("/{id}/download") public ResponseEntity<byte[]> download(@PathVariable long id){return serve(id,true);}
  private ResponseEntity<byte[]> serve(long id,boolean attachment){
    var rows=db.queryForList("SELECT storage_key,file_name,mime_type FROM t_document WHERE document_id=:id",Map.of("id",id));
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found");
    var row=rows.get(0);String key=String.valueOf(row.get("storage_key"));
    if(!key.matches("[a-f0-9-]{36}\\.pdf"))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Invalid storage key");
    try{
      byte[] bytes=Files.readAllBytes(base.resolve(key));
      String name=String.valueOf(row.get("file_name"));
      var disp=attachment?ContentDisposition.attachment().filename(name,java.nio.charset.StandardCharsets.UTF_8).build():ContentDisposition.inline().filename(name,java.nio.charset.StandardCharsets.UTF_8).build();
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,disp.toString())
         .header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store").body(bytes);
    }catch(IOException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Stored file is missing");}
  }
}
