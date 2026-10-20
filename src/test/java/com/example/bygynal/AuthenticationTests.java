package com.example.bygynal;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.time.Instant;
import java.util.regex.Pattern;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest @AutoConfigureMockMvc
class AuthenticationTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired Users users;
    @Autowired Challenges challenges; @Autowired PasswordEncoder encoder; @Autowired JdbcTemplate jdbc;
    @MockBean JavaMailSender mail;
    String code;
    final String email="buyer@gmail.com", password="Password123";
    @BeforeEach void setup() {
        jdbc.update("delete from admin_audit"); challenges.deleteAll(); users.deleteAll(); jdbc.update("delete from establishments");
        reset(mail);
        doAnswer(inv->{String text=((SimpleMailMessage)inv.getArgument(0)).getText();var m=Pattern.compile("Ваш код: ([0-9]{6})").matcher(text);if(m.find())code=m.group(1);return null;}).when(mail).send(any(SimpleMailMessage.class));
    }
    ResultActions postJson(String path,Object body,MockHttpSession session) throws Exception {
        var request=post("/api"+path).with(csrf()).contentType("application/json").content(json.writeValueAsString(body));
        if(session!=null)request.session(session);return mvc.perform(request);
    }
    JsonNode body(ResultActions r) throws Exception {return json.readTree(r.andReturn().getResponse().getContentAsString());}
    String register() throws Exception {
        return body(postJson("/auth/register",Map.of("name","Айжан","email",email,"phone","+996700123456","password",password),null).andExpect(status().isOk())).get("challengeId").asText();
    }
    UserAccount seed(String role) {
        UserAccount u=new UserAccount();u.email=email;u.name="Айжан";u.phone="+996700123456";u.passwordHash=encoder.encode(password);u.role=role;
        if(role.equals("SELLER")){u.establishmentId=UUID.randomUUID();jdbc.update("insert into establishments values (?, ?, ?)",u.establishmentId,"Кафе","Бишкек");}
        return users.saveAndFlush(u);
    }
    MockHttpSession login() throws Exception {
        return (MockHttpSession)postJson("/auth/login",Map.of("email",email,"password",password),null).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @Test void registrationRequiresCodeAndCannotReplay() throws Exception {
        String id=register();assertEquals(0,users.count());
        var payload=Map.of("challengeId",id,"code",code);
        postJson("/auth/register/verify",payload,null).andExpect(status().isOk());
        assertEquals(1,users.count());assertTrue(encoder.matches(password,users.findByEmail(email).orElseThrow().passwordHash));
        postJson("/auth/register/verify",payload,null).andExpect(status().isBadRequest());
        mvc.perform(get("/api/profile").session(login())).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("USER")).andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
    @Test void failedCodesPersistAndExhaustLimit() throws Exception {
        String id=register();String correct=code;
        for(int i=0;i<5;i++)postJson("/auth/register/verify",Map.of("challengeId",id,"code",correct.equals("000000")?"111111":"000000"),null).andExpect(status().isBadRequest());
        assertEquals(5,challenges.findById(UUID.fromString(id)).orElseThrow().attempts);
        postJson("/auth/register/verify",Map.of("challengeId",id,"code",correct),null).andExpect(status().isBadRequest());assertEquals(0,users.count());
    }
    @Test void fiveWrongPasswordsLockAccount() throws Exception {
        seed("USER");for(int i=0;i<5;i++)postJson("/auth/login",Map.of("email",email,"password","wrong"),null).andExpect(status().is(i==4?429:401));
        postJson("/auth/login",Map.of("email",email,"password",password),null).andExpect(status().isTooManyRequests());
        assertEquals(5,users.findByEmail(email).orElseThrow().failedLogins);
    }
    @Test void resetInvalidatesSessionsAndIsSingleUse() throws Exception {
        seed("USER");var session=login();String id=body(postJson("/auth/password/forgot",Map.of("email",email),null)).get("challengeId").asText();
        var payload=Map.of("challengeId",id,"code",code,"password","NewPassword123");
        postJson("/auth/password/reset",payload,null).andExpect(status().isOk());
        mvc.perform(get("/api/profile").session(session)).andExpect(status().isUnauthorized());
        postJson("/auth/password/reset",payload,null).andExpect(status().isBadRequest());
        postJson("/auth/login",Map.of("email",email,"password","NewPassword123"),null).andExpect(status().isOk());
    }
    @Test void optionalTwoFactorRequiresMailCode() throws Exception {
        seed("USER");var session=login();
        String id=body(postJson("/profile/two-factor",Map.of("password",password,"enabled",true),session)).get("challengeId").asText();
        postJson("/profile/two-factor/verify?enabled=true",Map.of("challengeId",id,"code",code),session).andExpect(status().isOk());
        assertTrue(users.findByEmail(email).orElseThrow().twoFactor);
        var result=postJson("/auth/login",Map.of("email",email,"password",password),null).andExpect(jsonPath("$.twoFactorRequired").value(true));
        id=body(result).at("/challenge/challengeId").asText();
        var anonymous=(MockHttpSession)result.andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/profile").session(anonymous==null?new MockHttpSession():anonymous)).andExpect(status().isUnauthorized());
        var verified=postJson("/auth/login/verify",Map.of("challengeId",id,"code",code),null).andExpect(status().isOk());
        var logged=(MockHttpSession)verified.andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/profile").session(logged)).andExpect(status().isOk());
        postJson("/auth/login/verify",Map.of("challengeId",id,"code",code),null).andExpect(status().isBadRequest());
    }
    @Test void rolesCsrfBlockingAndForcedPasswordChange() throws Exception {
        var u=seed("USER");var session=login();
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/seller/access").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/profile/password").session(session).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        u.blocked=true;u.sessionVersion++;users.saveAndFlush(u);
        mvc.perform(get("/api/profile").session(session)).andExpect(status().isUnauthorized());
        u.blocked=false;u.role="ADMIN";u.mustChangePassword=true;users.saveAndFlush(u);session=login();
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
        postJson("/profile/password",Map.of("currentPassword",password,"password","NewPassword123"),session).andExpect(status().isOk());
        assertFalse(users.findByEmail(email).orElseThrow().mustChangePassword);
    }
    @Test void expiredAndSupersededCodesFail() throws Exception {
        String id=register();String old=code;Challenge c=challenges.findById(UUID.fromString(id)).orElseThrow();c.expiresAt=Instant.now().minusSeconds(1);challenges.saveAndFlush(c);
        postJson("/auth/register/verify",Map.of("challengeId",id,"code",old),null).andExpect(status().isBadRequest());
        String next=register();old=code;String newest=register();
        postJson("/auth/register/verify",Map.of("challengeId",next,"code",old),null).andExpect(status().isBadRequest());
        postJson("/auth/register/verify",Map.of("challengeId",newest,"code",code),null).andExpect(status().isOk());
    }
    @Test void mailFailureDoesNotCreateRegistration() throws Exception {
        doThrow(new org.springframework.mail.MailSendException("unavailable")).when(mail).send(any(SimpleMailMessage.class));
        postJson("/auth/register",Map.of("name","Айжан","email",email,"phone","+996700123456","password",password),null).andExpect(status().isServiceUnavailable());
        assertEquals(0,users.count());assertEquals(0,challenges.count());
    }
    @Test void gmailAndPasswordValidation() throws Exception {
        postJson("/auth/register",Map.of("name","Айжан","email","x@example.com","phone","+996700123456","password",password),null).andExpect(status().isBadRequest());
        postJson("/auth/register",Map.of("name","Айжан","email",email,"phone","+996700123456","password","12345678"),null).andExpect(status().isBadRequest());
        assertEquals("firstlast@gmail.com",AuthService.email("First.Last@gmail.com"));
    }
    @Test void adminCreatesSellerWithEstablishmentAndMustChangePassword() throws Exception {
        var admin=seed("ADMIN");var session=login();
        postJson("/admin/users/"+admin.id+"/block",Map.of("blocked",true),session).andExpect(status().isMethodNotAllowed());
        mvc.perform(patch("/api/admin/users/"+admin.id+"/block").session(session).with(csrf()).contentType("application/json").content("{\"blocked\":true}")).andExpect(status().isBadRequest());
        String establishment=body(postJson("/admin/establishments",Map.of("name","Пекарня","address","Бишкек"),session).andExpect(status().isOk())).get("id").asText();
        postJson("/admin/sellers",Map.of("name","Сотрудник","email","seller@gmail.com","phone","+996700123456","establishmentId",establishment),session).andExpect(status().isOk());
        var seller=users.findByEmail("seller@gmail.com").orElseThrow();assertEquals("SELLER",seller.role);assertTrue(seller.mustChangePassword);assertEquals(establishment,seller.establishmentId.toString());
        var mails=org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail,atLeastOnce()).send(mails.capture());
        String text=mails.getAllValues().get(mails.getAllValues().size()-1).getText();var m=Pattern.compile("Временный пароль: (.+)").matcher(text);assertTrue(m.find());String temporary=m.group(1);
        var sellerLogin=postJson("/auth/login",Map.of("email","seller@gmail.com","password",temporary),null).andExpect(status().isOk());
        var sellerSession=(MockHttpSession)sellerLogin.andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/seller/access").session(sellerSession)).andExpect(status().isForbidden());
        postJson("/profile/password",Map.of("currentPassword",temporary,"password","SellerPassword123"),sellerSession).andExpect(status().isOk());
        assertFalse(users.findByEmail("seller@gmail.com").orElseThrow().mustChangePassword);
        assertEquals(2,jdbc.queryForObject("select count(*) from admin_audit",Integer.class));
    }
    @Test void changeEmailNeedsPasswordAndNewMailboxCode() throws Exception {
        seed("USER");var session=login();
        postJson("/profile/email",Map.of("email","newmail@gmail.com","password","wrong"),session).andExpect(status().isBadRequest());
        String id=body(postJson("/profile/email",Map.of("email","newmail@gmail.com","password",password),session)).get("challengeId").asText();
        assertTrue(users.findByEmail(email).isPresent());
        postJson("/profile/email/verify",Map.of("challengeId",id,"code",code),session).andExpect(status().isOk());
        assertTrue(users.findByEmail("newmail@gmail.com").isPresent());assertTrue(users.findByEmail(email).isEmpty());
    }
}
