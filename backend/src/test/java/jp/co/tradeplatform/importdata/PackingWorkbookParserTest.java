package jp.co.tradeplatform.importdata;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class PackingWorkbookParserTest {
  private XSSFWorkbook book;private XSSFSheet sheet;
  @BeforeEach void setup(){book=new XSSFWorkbook();sheet=book.createSheet("Packing Source");
    text(2,1,"契約番号(CONTRACT NO)");text(2,3,"設備名称");text(3,5,"分解部名称");text(3,7,"PCS");text(2,8,"外寸MM");
    text(3,8,"（W)");text(3,9,"（D)");text(3,10,"（H)");text(3,11,"KG");text(2,13,"コンテナ");text(2,14,"出発港");text(2,15,"目的港");
    text(4,1,"TEST-001");text(4,3,"Machine");text(4,4,"M-TEST");text(4,5,"Main body");text(4,6,"A1");
    number(4,7,1);number(4,8,1000);number(4,9,2000);number(4,10,3000);number(4,11,100);number(4,12,6);text(4,13,"40HQ*1");text(4,14,"PORT-A");text(4,15,"PORT-B");}
  @AfterEach void close() throws IOException{book.close();}
  XSSFCell cell(int row,int col){var r=sheet.getRow(row);if(r==null)r=sheet.createRow(row);var c=r.getCell(col);return c==null?r.createCell(col):c;}
  void text(int row,int col,String text){cell(row,col).setCellValue(text);}
  void number(int row,int col,double number){cell(row,col).setCellValue(number);}
  byte[] bytes() throws IOException{var out=new ByteArrayOutputStream();book.write(out);return out.toByteArray();}
  PackingWorkbookParser.Parsed parse() throws IOException{return new PackingWorkbookParser().parse(bytes());}
  @Test void retainsValuesAndPrecision() throws Exception{number(4,12,6.00001);var p=parse();assertEquals(1,p.rows().size());assertEquals(1,p.contractCount());assertEquals(new BigDecimal("6.00001"),p.rows().getFirst().volumeM3());assertEquals("PORT-A",p.rows().getFirst().departurePort());}
  @Test void excelFloatingPointDigitsAreNotRejected() throws Exception{cell(4,12).getCTCell().setV("0.10300000000000001");var r=parse().rows().getFirst();assertEquals(new BigDecimal("0.10300000000000001"),r.volumeM3());assertEquals("0.10300000000000001",r.rawCells().get("M").value());}
  @Test void mergesOnlyInsideDeclaredRanges() throws Exception{sheet.addMergedRegion(new CellRangeAddress(4,5,1,1));sheet.addMergedRegion(new CellRangeAddress(4,5,3,3));text(5,5,"Cover");number(5,7,1);text(6,5,"Unknown equipment");var p=parse();assertEquals("TEST-001",p.rows().get(1).contractNo());assertEquals("Machine",p.rows().get(1).equipmentName());assertEquals("",p.rows().get(2).contractNo());assertEquals("",p.rows().get(2).equipmentName());assertEquals("B5:B6",p.rows().get(1).rawCells().get("B").mergedRange());}
  @Test void mergedPartNameDoesNotBecomeAPackageCode() throws Exception{cell(4,6).setBlank();sheet.addMergedRegion(new CellRangeAddress(4,4,5,6));var r=parse().rows().getFirst();assertEquals("Main body",r.partName());assertEquals("",r.partCode());}
  @Test void sharedMeasurementsAreNotDuplicated() throws Exception{sheet.addMergedRegion(new CellRangeAddress(4,5,7,7));text(5,5,"Cover");var p=parse();assertNull(p.rows().get(1).quantity());assertTrue(p.rows().getFirst().warnings().stream().anyMatch(w->w.contains("複数行")));}
  @Test void swappedQuantityAndCodeAreFlagged() throws Exception{text(4,6,"2");text(4,7,"B5");var r=parse().rows().getFirst();assertEquals(new BigDecimal("2"),r.quantity());assertEquals("B5",r.partCode());assertTrue(r.warnings().stream().anyMatch(w->w.contains("入替")));}
  @Test void modelAndPartShiftAreDetectedByMerge() throws Exception{cell(4,4).setBlank();sheet.addMergedRegion(new CellRangeAddress(4,4,3,4));text(4,5,"MODEL-Z");text(4,6,"Cover");var r=parse().rows().getFirst();assertEquals("MODEL-Z",r.modelNo());assertEquals("Cover",r.partName());assertEquals("",r.partCode());}
  @Test void shiftedLayoutIncludesContinuationRows() throws Exception{cell(4,4).setBlank();sheet.addMergedRegion(new CellRangeAddress(4,4,3,4));sheet.addMergedRegion(new CellRangeAddress(4,5,1,1));text(4,5,"MODEL-Z");text(4,6,"Body");text(5,6,"Conveyor");number(5,7,1);var r=parse().rows().get(1);assertEquals("Conveyor",r.partName());assertEquals("",r.equipmentName());assertEquals("",r.partCode());}
  @Test void missingAndInvalidNumbersAreNotFabricated() throws Exception{text(4,7,"unknown");number(4,8,0);cell(4,11).setBlank();var r=parse().rows().getFirst();assertNull(r.quantity());assertNull(r.widthMm());assertNull(r.weightKg());assertEquals("unknown",r.rawCells().get("H").value());}
  @Test void palletKeepsItsUnit() throws Exception{text(4,7,"1 PALLET");var r=parse().rows().getFirst();assertEquals("PALLET",r.quantityUnit());assertEquals(BigDecimal.ONE,r.quantity());}
  @Test void dimensionMismatchDoesNotRewriteSource() throws Exception{number(4,8,100);var r=parse().rows().getFirst();assertEquals(0,new BigDecimal("100").compareTo(r.widthMm()));assertEquals(0,new BigDecimal("6").compareTo(r.volumeM3()));assertTrue(r.warnings().stream().anyMatch(w->w.contains("計算値")));}
  @Test void savedFormulaResultsAreReadWithoutEvaluation() throws Exception{cell(4,12).setCellFormula("I5*J5*K5/1000000000");cell(4,12).getCTCell().setV("6.000012");var r=parse().rows().getFirst();assertEquals(new BigDecimal("6.000012"),r.volumeM3());assertEquals("I5*J5*K5/1000000000",r.rawCells().get("M").formula());}
  @Test void unresolvedExternalMetadataIsNotUsed() throws Exception{number(4,4,0);cell(4,4).setCellFormula("1+1");cell(4,4).getCTCell().getF().setStringValue("[1]INVOICE!D5");cell(4,4).getCTCell().setV("0");var r=parse().rows().getFirst();assertEquals("",r.modelNo());assertTrue(r.warnings().stream().anyMatch(w->w.contains("外部")));assertEquals("0",r.rawCells().get("E").value());}
  @Test void changedHeadersRejected(){text(3,8,"CM");assertThrows(ResponseStatusException.class,()->parse());}
  @Test void extraSheetsRejected(){book.createSheet("Other");assertThrows(ResponseStatusException.class,()->parse());}
  @Test void unexpectedColumnsRejected(){text(4,16,"extra business data");assertThrows(ResponseStatusException.class,()->parse());}
  @Test void corruptFileRejected(){assertThrows(ResponseStatusException.class,()->new PackingWorkbookParser().parse("not excel".getBytes()));}
  @Test void zeroRowsRejected(){sheet.removeRow(sheet.getRow(4));assertThrows(ResponseStatusException.class,()->parse());}
  @Test void maximumRowBoundChecked(){text(10004,5,"too many");assertThrows(ResponseStatusException.class,()->parse());}
  @Test void originalWorkbookLocallyOnly() throws Exception{
    String local=System.getenv("PACKING_TEST_FILE");Assumptions.assumeTrue(local!=null,"Optional private workbook test; never committed");
    var parsed=new PackingWorkbookParser().parse(Files.readAllBytes(Path.of(local)));
    System.out.printf("PRIVATE WORKBOOK: rows=%d contracts=%d reviewRows=%d%n",parsed.rows().size(),parsed.contractCount(),parsed.reviewRows());
    assertEquals(2718,parsed.rows().size());assertEquals(151,parsed.contractCount());
    assertEquals(5,parsed.rows().getFirst().sourceRow());assertEquals(2722,parsed.rows().getLast().sourceRow());
    var swapped=parsed.rows().stream().filter(r->r.sourceRow()==2423).findFirst().orElseThrow();assertEquals(0,BigDecimal.ONE.compareTo(swapped.quantity()));assertEquals("B5",swapped.partCode());
    assertFalse(parsed.rows().stream().filter(r->r.sourceRow()==2658).findFirst().orElseThrow().partName().isEmpty());
  }
}
