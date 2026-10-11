package jp.co.tradeplatform.export;

import jp.co.tradeplatform.export.ExportController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExportCsvUnitTest {
  @Test void csvValuesEscapeQuotesAndNeutralizeFormulaCells() {
    assertEquals("\"abc\"", ExportController.csvValue("abc"));
    assertEquals("\"a\"\"b\"", ExportController.csvValue("a\"b"));
    assertEquals("\"'=SUM(1,1)\"", ExportController.csvValue("=SUM(1,1)"));
  }
}
