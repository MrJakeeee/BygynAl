package com.example.bygynal;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.hamcrest.Matchers.*;

@SpringBootTest(properties="app.ui-demo=true") @AutoConfigureMockMvc
class PageTests {
    @Autowired MockMvc mvc; @Autowired Users users; @Autowired Challenges challenges; @Autowired JdbcTemplate jdbc;
    @BeforeEach void clear() {
        jdbc.update("delete from admin_audit"); challenges.deleteAll(); users.deleteAll(); jdbc.update("delete from establishments");
    }
    UserAccount account(String role) {
        var u=new UserAccount(); u.name="Айбек <script>"; u.email=role.toLowerCase()+"@gmail.com"; u.phone="+996700123456";u.passwordHash="PRIVATE_HASH";u.role=role;
        if(role.equals("SELLER")) {u.establishmentId=UUID.randomUUID();jdbc.update("insert into establishments values (?,?,?)",u.establishmentId,"Пекарня «Тандыр»","ул. Киевская, 95");}
        return users.saveAndFlush(u);
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor as(UserAccount u) {
        return authentication(new UsernamePasswordAuthenticationToken(new SecurityConfig.Principal(u.id,u.sessionVersion),null,List.of(new SimpleGrantedAuthority("ROLE_"+u.role))));
    }
    @Test void publicCatalogRendersLocalAssetsAndFiltersOnServer() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("home"))
            .andExpect(content().string(containsString("Набор свежей выпечки на вечер")))
            .andExpect(content().string(containsString("/assets/s0-img.png")))
            .andExpect(content().string(not(containsString("www.figma.com/api/"))));
        mvc.perform(get("/").param("q","самсы").param("max","245").param("type","Кафе"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Горячий вечерний сет")))
            .andExpect(content().string(not(containsString("Набор свежей выпечки на вечер"))));
        mvc.perform(get("/").param("q","нет такого набора")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Наборы не найдены")));
        mvc.perform(get("/").param("until","18:00")).andExpect(status().isOk()).andExpect(model().attribute("offers",hasSize(0)));
    }
    @Test void loginPagesAndAssetsArePublic() throws Exception {
        for(String path:List.of("/login","/register","/forgot","/css/site.css","/js/site.js","/assets/s0-img.png"))
            mvc.perform(get(path)).andExpect(status().isOk());
        mvc.perform(get("/profile")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }
    @Test void everyRoleHasSafeServerRenderedProfile() throws Exception {
        for(String role:List.of("USER","SELLER","ADMIN")) {
            var u=account(role);
            mvc.perform(get("/profile").with(as(u))).andExpect(status().isOk()).andExpect(view().name("profile"))
                .andExpect(content().string(containsString("Айбек &lt;script&gt;")))
                .andExpect(content().string(not(containsString("PRIVATE_HASH"))));
        }
    }
    @Test void dashboardsEnforceRolesAndUseCurrentEstablishment() throws Exception {
        var buyer=account("USER");var seller=account("SELLER");var admin=account("ADMIN");
        mvc.perform(get("/seller").with(as(buyer))).andExpect(status().isForbidden());
        mvc.perform(get("/admin").with(as(seller))).andExpect(status().isForbidden());
        mvc.perform(get("/seller").with(as(admin))).andExpect(status().isForbidden());
        mvc.perform(get("/seller").with(as(seller))).andExpect(status().isOk()).andExpect(content().string(containsString("Киевская, 95")));
        mvc.perform(get("/admin").with(as(admin))).andExpect(status().isOk()).andExpect(view().name("admin"))
            .andExpect(content().string(not(containsString("PRIVATE_HASH"))));
        mvc.perform(get("/admin").param("role","SELLER").with(as(admin))).andExpect(status().isOk())
            .andExpect(content().string(containsString("seller@gmail.com"))).andExpect(content().string(not(containsString("user@gmail.com"))));
    }
    @Test void temporaryPasswordAllowsProfileAndAssetsButRedirectsDashboard() throws Exception {
        var seller=account("SELLER");seller.mustChangePassword=true;users.saveAndFlush(seller);
        mvc.perform(get("/seller").with(as(seller))).andExpect(redirectedUrl("/profile"));
        mvc.perform(get("/profile").with(as(seller))).andExpect(status().isOk()).andExpect(content().string(containsString("смените временный пароль")));
        mvc.perform(get("/css/site.css").with(as(seller))).andExpect(status().isOk());
    }
}
