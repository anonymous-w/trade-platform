package jp.co.tradeplatform.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);
  private ResponseEntity<Map<String,String>> error(int status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String,String>> known(ResponseStatusException e) {
    return error(e.getStatusCode().value(),e.getReason()==null?"リクエストを処理できませんでした":e.getReason());
  }
  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  ResponseEntity<Map<String,String>> forbidden(Exception e) { return error(403,"この操作には管理者権限が必要です"); }
  @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
      org.springframework.web.bind.MissingServletRequestParameterException.class,
      org.springframework.web.multipart.support.MissingServletRequestPartException.class,
      org.springframework.http.converter.HttpMessageNotReadableException.class})
  ResponseEntity<Map<String,String>> invalid(Exception e) { return error(400,"入力値・必須項目・ファイルを確認してください"); }
  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Map<String,String>> method(Exception e) { return error(405,"この操作は対応していません"); }
  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Map<String,String>> media(Exception e) { return error(415,"ファイルまたは送信形式を確認してください"); }
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String,String>> size(Exception e) { return error(413,"ファイルサイズが上限を超えています（PDF: 15MB、CSV: 2MB）"); }
  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<Map<String,String>> conflict(Exception e) { return error(409,"番号の重複または関連データの不整合があります"); }
  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String,String>> unknown(Exception e) {
    log.error("API operation failed",e);
    return error(500,"処理に失敗しました。時間をおいて再度お試しください");
  }
}
