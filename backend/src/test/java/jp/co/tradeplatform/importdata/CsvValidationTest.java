package jp.co.tradeplatform.importdata;

import java.io.StringReader;
import java.util.*;
import org.apache.commons.csv.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class CsvValidationTest {
  private static final String HEADER="export_no,customer_code,customer_name,equipment_code,equipment_name,model_no,invoice_no,invoice_date,export_date,part_name,length_mm,width_mm,height_mm,gross_weight_kg,volume_m3,container_type,container_qty,record_basis\n";
  private String[] row(){return "E1,C1,Customer,P1,Equipment,Model,I1,2026-10-12,2026-10-12,Part,100,200,300,10,0.006,20DRY,1,DOCUMENTED".split(",");}
  private List<CSVRecord> parse(String[]... rows) throws Exception {
    String text=HEADER+Arrays.stream(rows).map(r->String.join(",",r)).reduce("",(a,b)->a+b+"\n");
    try(var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(new StringReader(text))){return parser.getRecords();}
  }
  @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7,8,15,16,17})
  void conflictingGroupHeadersAreRejected(int index) throws Exception {
    var a=row();var b=row();b[index]=switch(index){case 7,8->"2026-10-13";case 16->"2";case 17->"ACTUAL";default->"different";};
    var records=parse(a,b);assertThrows(ResponseStatusException.class,()->CsvImportController.validateRows(records));
  }
  @Test void differentPartsInSameExportAreAllowed() throws Exception {
    var b=row();b[9]="Second part";b[13]="0";
    assertDoesNotThrow(()->CsvImportController.validateRows(parse(row(),b)));
  }
  @ParameterizedTest @ValueSource(strings={"0","-1","10000000000","1.001"})
  void invalidDimensionsDoNotReachDatabase(String dimension) throws Exception {
    var a=row();a[10]=dimension;var records=parse(a);
    assertThrows(ResponseStatusException.class,()->CsvImportController.validateRows(records));
  }
}
