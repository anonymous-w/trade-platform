package jp.co.tradeplatform.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
  @Bean UserDetailsService users(JdbcTemplate jdbc) {
    return username -> jdbc.query("SELECT user_id,password_hash,role_code,enabled_flg,locked_until > CURRENT_TIMESTAMP AS locked FROM m_user WHERE user_id=?", rs -> {
      if (!rs.next()) throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Unknown user");
      return User.withUsername(rs.getString(1)).password(rs.getString(2))
        .roles(rs.getString(3)).disabled(!rs.getBoolean(4)).accountLocked(rs.getBoolean(5)).build();
    }, username);
  }
  @Bean SecurityFilterChain filterChain(HttpSecurity http, JdbcTemplate jdbc) throws Exception {
    return http
      .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
      .authorizeHttpRequests(a -> a
        .requestMatchers("/api/auth/csrf", "/actuator/health").permitAll()
        .anyRequest().authenticated())
      .formLogin(f -> f.loginProcessingUrl("/api/auth/login")
        .successHandler((req,res,auth) -> {
          jdbc.update("UPDATE m_user SET failed_count=0,locked_until=NULL,last_login_at=CURRENT_TIMESTAMP WHERE user_id=?",auth.getName());
          res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"ok\":true}"); })
        .failureHandler((req,res,e) -> {
          if (e instanceof org.springframework.security.authentication.BadCredentialsException) {
            jdbc.update("""
              UPDATE m_user SET failed_count=CASE WHEN locked_until IS NOT NULL THEN 1 ELSE LEAST(failed_count+1,999) END,
                locked_until=CASE WHEN locked_until IS NULL AND failed_count>=4 THEN CURRENT_TIMESTAMP+INTERVAL '5 minutes' ELSE NULL END
              WHERE user_id=? AND enabled_flg AND (locked_until IS NULL OR locked_until<=CURRENT_TIMESTAMP)
              """,req.getParameter("username"));
          }
          res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"error\":\"ログインできません。入力内容を確認し、連続して失敗した場合は5分後にお試しください\"}"); }))
      .logout(l -> l.logoutUrl("/api/auth/logout")
        .logoutSuccessHandler((req,res,auth) -> { res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"ok\":true}"); }))
      .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> {
        res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"error\":\"セッションが切れました。再度ログインしてください\"}");
      }).accessDeniedHandler((req,res,ex) -> {
        boolean csrf = ex instanceof org.springframework.security.web.csrf.CsrfException;
        res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(csrf ? "{\"error\":\"画面の認証情報を更新してください\",\"code\":\"CSRF_INVALID\"}" : "{\"error\":\"この操作を行う権限がありません\"}");
      }))
      .headers(h -> h.frameOptions(f -> f.sameOrigin()))
      .build();
  }
  @RestController
  static class AuthController {
    @GetMapping("/api/auth/csrf") public java.util.Map<String,String> csrf(@RequestAttribute("_csrf") CsrfToken token) {
      return java.util.Map.of("token",token.getToken(),"header",token.getHeaderName());
    }
    @GetMapping("/api/auth/me") public java.util.Map<String,Object> me(org.springframework.security.core.Authentication a) {
      return java.util.Map.of("username",a.getName(),"roles",a.getAuthorities().stream().map(Object::toString).toList());
    }
  }
}
