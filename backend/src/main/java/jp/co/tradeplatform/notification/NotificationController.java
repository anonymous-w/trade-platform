package jp.co.tradeplatform.notification;

import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
  private final JdbcTemplate db;
  public NotificationController(JdbcTemplate db){this.db=db;}
  @GetMapping
  public Map<String,Object> list(Principal user,@RequestParam(required=false) Long before,
      @RequestParam(defaultValue="20") int size){
    if(size<1 || size>100 || (before!=null && before<1))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ページ条件を確認してください");
    var args=new ArrayList<Object>();args.add(user.getName());if(before!=null)args.add(before);args.add(size+1);
    var rows=db.query("SELECT notification_id,event_type,message,created_at,read_at FROM t_notification WHERE recipient_id=?"+
      (before==null?"":" AND notification_id<?")+" ORDER BY notification_id DESC LIMIT ?",(rs,n)->{
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",rs.getLong(1));row.put("type",rs.getString(2));row.put("message",rs.getString(3));
        row.put("createdAt",rs.getTimestamp(4).toInstant().toString());row.put("readAt",rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant().toString());return row;
      },args.toArray());
    boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);
    return Map.of("rows",rows,"hasMore",more,"unreadCount",unread(user).get("unreadCount"));
  }
  @GetMapping("/unread-count") public Map<String,Long> unread(Principal user){
    return Map.of("unreadCount",db.queryForObject("SELECT count(*) FROM t_notification WHERE recipient_id=? AND read_at IS NULL",Long.class,user.getName()));
  }
  @PostMapping("/{id}/read") public Map<String,Boolean> read(@PathVariable long id,Principal user){
    int changed=db.update("UPDATE t_notification SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE notification_id=? AND recipient_id=?",id,user.getName());
    if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"通知が見つかりません");return Map.of("ok",true);
  }
  @PostMapping("/read-all") public Map<String,Boolean> readAll(Principal user){
    db.update("UPDATE t_notification SET read_at=CURRENT_TIMESTAMP WHERE recipient_id=? AND read_at IS NULL",user.getName());return Map.of("ok",true);
  }
}
