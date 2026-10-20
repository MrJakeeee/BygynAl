package com.example.bygynal;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain security(HttpSecurity http,Users users,SecurityContextRepository repo) throws Exception {
        return http.cors(c->{}).securityContext(c->c.securityContextRepository(repo))
            .authorizeHttpRequests(a->a
                .requestMatchers("/api/auth/**","/error").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/seller/**").hasRole("SELLER")
                .requestMatchers("/api/buyer/**").hasRole("USER")
                .anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->json(res,401,"Войдите в систему"))
                .accessDeniedHandler((req,res,ex)->json(res,403,"Недостаточно прав или неверный CSRF-токен")))
            .logout(l->l.logoutUrl("/api/logout").logoutSuccessHandler((req,res,a)->res.setStatus(204)).deleteCookies("JSESSIONID"))
            .addFilterAfter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                    var auth=SecurityContextHolder.getContext().getAuthentication();
                    if(auth!=null && auth.getPrincipal() instanceof Principal p) {
                        var u=users.findById(p.id()).orElse(null);
                        if(u==null||u.blocked||u.sessionVersion!=p.version()) {
                            if(req.getSession(false)!=null) req.getSession(false).invalidate();
                            SecurityContextHolder.clearContext(); json(res,401,"Сессия завершена. Войдите заново"); return;
                        }
                        String path=req.getRequestURI();
                        if(u.mustChangePassword && !Set.of("/api/profile","/api/profile/password","/api/auth/csrf","/api/logout").contains(path)) {
                            json(res,403,"Сначала смените временный пароль"); return;
                        }
                    }
                    chain.doFilter(req,res);
                }
            },SecurityContextHolderFilter.class).build();
    }
    static void json(HttpServletResponse res,int status,String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\""+message+"\"}");
    }
    @Bean CorsConfigurationSource cors(@Value("${app.frontend-origin}") String origin) {
        CorsConfiguration c=new CorsConfiguration(); c.setAllowedOrigins(List.of(origin)); c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        c.setAllowedHeaders(List.of("Content-Type","X-CSRF-TOKEN")); c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**",c); return source;
    }
    public record Principal(UUID id,int version) implements java.io.Serializable {}
}
