package com.example.bygynal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.time.*;
import java.math.BigDecimal;

/** Server-rendered first UI stage. Transactional order workflows are separate. */
@Controller
public class PageController {
    private final AuthService auth;
    private final JdbcTemplate jdbc;
    private final CatalogService catalog;
    public PageController(AuthService auth, JdbcTemplate jdbc, CatalogService catalog) {
        this.auth=auth; this.jdbc=jdbc; this.catalog=catalog;
    }
    @ModelAttribute
    void shell(Model model, Authentication authentication, jakarta.servlet.http.HttpServletRequest request) {
        UserAccount account=authentication!=null && authentication.getPrincipal() instanceof SecurityConfig.Principal p ? auth.user(p.id()) : null;
        model.addAttribute("account", account);
        model.addAttribute("roleName", account==null ? "Гость" : switch(account.role) {case "SELLER" -> "Сотрудник заведения"; case "ADMIN" -> "Администратор"; default -> "Покупатель";});
        model.addAttribute("today", LocalDate.now(ZoneId.of("Asia/Bishkek")));
        boolean admin = account != null && "ADMIN".equals(account.role);
        model.addAttribute("adminShell", admin);
        if (admin) {
            String path = request.getRequestURI();
            model.addAttribute("adminSection", path.equals("/profile") ? "profile" : path.equals("/admin") ? "users" : "catalog");
        }
    }
    @GetMapping({"/", "/catalog"})
    String home(Model model, @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String type,
        @RequestParam(required=false) BigDecimal min, @RequestParam(required=false) BigDecimal max,
        @RequestParam(required=false) LocalTime until, @RequestParam(defaultValue="time") String sort) {
        if ((min != null && min.signum() < 0) || (max != null && max.signum() < 0) ||
            (min != null && max != null && min.compareTo(max) > 0)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Проверьте диапазон цены");
        }
        model.addAttribute("offers",catalog.search(q,type,min,max,until,sort));
        model.addAttribute("types",catalog.categories()); model.addAttribute("q",q); model.addAttribute("type",type);
        model.addAttribute("min",min); model.addAttribute("max",max); model.addAttribute("until",until); model.addAttribute("sort",sort);
        return "home";
    }
    @GetMapping({"/login", "/register", "/forgot"})
    String login(Model model, jakarta.servlet.http.HttpServletRequest request) {
        model.addAttribute("authPage", request.getRequestURI().substring(1)); return "auth";
    }
    @GetMapping("/profile") String profile() {return "profile";}
    @GetMapping("/seller") String seller(Model model, Authentication authentication) {
        var u=auth.user(AuthController.id(authentication));
        var places=jdbc.queryForList("select id, name, address from establishments where id = ?",u.establishmentId);
        model.addAttribute("place",places.isEmpty()?null:places.get(0));
        return "seller";
    }
    @GetMapping("/admin") String admin(Model model, @RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="") String role, @RequestParam(defaultValue="") String state) {
        String term="%"+q.toLowerCase(Locale.ROOT)+"%";
        var rows=jdbc.queryForList("select id,name,email,phone,role,blocked from app_users where (lower(name) like ? or lower(email) like ? or phone like ?) and (? = '' or role = ?) and (? = '' or blocked = ?) order by name limit 100", term,term,term,role,role,state,"blocked".equals(state));
        model.addAttribute("members",rows); model.addAttribute("q",q); model.addAttribute("selectedRole",role); model.addAttribute("state",state);
        model.addAttribute("counts",jdbc.queryForMap("select count(*) as total, count(case when role='USER' then 1 end) as buyers, count(case when role='SELLER' then 1 end) as sellers, count(case when role='ADMIN' then 1 end) as admins, count(case when blocked then 1 end) as blocked from app_users"));
        model.addAttribute("places",jdbc.queryForList("select id,name,address from establishments order by name limit 100"));
        return "admin";
    }
}
