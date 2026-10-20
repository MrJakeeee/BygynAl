package com.example.bygynal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.*;

@RestController @RequestMapping("/api")
public class AuthController {
    final AuthService service; final SecurityContextRepository repo;
    public AuthController(AuthService service,SecurityContextRepository repo) {this.service=service;this.repo=repo;}
    @GetMapping("/auth/csrf") Object csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @PostMapping("/auth/register") Object register(@Valid @RequestBody Requests.Register r) {return service.register(r);}
    @PostMapping("/auth/register/verify") Object registerVerify(@Valid @RequestBody Requests.Verify r) { service.confirmRegistration(r); return message("Регистрация завершена. Войдите с паролем"); }
    @PostMapping("/auth/login") Object login(@Valid @RequestBody Requests.Login r,HttpServletRequest req,HttpServletResponse res) {
        var result=service.login(r);
        if(result.challenge()!=null) return Map.of("twoFactorRequired",true,"challenge",result.challenge());
        return authenticate(result.user(),req,res);
    }
    @PostMapping("/auth/login/verify") Object loginVerify(@Valid @RequestBody Requests.Verify r,HttpServletRequest req,HttpServletResponse res) {return authenticate(service.confirmLogin(r),req,res);}
    Object authenticate(UserAccount u,HttpServletRequest req,HttpServletResponse res) {
        req.getSession(); req.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(new SecurityConfig.Principal(u.id,u.sessionVersion),null,List.of(new SimpleGrantedAuthority("ROLE_"+u.role))));
        SecurityContextHolder.setContext(context); repo.saveContext(context,req,res);
        return AuthService.view(u);
    }
    static UUID id(Authentication a) {return ((SecurityConfig.Principal)a.getPrincipal()).id();}
    @PostMapping("/auth/password/forgot") Object forgot(@Valid @RequestBody Requests.EmailRequest r) { return service.forgot(r); }
    @PostMapping("/auth/password/reset") Object reset(@Valid @RequestBody Requests.Reset r) { service.reset(r); return message("Пароль изменён. Войдите заново"); }
    @GetMapping("/profile") Object profile(Authentication a) {return AuthService.view(service.user(id(a)));}
    @PatchMapping("/profile") Object profile(Authentication a,@Valid @RequestBody Requests.Profile r) {service.profile(id(a),r); return message("Профиль сохранён");}
    @PostMapping("/profile/password") Object password(Authentication a,@Valid @RequestBody Requests.Password r,HttpServletRequest req) {service.changePassword(id(a),r); req.getSession().invalidate(); return message("Пароль изменён. Войдите заново");}
    @PostMapping("/profile/two-factor") Object factor(Authentication a,@Valid @RequestBody Requests.TwoFactor r) { return service.twoFactor(id(a),r); }
    @PostMapping("/profile/two-factor/verify") Object factorVerify(Authentication a,@Valid @RequestBody Requests.Verify r,@RequestParam boolean enabled,HttpServletRequest req) {service.confirmTwoFactor(id(a),r,enabled); req.getSession().invalidate(); return message("Настройка сохранена. Войдите заново");}
    @PostMapping("/profile/email") Object email(Authentication a,@Valid @RequestBody Requests.ChangeEmail r) {return service.changeEmail(id(a),r);}
    @PostMapping("/profile/email/verify") Object emailVerify(Authentication a,@Valid @RequestBody Requests.Verify r,HttpServletRequest req) {service.confirmEmail(id(a),r); req.getSession().invalidate(); return message("E-mail изменён. Войдите заново");}
    @GetMapping("/seller/access") Object seller(Authentication a) {return message("Доступ сотрудника подтверждён. Заведение: "+service.user(id(a)).establishmentId);}
    @GetMapping("/buyer/access") Object buyer() {return message("Доступ покупателя подтверждён");}
    static Map<String,String> message(String s) {return Map.of("message",s);}
}
