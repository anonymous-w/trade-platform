package jp.co.tradeplatform.importdata;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Simplified, audited first-version CSV importer: one export / one equipment per import row group.
    Multi-product and multi-document source imports require a separate mapping specification. */
@RestController
@RequestMapping("/api/imports")
public class CsvImportController {
  private final NamedParameterJdbcTemplate db;
  private final jp.co.tradeplatform.notification.NotificationService notifications;
  public CsvImportController(NamedParameterJdbcTemplate db,jp.co.tradeplatform.notification.NotificationService notifications){this.db=db;this.notifications=notifications;}
  private static final List<String> COLS=List.of("export_no","customer_code","customer_name","equipment_code","equipment_name","model_no","invoice_no","invoice_date","export_date","part_name","length_mm","width_mm","height_mm","gross_weight_kg","volume_m3","container_type","container_qty","record_basis");

  @PostMapping(value="/csv",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public Map<String,Object> importCsv(@RequestPart("file") MultipartFile file, java.security.Principal principal,jakarta.servlet.http.HttpServletRequest request){
    request.setAttribute("notification.fileName",file.getOriginalFilename());
    if(file.isEmpty()||file.getSize()>2*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CSV must be 1 byte to 2 MB");
    try(Reader reader=bomReader(file);
      CSVParser parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreSurroundingSpaces(true).get().parse(reader)){
      if(!parser.getHeaderNames().containsAll(COLS))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Missing CSV columns: "+COLS.stream().filter(c->!parser.getHeaderNames().contains(c)).toList());
      if(parser.getHeaderNames().size()!=new HashSet<>(parser.getHeaderNames()).size())
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CSVに重複した列名があります");
      List<CSVRecord> rows=parser.getRecords();
      if(rows.isEmpty()||rows.size()>300)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CSV supports 1-300 packing rows per upload");
      validateRows(rows);
      int created=0;Map<String,Entry> entries=new LinkedHashMap<>();
      for(CSVRecord row:rows){
        String exportNo=required(row,"export_no");
        Entry entry=entries.get(exportNo);
        if(entry==null){
          Long cid=existingId("SELECT customer_id FROM m_customer WHERE customer_code=:c",required(row,"customer_code"));
          if(cid!=null && db.queryForObject("SELECT count(*) FROM m_customer WHERE customer_id=:id AND customer_name=:n",Map.of("id",cid,"n",required(row,"customer_name")),Integer.class)==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"顧客コードと登録済み名称が一致しません: "+required(row,"customer_code"));
          if(cid==null) cid=db.queryForObject("INSERT INTO m_customer(customer_code,customer_name,country_code,address1) VALUES(:c,:n,'JP','Imported; verify address') RETURNING customer_id",new MapSqlParameterSource("c",required(row,"customer_code")).addValue("n",required(row,"customer_name")),Long.class);
          Long pid=existingId("SELECT product_id FROM m_product WHERE product_code=:c",required(row,"equipment_code"));
          if(pid!=null && db.queryForObject("SELECT count(*) FROM m_product WHERE product_id=:id AND product_name=:n AND model_no=:m",Map.of("id",pid,"n",required(row,"equipment_name"),"m",required(row,"model_no")),Integer.class)==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"商品コードと登録済み名称・型番が一致しません: "+required(row,"equipment_code"));
          if(pid==null)pid=db.queryForObject("INSERT INTO m_product(product_code,product_name,model_no,unit_code) VALUES(:c,:n,:m,'SET') RETURNING product_id",new MapSqlParameterSource("c",required(row,"equipment_code")).addValue("n",required(row,"equipment_name")).addValue("m",required(row,"model_no")),Long.class);
          if(existingId("SELECT export_id FROM t_export WHERE export_no=:c",exportNo)!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Export already exists: "+exportNo);
          LocalDate exportDate=LocalDate.parse(required(row,"export_date"));
          Long eid=db.queryForObject("INSERT INTO t_export(export_no,customer_id,dest_country_code,destination_name,shipper_name,export_date,export_status,created_by) VALUES(:n,:c,'JP','TO VERIFY','TO VERIFY',:d,'DRAFT',:u) RETURNING export_id",
            new MapSqlParameterSource("n",exportNo).addValue("c",cid).addValue("d",exportDate).addValue("u",principal.getName()),Long.class);
          Long iid=db.queryForObject("INSERT INTO t_export_item(export_id,line_no,product_id,equipment_name,model_no,quantity) VALUES(:e,1,:p,:n,:m,1) RETURNING export_item_id",
            new MapSqlParameterSource("e",eid).addValue("p",pid).addValue("n",required(row,"equipment_name")).addValue("m",required(row,"model_no")),Long.class);
          Long invoiceId=db.queryForObject("INSERT INTO t_invoice(export_id,invoice_no,invoice_date,currency_code,total_amount) VALUES(:e,:n,:d,'JPY',0) RETURNING invoice_id",
            new MapSqlParameterSource("e",eid).addValue("n",required(row,"invoice_no")).addValue("d",LocalDate.parse(required(row,"invoice_date"))),Long.class);
          Long plid=db.queryForObject("INSERT INTO t_packing_list_header(export_id,invoice_id,document_date) VALUES(:e,:i,:d) RETURNING packing_list_id",
            new MapSqlParameterSource("e",eid).addValue("i",invoiceId).addValue("d",exportDate),Long.class);
          int qty=Integer.parseInt(required(row,"container_qty"));
          String basis=required(row,"record_basis");
          if(qty<1||!Set.of("PLANNED","DOCUMENTED","ACTUAL").contains(basis))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid container fields");
          db.update("INSERT INTO t_export_container(export_id,container_type,container_qty,record_basis,source_text) VALUES(:e,:t,:q,:b,'Imported CSV; verify supporting evidence')",
            new MapSqlParameterSource("e",eid).addValue("t",required(row,"container_type")).addValue("q",qty).addValue("b",basis));
          entry=new Entry(eid,iid,plid,exportNo,required(row,"invoice_no"),required(row,"equipment_code"),required(row,"container_type"),qty,basis,0);
          entries.put(exportNo,entry);created++;
        }
        if(!entry.invoiceNo.equals(required(row,"invoice_no"))||!entry.equipmentCode.equals(required(row,"equipment_code"))||!entry.containerType.equals(required(row,"container_type"))||entry.qty!=Integer.parseInt(required(row,"container_qty"))||!entry.basis.equals(required(row,"record_basis")))
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Conflicting header values for export "+exportNo);
        entry.line++;
        db.update("INSERT INTO t_packing_list(export_id,packing_list_id,export_item_id,line_no,part_name,pcs,length_mm,width_mm,height_mm,gross_weight_kg,volume_m3) " +
         "VALUES(:e,:p,:i,:l,:n,1,:x,:y,:z,:w,:v)",
         new MapSqlParameterSource("e",entry.exportId).addValue("i",entry.itemId).addValue("p",entry.plId).addValue("l",entry.line).addValue("n",required(row,"part_name"))
          .addValue("x",decimal(row,"length_mm")).addValue("y",decimal(row,"width_mm")).addValue("z",decimal(row,"height_mm"))
          .addValue("w",decimal(row,"gross_weight_kg")).addValue("v",decimal(row,"volume_m3")));
      }
      notifications.record(principal.getName(),"csv-import:"+entries.values().iterator().next().exportId,jp.co.tradeplatform.notification.NotificationService.Type.DATA_IMPORT,
        jp.co.tradeplatform.notification.NotificationService.fileName(file.getOriginalFilename())+" の取込みが完了しました（輸出 "+created+" 件・包装明細 "+rows.size()+" 行）。");
      return Map.of("createdExports",created,"packingRows",rows.size(),"message","Imported as DRAFT: verify location, amounts and source evidence");
    }catch(ResponseStatusException e){throw e;}
    catch(org.springframework.dao.DataIntegrityViolationException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"番号が重複しているか、既存データと競合しています。CSV全体の登録を取り消しました");}
    catch(IOException|IllegalArgumentException|UncheckedIOException|java.time.DateTimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CSVの形式・日付・数値を確認してください。CSV全体の登録を取り消しました");}
  }
  private static Reader bomReader(MultipartFile file) throws IOException {
    PushbackReader reader=new PushbackReader(new InputStreamReader(file.getInputStream(),StandardCharsets.UTF_8.newDecoder()),1);
    int first=reader.read(); if(first!=-1 && first!=0xFEFF)reader.unread(first);
    return reader;
  }
  static void validateRows(List<CSVRecord> rows) {
    List<String> headers=List.of("customer_code","customer_name","equipment_code","equipment_name","model_no","invoice_no","invoice_date","export_date","container_type","container_qty","record_basis");
    Map<String,List<String>> groups=new HashMap<>();
    for(CSVRecord row:rows){
      if(!row.isConsistent())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CSVの列数が一致しません: 行 "+row.getRecordNumber());
      for(String col:COLS)required(row,col);
      LocalDate.parse(required(row,"export_date"));LocalDate.parse(required(row,"invoice_date"));
      int qty=Integer.parseInt(required(row,"container_qty"));
      if(qty<1 || !Set.of("PLANNED","DOCUMENTED","ACTUAL").contains(required(row,"record_basis")))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"コンテナ数量・記録根拠が不正です: 行 "+row.getRecordNumber());
      for(String col:List.of("length_mm","width_mm","height_mm","gross_weight_kg","volume_m3")) {
        BigDecimal value=decimal(row,col);boolean dimension=col.endsWith("_mm");int scale=dimension?2:3;
        if(value.signum()<0 || (dimension && value.signum()==0) || value.compareTo(BigDecimal.TEN.pow(12-scale))>=0 || Math.max(0,value.stripTrailingZeros().scale())>scale)
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"数値の範囲・小数桁を確認してください: "+col+" 行 "+row.getRecordNumber());
      }
      List<String> values=headers.stream().map(c->required(row,c)).toList();
      var previous=groups.putIfAbsent(required(row,"export_no"),values);
      if(previous!=null && !previous.equals(values))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"同じ輸出No.の顧客・設備・日付・コンテナ情報が一致しません: 行 "+row.getRecordNumber());
    }
  }
  private Long existingId(String sql,String code){var list=db.queryForList(sql,Map.of("c",code));return list.isEmpty()?null:((Number)list.get(0).values().iterator().next()).longValue();}
  private static String required(CSVRecord r,String name){String s=r.get(name).strip();if(s.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Empty column "+name+" at line "+r.getRecordNumber());int max=switch(name){case "export_no","customer_code","equipment_code"->50;case "invoice_no","container_type"->30;case "model_no"->100;case "customer_name","equipment_name","part_name"->200;default->50;};
    if(s.length()>max)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"文字数が上限を超えています: "+name+" 行 "+r.getRecordNumber());return s;}
  private static BigDecimal decimal(CSVRecord r,String name){try{return new BigDecimal(required(r,name));}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid decimal: "+name);}}
  private static class Entry {
    final Long exportId,itemId,plId;final String exportNo,invoiceNo,equipmentCode,containerType,basis;final int qty;int line;
    Entry(Long e,Long i,Long p,String n,String inv,String c,String t,int q,String b,int l){exportId=e;itemId=i;plId=p;exportNo=n;invoiceNo=inv;equipmentCode=c;containerType=t;qty=q;basis=b;line=l;}
  }
}
