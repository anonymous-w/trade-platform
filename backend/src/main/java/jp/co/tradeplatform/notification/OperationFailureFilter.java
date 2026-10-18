package jp.co.tradeplatform.notification;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs inside the security chain's servlet continuation, after MVC transaction rollback. */
@Component
public class OperationFailureFilter extends OncePerRequestFilter {
  private final NotificationService notifications;
  public OperationFailureFilter(NotificationService notifications){this.notifications=notifications;}
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
    var actor=request.getUserPrincipal();String path=request.getRequestURI();
    boolean observed=actor!=null && request.getMethod().equals("POST") &&
      Set.of("/api/imports/packing/preview","/api/imports/packing","/api/imports/csv","/api/documents","/api/imports/deadlines/preview","/api/imports/deadlines").contains(path);
    String key="failure:"+UUID.randomUUID();
    try {chain.doFilter(request,response);} finally {
      if(observed && response.getStatus()>=400 && response.getStatus()!=401 && response.getStatus()!=403){
        String operation=path.endsWith("preview")?"プレビュー":path.equals("/api/documents")?"アップロード":"取込み";
        String name=NotificationService.fileName((String)request.getAttribute("notification.fileName"));
        try {notifications.record(actor.getName(),key,NotificationService.Type.OPERATION_FAILED,
          name+" の"+operation+"に失敗しました。画面のエラー内容・ファイル形式を確認してください。");}
        catch(Exception e){LoggerFactory.getLogger(getClass()).error("Could not persist operation failure notification",e);}
      }
    }
  }
}
