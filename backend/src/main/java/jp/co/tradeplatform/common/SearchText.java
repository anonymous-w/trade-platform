package jp.co.tradeplatform.common;

/** Literal substring matching; user-entered SQL wildcards are ordinary text. */
public final class SearchText {
  private SearchText() {}
  public static String contains(String value) {
    return "%" + value.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }
}
