package jp.co.tradeplatform.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  public enum Type { FILE_UPLOAD, DATA_IMPORT, DATA_UPDATE, DATA_DELETE, OPERATION_FAILED }
  private final JdbcTemplate db;
  public NotificationService(JdbcTemplate db){this.db=db;}
  // Joins the caller's business transaction. One recipient/event key can be stored only once.
  @Transactional
  public void record(String recipient,String key,Type type,String message){
    db.update("INSERT INTO t_notification(recipient_id,event_key,event_type,message) VALUES(?,?,?,?) ON CONFLICT(recipient_id,event_key) DO NOTHING",
      recipient,key,type.name(),message);
  }
  public static String fileName(String name){
    if(name==null || name.isBlank())return "ファイル";
    String clean=name.replace('\\','/');clean=clean.substring(clean.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","_");
    return clean.substring(0,Math.min(255,clean.length()));
  }
}
