package jp.co.tradeplatform.importdata;

import java.io.*;
import java.math.*;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reads this packing-summary template as source material, never as a completed export. */
public final class PackingWorkbookParser {
  public static final int MAX_BYTES=10*1024*1024, MAX_ROWS=10000;
  public record SourceCell(String value,String formula,String mergedRange) {}
  public record SourceRow(int sourceRow,String contractNo,String equipmentName,String modelNo,
      String partName,String partCode,BigDecimal quantity,String quantityUnit,BigDecimal widthMm,
      BigDecimal depthMm,BigDecimal heightMm,BigDecimal weightKg,BigDecimal volumeM3,
      String containerText,String departurePort,String destinationPort,List<String> warnings,
      Map<String,SourceCell> rawCells) {}
  public record Parsed(String sheetName,List<SourceRow> rows,int contractCount,int reviewRows) {}

  public Parsed parse(byte[] bytes) {
    if(bytes.length==0 || bytes.length>MAX_BYTES)throw bad("Excelは空でない10MB以下の .xlsx を選択してください");
    checkZip(bytes);
    try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      // Do not evaluate formulas or resolve external workbooks. Preserve Excel's saved results.
      if(book.getNumberOfSheets()!=1)throw bad("対象シートだけを含むExcelを選択してください（複数シートは未対応）");
      XSSFSheet sheet=book.getSheetAt(0);
      if(sheet.getLastRowNum()>=MAX_ROWS+4 || sheet.getNumMergedRegions()>30000)throw bad("Excelは明細10000行までです");
      Map<String,CellRangeAddress> merged=new HashMap<>();
      for(CellRangeAddress area:sheet.getMergedRegions()) {
        if(area.getLastRow()>MAX_ROWS+4 || area.getLastColumn()>15)throw bad("対応範囲外の結合セルがあります（B:P列・明細10000行まで）");
        for(int r=area.getFirstRow();r<=area.getLastRow();r++)for(int c=area.getFirstColumn();c<=area.getLastColumn();c++) {
          if(merged.put(key(r,c),area)!=null)throw bad("結合セル範囲が重複しています");
        }
      }
      Set<Integer> shiftedRows=new HashSet<>();
      for(CellRangeAddress area:sheet.getMergedRegions())if(area.getFirstColumn()==3 && area.getLastColumn()==4) {
        CellRangeAddress contractArea=merged.get(key(area.getFirstRow(),1));
        int first=contractArea==null?area.getFirstRow():contractArea.getFirstRow();
        int last=contractArea==null?area.getLastRow():contractArea.getLastRow();
        for(int r=first;r<=last;r++)shiftedRows.add(r);
      }
      Reader read=new Reader(sheet,merged);
      if(!norm(read.value(2,1)).contains("契約番号") || !norm(read.value(2,3)).contains("設備名称")
          || !norm(read.value(3,5)).contains("分解部名称") || !norm(read.value(3,7)).equals("PCS")
          || !norm(read.value(2,8)).contains("外寸MM") || !norm(read.value(3,11)).equals("KG")
          || !norm(read.value(3,8)).equals("(W)") || !norm(read.value(3,9)).equals("(D)") || !norm(read.value(3,10)).equals("(H)")
          || !norm(read.value(2,13)).contains("コンテナ") || !norm(read.value(2,14)).contains("出発港") || !norm(read.value(2,15)).contains("目的港"))
        throw bad("パッキングリスト集計の列構成が一致しません。3・4行目の見出しとB:P列を確認してください");
      List<SourceRow> rows=new ArrayList<>(); Set<String> contracts=new HashSet<>();
      for(int r=4;r<=sheet.getLastRowNum();r++) {
        Row physical=sheet.getRow(r); if(physical==null)continue;
        boolean hasData=false;
        for(Cell cell:physical) {
          String value=read.value(r,cell.getColumnIndex());
          if(!value.isBlank()) {
            if(cell.getColumnIndex()<1 || cell.getColumnIndex()>15)throw bad("行 "+(r+1)+": B:P列の外にデータがあります");
            hasData=true;
          }
        }
        if(!hasData)continue;
        List<String> warnings=new ArrayList<>(); Map<String,SourceCell> raw=new LinkedHashMap<>();
        for(int c=1;c<=15;c++) {
          String value=read.value(r,c), formula=read.formula(r,c);
          CellRangeAddress area=merged.get(key(r,c));
          if(!value.isEmpty() || !formula.isEmpty() || area!=null)
            raw.put(Character.toString('A'+c),new SourceCell(value,formula,area==null?"":area.formatAsString()));
          if(formula.contains("["))warnings.add(Character.toString('A'+c)+(r+1)+": 外部参照の保存値です。参照元を確認してください");
        }
        String contract=read.text(r,1,warnings), equipment=read.text(r,3,warnings);
        boolean shifted=shiftedRows.contains(r);
        String model=read.text(r,shifted?5:4,warnings), part=read.text(r,shifted?6:5,warnings);
        CellRangeAddress codeArea=merged.get(key(r,6));
        String code=shifted || (codeArea!=null && codeArea.getFirstColumn()!=6)?"":read.text(r,6,warnings);
        if(shifted)warnings.add("設備名がD:E結合の形式：F列を型番、G列を分解部名称として読込み");
        if(contract.isEmpty())warnings.add("契約番号が未記入です"); else contracts.add(contract);
        if(equipment.isEmpty())warnings.add("設備名称が未記入・未確定です（空欄を前行から推測していません）");
        if(model.isEmpty())warnings.add("型番が未記入・未確定です");
        if(part.isEmpty())warnings.add("分解部名称が未記入です");
        String qtyText=read.value(r,7); String unit="PCS";
        if(!shifted && norm(code).matches("[1-9][0-9]*") && norm(qtyText).matches("[A-Z]+-?[0-9]+")) {
          String savedCode=qtyText; qtyText=code; code=savedCode;
          warnings.add("G/H列の数量と包装番号を入替えて読込みました。元資料を確認してください");
        }
        if(norm(qtyText).matches("[1-9][0-9]*PALLET")) {unit="PALLET";qtyText=norm(qtyText).replace("PALLET","");warnings.add("数量単位はPALLETです。PCSとして集計しないでください");}
        BigDecimal qty=number(qtyText,"数量",true,warnings);
        if(qty!=null && qty.stripTrailingZeros().scale()>0)warnings.add("数量に小数があります");
        for(int c=7;c<=12;c++) {
          CellRangeAddress area=merged.get(key(r,c));
          if(area!=null && (area.getLastRow()>area.getFirstRow() || area.getLastColumn()>area.getFirstColumn()))warnings.add(Character.toString('A'+c)+": 複数行・列に結合された数値です。各行へ複製していません");
        }
        BigDecimal width=number(read.value(r,8),"W(mm)",true,warnings), depth=number(read.value(r,9),"D(mm)",true,warnings), height=number(read.value(r,10),"H(mm)",true,warnings);
        BigDecimal weight=number(read.value(r,11),"重量(kg)",false,warnings), volume=number(read.value(r,12),"体積(m³)",false,warnings);
        if(width!=null && depth!=null && height!=null && volume!=null) {
          BigDecimal calculated=width.multiply(depth).multiply(height).movePointLeft(9);
          BigDecimal tolerance=calculated.abs().multiply(new BigDecimal("0.01")).max(new BigDecimal("0.001"));
          if(volume.subtract(calculated).abs().compareTo(tolerance)>0 && (qty==null || volume.subtract(calculated.multiply(qty)).abs().compareTo(tolerance.multiply(qty.max(BigDecimal.ONE)))>0))
            warnings.add("記載体積と寸法からの計算値が一致しません（単位・数量を確認）。記載値を保持しています");
        }
        String container=read.text(r,13,warnings);
        if(container.isEmpty())warnings.add("コンテナ記載なし");
        else if(!norm(container).matches("(?:[24]0(?:GP|HQ|HC|FR|OT)[*X×][1-9][0-9]*)+"))warnings.add("コンテナは原文保存です。箱型・数量・航空便等を確認してください");
        rows.add(new SourceRow(r+1,contract,equipment,model,part,code,qty,unit,width,depth,height,weight,volume,
            container,read.text(r,14,warnings),read.text(r,15,warnings),List.copyOf(warnings),raw));
      }
      if(rows.isEmpty())throw bad("包装明細がありません");
      return new Parsed(sheet.getSheetName(),List.copyOf(rows),contracts.size(),(int)rows.stream().filter(row->!row.warnings().isEmpty()).count());
    } catch(ResponseStatusException e){throw e;}
    catch(IOException|RuntimeException e){throw bad("Excelを読み取れません。破損・暗号化・形式を確認してください");}
  }
  private static BigDecimal number(String value,String label,boolean positive,List<String> warnings) {
    if(value.isBlank()){warnings.add(label+"が未記入です");return null;}
    try {
      BigDecimal number=new BigDecimal(Normalizer.normalize(value,Normalizer.Form.NFKC).strip());
      if(number.precision()>40 || Math.abs(number.scale())>30 || number.signum()<0 || (positive && number.signum()==0))throw new NumberFormatException();
      if(number.signum()==0)warnings.add(label+"が0です");
      return number;
    } catch(NumberFormatException e){warnings.add(label+"の値を数値として確定できません（原文を保存）");return null;}
  }
  private static String norm(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("\\s+","").toUpperCase(Locale.ROOT);}
  private static String key(int row,int col){return row+":"+col;}
  private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
  private static void checkZip(byte[] bytes) {
    long total=0;int entries=0;
    try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
      byte[] buffer=new byte[8192];ZipEntry entry;
      while((entry=zip.getNextEntry())!=null) {
        if(++entries>2000)throw bad("Excel内のファイル数が上限を超えています");
        int count;while((count=zip.read(buffer))!=-1){total+=count;if(total>40L*1024*1024)throw bad("展開後のExcelサイズが上限を超えています（40MB）");}
      }
      if(entries==0)throw bad(".xlsx形式ではありません");
    }catch(IOException e){throw bad("Excelファイルが破損しています");}
  }
  private static final class Reader {
    private final XSSFSheet sheet;private final Map<String,CellRangeAddress> merged;
    Reader(XSSFSheet sheet,Map<String,CellRangeAddress> merged){this.sheet=sheet;this.merged=merged;}
    private XSSFCell cell(int row,int col){XSSFRow r=sheet.getRow(row);return r==null?null:r.getCell(col);}
    String formula(int row,int col){XSSFCell c=cell(row,col);return c!=null && c.getCellType()==CellType.FORMULA?c.getCellFormula():"";}
    String value(int row,int col) {
      XSSFCell c=cell(row,col);if(c==null)return "";
      CellType type=c.getCellType()==CellType.FORMULA?c.getCachedFormulaResultType():c.getCellType();
      String value=switch(type) {
        case STRING -> c.getStringCellValue();
        case NUMERIC -> c.getRawValue()==null?"":c.getRawValue();
        case BOOLEAN -> Boolean.toString(c.getBooleanCellValue());
        case ERROR -> FormulaError.forInt(c.getErrorCellValue()).getString();
        default -> "";
      };
      if(value.length()>4000 || value.indexOf('\0')>=0)throw bad("行 "+(row+1)+": セルの文字数・内容を確認してください（4000文字まで）");
      return value;
    }
    String text(int row,int col,List<String> warnings) {
      CellRangeAddress area=merged.get(key(row,col));
      if(area!=null){row=area.getFirstRow();col=area.getFirstColumn();}
      String f=formula(row,col);
      if(f.contains("[")){warnings.add("参照元不明の外部リンク値は確定項目に使用していません");return "";}
      String value=value(row,col);
      if(value.startsWith("#")){warnings.add("Excelエラーを含む項目があります");return "";}
      return value.strip();
    }
  }
}
