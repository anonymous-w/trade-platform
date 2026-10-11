package jp.co.tradeplatform.common;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrorHandler {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String,String>> known(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()==null?"Request failed":e.getReason()));
  }
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String,String>> size(MaxUploadSizeExceededException e) { return ResponseEntity.status(413).body(Map.of("error","File too large (15MB limit)")); }
  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<Map<String,String>> conflict(org.springframework.dao.DataIntegrityViolationException e) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","Duplicate or invalid related record")); }
  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String,String>> unknown(Exception e) {
    // Do not expose internal paths, SQL or credentials to callers
    return ResponseEntity.status(500).body(Map.of("error","Server operation failed"));
  }
}
