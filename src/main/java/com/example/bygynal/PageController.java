package com.example.bygynal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.time.*;

/** Server-rendered first UI stage. Transactional order workflows are separate. */
@Controller
public class PageController {
    private final AuthService auth;
    private final JdbcTemplate jdbc;
    private final boolean demo;
    public PageController(AuthService auth, JdbcTemplate jdbc, @Value("${app.ui-demo:false}") boolean demo) {
        this.auth=auth; this.jdbc=jdbc; this.demo=demo;
    }
    @ModelAttribute
    void shell(Model model, Authentication authentication) {
        UserAccount account=authentication!=null && authentication.getPrincipal() instanceof SecurityConfig.Principal p ? auth.user(p.id()) : null;
        model.addAttribute("account", account);
        model.addAttribute("roleName", account==null ? "Гость" : switch(account.role) {case "SELLER" -> "Сотрудник заведения"; case "ADMIN" -> "Администратор"; default -> "Покупатель";});
        model.addAttribute("today", LocalDate.now(ZoneId.of("Asia/Bishkek")));
        model.addAttribute("demo", demo);
    }
    @GetMapping({"/", "/catalog"})
    String home(Model model, @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String type,
        @RequestParam(required=false) Integer min, @RequestParam(required=false) Integer max,
        @RequestParam(defaultValue="") String until, @RequestParam(defaultValue="time") String sort) {
        var offers=demo ? examples() : List.<Offer>of();
        String search=q.trim().toLowerCase(Locale.ROOT);
        var filtered=offers.stream().filter(o->o.remaining()>0)
            .filter(o->search.isEmpty() || (o.title()+o.composition()+o.place()).toLowerCase(Locale.ROOT).contains(search))
            .filter(o->type.isBlank() || o.type().equals(type)).filter(o->min==null || o.price()>=min).filter(o->max==null || o.price()<=max)
            .filter(o->until.isBlank() || o.start().compareTo(until)<=0)
            .sorted(switch(sort) {case "price" -> Comparator.comparingInt(Offer::price); case "discount" -> Comparator.comparingInt(Offer::discount).reversed(); default -> Comparator.comparing(Offer::start);}).toList();
        model.addAttribute("offers",filtered); model.addAttribute("q",q); model.addAttribute("type",type);
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
    public record Offer(int id,String title,String place,String type,String composition,int price,int original,int remaining,String start,String end,String image) {
        public int discount(){return (original-price)*100/original;}
    }
    private static List<Offer> examples() {
        return List.of(
            new Offer(1,"Набор свежей выпечки на вечер","Пекарня «Тандыр» · ул. Киевская","Пекарня","Самсы, булочки и круассаны",250,500,3,"19:00","21:00","s0-img.png"),
            new Offer(2,"Горячий вечерний сет с пловом и салатом","Кафе «Ала-Тоо» · пр. Чуй","Кафе","Плов, самсы и салат",240,600,1,"20:00","21:30","s0-img1.png"),
            new Offer(3,"Сэндвич-бокс и авторские десерты","Кофейня «Утро» · ул. Боконбаева","Кафе","Сэндвичи и десерты",225,450,4,"18:30","20:30","s0-img2.png"),
            new Offer(4,"Сюрприз-сет роллов Филадельфия и Калифорния","Суши-бар «Сакура» · мкр. Асанбай","Ресторан","Роллы Филадельфия и Калифорния",360,800,2,"20:30","22:00","s0-img3.png"),
            new Offer(5,"Сладкий бокс: медовик и эклеры","Кондитерская «Медовик» · ул. Токтогула","Кондитерская","Медовик и эклеры",250,500,5,"19:00","21:00","s0-img4.png"));
    }
}
