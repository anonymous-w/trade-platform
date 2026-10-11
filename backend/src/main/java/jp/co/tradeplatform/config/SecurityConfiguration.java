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
    return username -> jdbc.query("SELECT user_id,password_hash,role_code,enabled_flg FROM m_user WHERE user_id=?", rs -> {
      if (!rs.next()) throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Unknown user");
      return User.withUsername(rs.getString(1)).password(rs.getString(2))
        .roles(rs.getString(3)).disabled(!rs.getBoolean(4)).build();
    }, username);
  }
  @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
      .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
      .authorizeHttpRequests(a -> a
        .requestMatchers("/api/auth/csrf", "/actuator/health").permitAll()
        .anyRequest().authenticated())
      .formLogin(f -> f.loginProcessingUrl("/api/auth/login")
        .successHandler((req,res,auth) -> { res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"ok\":true}"); })
        .failureHandler((req,res,e) -> { res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"error\":\"ユーザー名またはパスワードが正しくありません\"}"); }))
      .logout(l -> l.logoutUrl("/api/auth/logout")
        .logoutSuccessHandler((req,res,auth) -> { res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"ok\":true}"); }))
      .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> {
        res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
      }).accessDeniedHandler((req,res,ex) -> res.sendError(403, "Access denied")))
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
