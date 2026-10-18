package jp.co.tradeplatform.deadline;

import java.io.*;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Template-aware block parser. Never evaluates formulas or infers annual completion/status. */
public class DeadlineParser {
  public static final int MAX_BYTES=10*1024*1024;
  public record Year(int year,LocalDate dueDate,LocalDate plannedDate,LocalDate completedDate,String rawText){}
  public record Entry(int sourceRow,int endRow,String contractNo,String productName,String modelNo,
      String exportPermitNo,String servicePermitNo,LocalDate permitDate,LocalDate validUntil,
      LocalDate installationDate,LocalDate reportDueDate,LocalDate blDate,String personInCharge,
      String responsiblePerson,String conditions,List<Year> years,List<String> warnings,List<String> errors,
      Map<String,String> rawCells,Map<String,String> formulas,List<String> merges){}
  public record Parsed(String sheetName,List<Entry> records){}
  private static final String[] HEADERS={"契約番号","輸出許可番号","役務許可番号","商品名","型番","経済産業省輸出許可日","有効期限","貨物輸入後設置日","設置完了報告提出期限","貨物B/L日付","一年目","二年目","三年目","四年目","五年目","担当者","責任者","条件内容"};
  static String norm(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).replaceAll("[\\s\\u3000]+","");}
  static String text(Cell c){
    if(c==null)return "";
    CellType type=c.getCellType()==CellType.FORMULA?c.getCachedFormulaResultType():c.getCellType();
    return switch(type){case STRING->c.getStringCellValue().strip();case NUMERIC->DateUtil.isCellDateFormatted(c)?c.getLocalDateTimeCellValue().toLocalDate().toString():new DataFormatter(Locale.JAPAN).formatCellValue(c);case BOOLEAN->String.valueOf(c.getBooleanCellValue());case ERROR->"#ERROR";default->"";};
  }
  static Cell cell(Sheet s,int row,int col){Row r=s.getRow(row);return r==null?null:r.getCell(col);}
  static String value(Sheet s,int row,int col){return text(cell(s,row,col));}
  static Cell inherited(Sheet s,int row,int col){
    Cell own=cell(s,row,col);if(own!=null&&!text(own).isBlank())return own;
    for(CellRangeAddress area:s.getMergedRegions())if(area.isInRange(row,col))return cell(s,area.getFirstRow(),area.getFirstColumn());
    return own;
  }
  static String inheritedValue(Sheet s,int row,int col){return text(inherited(s,row,col));}
  static boolean present(String s){return !s.isBlank()&&!Set.of("-","－","—","―").contains(s.strip());}
  public static LocalDate date(String raw){
    String s=norm(raw);
    if(!present(s))return null;
    Matcher m=Pattern.compile("^(\\d{4})[年/.-](\\d{1,2})(?:月末日|/末|末日)$").matcher(s);
    try{
      if(m.matches())return YearMonth.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))).atEndOfMonth();
      m=Pattern.compile("^(\\d{4})[年/.-](\\d{1,2})[月/.-](\\d{1,2})日?$").matcher(s);
      if(m.matches())return LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));
    }catch(DateTimeException ignored){}
    return null;
  }
  static LocalDate scalar(Sheet s,int row,int col,String label,List<String>warnings){
    String raw=inheritedValue(s,row,col);Cell c=inherited(s,row,col);
    if(c!=null && c.getCellType()==CellType.FORMULA){warnings.add(label+"：数式の保存値。要確認");return null;}
    LocalDate result=date(raw);if(present(raw)&&result==null)warnings.add(label+"：日付を特定できません。原文を確認してください");return result;
  }
  public Parsed parse(byte[] bytes){
    if(bytes.length==0||bytes.length>MAX_BYTES)throw bad("Excelは空でない10MB以下の .xlsx / .xls を選択してください");
    try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
      Sheet sheet=book.getSheet("期限管理表");if(sheet==null)throw bad("「期限管理表」シートが見つかりません");
      if(sheet.getLastRowNum()>10000||sheet.getNumMergedRegions()>5000)throw bad("期限管理表は10000行・結合5000箇所以内で指定してください");
      int header=-1;Map<String,Integer> cols=new LinkedHashMap<>();
      for(int row=0;row<Math.min(30,sheet.getLastRowNum()+1);row++){
        Map<String,Integer> found=new LinkedHashMap<>();Row rr=sheet.getRow(row);if(rr==null)continue;
        for(int col=0;col<Math.min(100,rr.getLastCellNum());col++){
          String label=norm(value(sheet,row,col));
          for(String h:HEADERS)if(label.equals(h)||(h.equals("貨物輸入後設置日")&&label.startsWith(h))){if(found.put(h,col)!=null)throw bad("表頭に重複した項目があります："+h);}
        }
        if(found.size()==HEADERS.length){header=row;cols=found;break;}
      }
      if(header<0)throw bad("期限管理表の必須表頭を確認してください（契約番号・許可・商品・一年目〜五年目等）");
      int contractCol=cols.get("契約番号");List<Integer> starts=new ArrayList<>();
      for(int row=header+1;row<=sheet.getLastRowNum();row++)if(present(value(sheet,row,contractCol))||present(value(sheet,row,cols.get("商品名")))||present(value(sheet,row,cols.get("型番"))))starts.add(row);
      if(starts.isEmpty())throw bad("取込み対象の契約がありません");
      if(starts.size()>2000)throw bad("一度に取込める契約ブロックは2000件までです");
      List<Entry> entries=new ArrayList<>();
      for(int index=0;index<starts.size();index++){
        int start=starts.get(index),end=(index+1<starts.size()?starts.get(index+1)-1:sheet.getLastRowNum());
        // No. merged range defines the block. Empty template blocks below are not data.
        for(CellRangeAddress a:sheet.getMergedRegions())if(a.getFirstRow()==start&&a.getFirstColumn()<contractCol&&a.getLastColumn()<contractCol){end=Math.min(end,a.getLastRow());break;}
        List<String>warnings=new ArrayList<>(),errors=new ArrayList<>();Map<String,String>raw=new LinkedHashMap<>(),formulas=new LinkedHashMap<>();List<String>merges=new ArrayList<>();
        for(int row=start;row<=end;row++)for(int col:cols.values()){
          Cell c=cell(sheet,row,col);String v=text(c);if(!v.isBlank())raw.put(new org.apache.poi.ss.util.CellReference(row,col).formatAsString(),v);
          if(c!=null&&c.getCellType()==CellType.FORMULA)formulas.put(c.getAddress().formatAsString(),c.getCellFormula());
        }
        for(CellRangeAddress a:sheet.getMergedRegions())if(a.getFirstRow()<=end&&a.getLastRow()>=start)merges.add(a.formatAsString());
        if(!formulas.isEmpty())warnings.add("数式は再計算しません。保存値・元ファイルを確認してください");
        String contract=inheritedValue(sheet,start,contractCol),product=inheritedValue(sheet,start,cols.get("商品名")),model=inheritedValue(sheet,start,cols.get("型番"));
        String export=inheritedValue(sheet,start,cols.get("輸出許可番号")),service=inheritedValue(sheet,start,cols.get("役務許可番号"));
        if(!present(contract))errors.add("契約番号が空欄で、結合セルからも確認できません");
        if(!present(product)&&!present(model))errors.add("商品名と型番が両方空欄です");
        if(!present(export)&&!present(service))warnings.add("許可番号が空欄です。別案件との照合に注意してください");
        for(String key:List.of("契約番号","商品名","型番","輸出許可番号","役務許可番号"))if(cell(sheet,start,cols.get(key))!=null&&cell(sheet,start,cols.get(key)).getCellType()==CellType.FORMULA)errors.add("照合項目に数式があります："+key);
        List<Year>years=new ArrayList<>();
        for(int y=1;y<=5;y++){
          int col=cols.get(HEADERS[9+y]);String base=inheritedValue(sheet,start,col);List<String>lines=new ArrayList<>();LocalDate due=date(base),planned=null,completed=null;Set<LocalDate> planDates=new HashSet<>(),completeDates=new HashSet<>();
          if(inherited(sheet,start,col)!=null&&inherited(sheet,start,col).getCellType()==CellType.FORMULA)due=null;
          if(present(base)&&due==null)warnings.add(y+"年目：期限を特定できません");
          for(int row=start;row<=end;row++){
            String v=value(sheet,row,col);if(!present(v))continue;lines.add("Excel "+(row+1)+"行："+v);
            if(cell(sheet,row,col)!=null&&cell(sheet,row,col).getCellType()==CellType.FORMULA)continue;
            String n=norm(v);String label=n.startsWith("確認完了予定日:")?"確認完了予定日:":n.startsWith("確認完了日:")?"確認完了日:":"";
            if(!label.isEmpty()){
              String tail=n.substring(label.length());LocalDate d=date(tail);
              if(!tail.isBlank()&&d==null)warnings.add(y+"年目：確認日を特定できません");
              if(d!=null){if(label.equals("確認完了予定日:"))planDates.add(d);else completeDates.add(d);}
              if(label.equals("確認完了予定日:")){if(planned!=null&&!planned.equals(d))warnings.add(y+"年目：予定日が複数あります。原文を確認してください");else planned=d;}
              else {if(completed!=null&&!completed.equals(d))warnings.add(y+"年目：完了日が複数あります。原文を確認してください");else completed=d;}
            }else if(row>start)warnings.add(y+"年目：補足行に日付・記載あり。計画／完了とは判定していません");
          }
          planned=planDates.size()==1?planDates.iterator().next():null;completed=completeDates.size()==1?completeDates.iterator().next():null;
          if(planned!=null&&due!=null&&Math.abs(java.time.temporal.ChronoUnit.DAYS.between(planned,due))>180)warnings.add(y+"年目：予定日と記載期限が半年以上離れています");
          years.add(new Year(y,due,planned,completed,String.join("\n",lines)));
        }
        String conditions=inheritedValue(sheet,start,cols.get("条件内容"));Sheet cs=book.getSheet("条件内容");
        if(cs!=null&&cs.getLastRowNum()<=10000){List<String>matches=new ArrayList<>();for(Row row:cs){if(value(cs,row.getRowNum(),1).equals(contract)&&value(cs,row.getRowNum(),2).equals(export)&&present(value(cs,row.getRowNum(),3)))matches.add(value(cs,row.getRowNum(),3));}if(matches.size()==1)conditions=matches.get(0);else if(present(conditions))warnings.add("条件内容の対応を一意に確認できません。参照原文を保持しました");}
        for(int row=start+1;row<=end;row++)if(present(value(sheet,row,cols.get("輸出許可番号"))))warnings.add("許可欄の補足行あり（相談番号等）。新しい許可として自動登録しません");
        entries.add(new Entry(start+1,end+1,contract,product,model,export,service,
          scalar(sheet,start,cols.get("経済産業省輸出許可日"),"許可日",warnings),scalar(sheet,start,cols.get("有効期限"),"有効期限",warnings),
          scalar(sheet,start,cols.get("貨物輸入後設置日"),"設置日",warnings),scalar(sheet,start,cols.get("設置完了報告提出期限"),"報告期限",warnings),scalar(sheet,start,cols.get("貨物B/L日付"),"B/L日",warnings),
          inheritedValue(sheet,start,cols.get("担当者")),inheritedValue(sheet,start,cols.get("責任者")),conditions,years,warnings,errors,raw,formulas,merges));
      }
      return new Parsed(sheet.getSheetName(),entries);
    }catch(ResponseStatusException e){throw e;}catch(Exception e){throw bad("Excelを読み取れません。形式、暗号化、破損やデータ量を確認してください");}
  }
  static ResponseStatusException bad(String text){return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);}
}
