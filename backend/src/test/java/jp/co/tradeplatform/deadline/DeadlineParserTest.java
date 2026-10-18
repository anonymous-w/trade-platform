package jp.co.tradeplatform.deadline;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
class DeadlineParserTest {
  void text(Sheet s,int r,int c,String v){Row row=s.getRow(r);if(row==null)row=s.createRow(r);row.createCell(c).setCellValue(v);}
  void setup(Workbook b){Sheet s=b.createSheet("期限管理表");String[] headers={"契約番号","輸出許可番号","役務許可番号","商品名","型番","経済産業省\n輸出許可日","有効期限","貨物輸入後設置日の確認","設置完了\n報告提出期限","貨物B/L日付","一年目","二年目","三年目","四年目","五年目","担当者","責任者","条件内容"};for(int i=0;i<headers.length;i++)text(s,3,i+2,headers[i]);
    text(s,4,2,"TEST-C");text(s,4,3,"TEST-P");text(s,4,4,"TEST-S");text(s,4,5,"Machine");text(s,4,6,"MODEL");text(s,4,7,"2024/01/05");text(s,4,8,"2024年7月末日");text(s,4,10,"2024年10月末日");text(s,4,12,"２０２５年１月末日");text(s,5,12,"確認完了日：\n2024年11月22日");text(s,4,13,"２０２６年１月末日");text(s,5,13,"確認完了予定日：\n2025/01/08");s.addMergedRegion(new CellRangeAddress(4,6,1,1));text(s,7,1,"2");s.addMergedRegion(new CellRangeAddress(7,9,1,1));}
  DeadlineParser.Parsed parse(Workbook b)throws Exception{var out=new ByteArrayOutputStream();b.write(out);return new DeadlineParser().parse(out.toByteArray());}
  @Test void xlsxBlocksAndAnnualLabels()throws Exception{try(var b=new XSSFWorkbook()){setup(b);var p=parse(b);assertEquals(1,p.records().size());var e=p.records().getFirst();assertEquals(5,e.sourceRow());assertEquals(7,e.endRow());assertEquals(LocalDate.of(2025,1,31),e.years().get(0).dueDate());assertEquals(LocalDate.of(2024,11,22),e.years().get(0).completedDate());assertNull(e.years().get(0).plannedDate());assertEquals(LocalDate.of(2025,1,8),e.years().get(1).plannedDate());assertTrue(e.warnings().stream().anyMatch(w->w.contains("半年")));}}
  @Test void supportsXlsAndNumericDates()throws Exception{try(var b=new HSSFWorkbook()){setup(b);Cell c=b.getSheetAt(0).getRow(4).getCell(7);c.setCellValue(LocalDate.of(2024,1,5));CellStyle style=b.createCellStyle();style.setDataFormat(b.createDataFormat().getFormat("yyyy/mm/dd"));c.setCellStyle(style);assertEquals(LocalDate.of(2024,1,5),parse(b).records().getFirst().permitDate());}}
  @Test void unlabelledSupplementIsNotCompletion()throws Exception{try(var b=new XSSFWorkbook()){setup(b);text(b.getSheetAt(0),5,12,"２０２６年１２月末日");var y=parse(b).records().getFirst().years().getFirst();assertNull(y.completedDate());assertNull(y.plannedDate());assertTrue(y.rawText().contains("２０２６"));}}
  @Test void rejectsBrokenAndWrongSheet(){assertThrows(Exception.class,()->new DeadlineParser().parse(new byte[]{1,2,3}));}
  @Test void invalidDatesStayUnknown(){assertNull(DeadlineParser.date("2024/02/31"));assertNull(DeadlineParser.date("1/5"));assertNull(DeadlineParser.date("未定"));assertEquals(LocalDate.of(2024,2,29),DeadlineParser.date("２０２４年２月末日"));}
  @Test void conditionsMatchBothKeys()throws Exception{try(var b=new XSSFWorkbook()){setup(b);Sheet c=b.createSheet("条件内容");text(c,3,1,"TEST-C");text(c,3,2,"TEST-P");text(c,3,3,"Actual condition");text(c,4,1,"TEST-C");text(c,4,2,"OTHER");text(c,4,3,"Wrong condition");assertEquals("Actual condition",parse(b).records().getFirst().conditions());}}
  @Test void optionalRealWorkbook()throws Exception{String path=System.getenv("DEADLINE_TEST_FILE");if(path==null||path.isBlank())return;var p=new DeadlineParser().parse(Files.readAllBytes(Path.of(path)));assertEquals(2,p.records().size());assertEquals("SDK112",p.records().get(0).contractNo());assertEquals("SDK117",p.records().get(1).contractNo());assertEquals(LocalDate.of(2024,7,5),p.records().get(0).validUntil());assertTrue(p.records().get(1).years().get(0).rawText().contains("２０２６"));}
}
