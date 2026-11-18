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
    private final Establishments establishments;
    private final Offers offers;
    public PageController(AuthService auth, JdbcTemplate jdbc, CatalogService catalog, Establishments establishments, Offers offers) {
        this.auth=auth; this.jdbc=jdbc; this.catalog=catalog; this.establishments=establishments; this.offers=offers;
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
    @GetMapping({"/offers/{id}", "/catalog/{id}"})
    String offerDetails(@PathVariable UUID id, Model model) {
        CatalogService.Offer offer = catalog.findById(id);
        if (offer == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        model.addAttribute("offer", offer);
        return "offer-details";
    }
    @GetMapping({"/login", "/register", "/forgot"})
    String login(Model model, jakarta.servlet.http.HttpServletRequest request) {
        model.addAttribute("authPage", request.getRequestURI().substring(1)); return "auth";
    }
    @GetMapping("/profile") String profile() {return "profile";}
    // ---------- Кабинет сотрудника заведения (ТЗ 4.3, 4.4) ----------
    private UUID placeId(Authentication authentication) {return auth.user(AuthController.id(authentication)).establishmentId;}
    @GetMapping("/seller") String seller(Model model, Authentication authentication) {
        UUID placeId=placeId(authentication);
        var list=placeId==null ? List.<Offers.View>of() : offers.forEstablishment(placeId);
        model.addAttribute("place",placeId==null ? null : establishments.find(placeId).orElse(null));
        model.addAttribute("offers",list);
        model.addAttribute("activeCount",list.stream().filter(o->o.status()==Offers.Status.ACTIVE).count());
        model.addAttribute("soldCount",list.stream().mapToInt(Offers.View::sold).sum());
        return "seller";
    }
    @GetMapping("/seller/establishment") String establishment(Model model, Authentication authentication) {
        UUID placeId=placeId(authentication);
        var place=placeId==null ? null : establishments.find(placeId).orElse(null);
        model.addAttribute("place",place);
        String[] hours=splitOpeningHours(place==null ? null : place.openingHours());
        model.addAttribute("openingStart",hours[0]); model.addAttribute("openingEnd",hours[1]);
        model.addAttribute("types",Establishments.TYPES);
        return "seller-establishment";
    }
    private String[] splitOpeningHours(String value) {
        String[] result={"", ""};
        if(value==null) return result;
        var matcher=java.util.regex.Pattern.compile("(?<!\\d)(\\d{1,2}):?(\\d{2})(?!\\d)").matcher(value);
        if(!matcher.find()) return result;
        int firstHour=Integer.parseInt(matcher.group(1)), firstMinute=Integer.parseInt(matcher.group(2));
        if(firstHour>23||firstMinute>59) return result;
        result[0]=String.format(java.util.Locale.ROOT,"%02d:%02d",firstHour,firstMinute);
        if(matcher.find()) {
            int secondHour=Integer.parseInt(matcher.group(1)), secondMinute=Integer.parseInt(matcher.group(2));
            if(secondHour<=23&&secondMinute<=59) result[1]=String.format(java.util.Locale.ROOT,"%02d:%02d",secondHour,secondMinute);
        }
        return result;
    }
    @GetMapping("/seller/offers/new") String newOffer(Model model, Authentication authentication) {
        UUID id=placeId(authentication); model.addAttribute("offer",null);
        model.addAttribute("place",id==null ? null : establishments.find(id).orElse(null)); return "seller-offer";
    }
    @GetMapping("/seller/offers/{id}") String editOffer(Model model, Authentication authentication, @PathVariable UUID id) {
        try {model.addAttribute("offer",offers.get(placeId(authentication),id));}
        catch(ApiError e) {throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);}
        UUID establishmentId=placeId(authentication);
        model.addAttribute("place",establishmentId==null ? null : establishments.find(establishmentId).orElse(null));
        return "seller-offer";
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
