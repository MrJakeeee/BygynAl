package com.example.bygynal;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Сведения о заведении (ТЗ 4.3) и предложения заведения (ТЗ 4.4). */
@SpringBootTest @AutoConfigureMockMvc
class SellerTests {
    @Autowired MockMvc mvc; @Autowired Users users; @Autowired Challenges challenges; @Autowired JdbcTemplate jdbc;

    /** Минимальные байты, по которым сервер узнаёт PNG. */
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};
    static final String TOMORROW = LocalDate.now(ZoneId.of("Asia/Bishkek")).plusDays(1).toString();

    @BeforeEach @AfterEach void clear() {
        // Порядок важен: сначала строки, которые ссылаются на другие таблицы.
        jdbc.update("delete from offer_photos"); jdbc.update("delete from offers");
        jdbc.update("update establishments set logo_id = null"); jdbc.update("delete from images");
        jdbc.update("delete from admin_audit"); challenges.deleteAll(); users.deleteAll(); jdbc.update("delete from establishments");
    }

    UserAccount account(String role, String email) {
        var u = new UserAccount(); u.name = "Айбек"; u.email = email; u.phone = "+996700123456"; u.passwordHash = "x"; u.role = role;
        if (role.equals("SELLER")) {
            u.establishmentId = UUID.randomUUID();
            jdbc.update("insert into establishments (id, name, address) values (?, ?, ?)", u.establishmentId, "Пекарня «Тандыр»", "ул. Киевская, 95");
        }
        return users.saveAndFlush(u);
    }

    RequestPostProcessor as(UserAccount u) {
        return authentication(new UsernamePasswordAuthenticationToken(new SecurityConfig.Principal(u.id, u.sessionVersion), null,
            List.of(new SimpleGrantedAuthority("ROLE_" + u.role))));
    }

    static MockMultipartFile png(String field) { return new MockMultipartFile(field, "photo.png", "image/png", PNG); }

    MockMultipartHttpServletRequestBuilder form(String path, UserAccount u, Map<String, String> fields, MockMultipartFile... files) {
        var b = multipart(path);
        for (var f : files) b.file(f);
        fields.forEach(b::param);
        b.with(as(u)).with(csrf());
        return b;
    }

    static Map<String, String> offer() {
        var m = new HashMap<String, String>();
        m.put("title", "Набор выпечки на вечер"); m.put("composition", "2 самсы, 3 булочки, 1 лепёшка");
        m.put("originalPrice", "500"); m.put("price", "250"); m.put("portions", "5");
        m.put("pickupDate", TOMORROW); m.put("pickupStart", "19:00"); m.put("pickupEnd", "21:00");
        return m;
    }

    static Map<String, String> with(Map<String, String> base, String key, String value) {
        var m = new HashMap<>(base); m.put(key, value); return m;
    }

    UUID createOffer(UserAccount seller) throws Exception {
        String body = mvc.perform(form("/api/seller/offers", seller, offer(), png("photos")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    }

    // ---------- ТЗ 4.3 ----------

    @Test void sellerSavesEstablishmentDetailsWithLogo() throws Exception {
        var seller = account("SELLER", "seller@gmail.com");
        Map<String, String> place = Map.of("name", "Пекарня «Тандыр»", "type", "Пекарня", "address", "ул. Киевская, 95",
            "phone", "+996 555 34 88 12", "openingHours", "Ежедневно 08:00–22:00", "description", "Тандырная выпечка");
        mvc.perform(form("/api/seller/establishment", seller, place, png("logo")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Сведения сохранены"));
        var row = jdbc.queryForMap("select * from establishments where id = ?", seller.establishmentId);
        assertEquals("Пекарня", row.get("place_type"));
        assertEquals("Ежедневно 08:00–22:00", row.get("opening_hours"));
        String logo = "/api/seller/images/" + row.get("logo_id");
        mvc.perform(get(logo).with(as(seller))).andExpect(status().isOk())
            .andExpect(content().contentType("image/png"));
        // Чужой сотрудник не видит логотип другого заведения, покупатель — кабинет заведения.
        mvc.perform(get(logo).with(as(account("SELLER", "other@gmail.com")))).andExpect(status().isNotFound());
        mvc.perform(get(logo).with(as(account("USER", "user@gmail.com")))).andExpect(status().isForbidden());
        mvc.perform(get("/seller").with(as(seller))).andExpect(status().isOk())
            .andExpect(content().string(containsString("Ежедневно 08:00–22:00")));
    }

    @Test void establishmentFormIsValidated() throws Exception {
        var seller = account("SELLER", "seller@gmail.com");
        Map<String, String> place = Map.of("name", "Пекарня", "type", "Столовая", "address", "Бишкек", "phone", "+996555348812", "openingHours", "08:00–22:00");
        mvc.perform(form("/api/seller/establishment", seller, place)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Выберите тип заведения"));
        var notImage = new MockMultipartFile("logo", "logo.png", "image/png", "это не картинка".getBytes());
        mvc.perform(form("/api/seller/establishment", seller, with(place, "type", "Пекарня"), notImage)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Можно загрузить только фото в формате JPG или PNG"));
    }

    @Test void onlySellerUsesSellerApiAndPages() throws Exception {
        var buyer = account("USER", "user@gmail.com");
        mvc.perform(get("/api/seller/offers").with(as(buyer))).andExpect(status().isForbidden());
        mvc.perform(get("/seller/offers/new").with(as(buyer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/seller/offers")).andExpect(status().isUnauthorized());
    }

    @Test void sellerPagesRender() throws Exception {
        var seller = account("SELLER", "seller@gmail.com");
        mvc.perform(get("/seller/establishment").with(as(seller))).andExpect(status().isOk())
            .andExpect(view().name("seller-establishment"))
            .andExpect(content().string(containsString("ул. Киевская, 95")))
            .andExpect(content().string(containsString("Кондитерская")));
        mvc.perform(get("/seller/offers/new").with(as(seller))).andExpect(status().isOk())
            .andExpect(view().name("seller-offer"))
            .andExpect(content().string(containsString("Опубликовать предложение")));
        mvc.perform(get("/seller").with(as(seller))).andExpect(status().isOk())
            .andExpect(content().string(containsString("Здесь будут ваши предложения")))
            .andExpect(content().string(containsString("Заполните сведения о заведении")));
        mvc.perform(get("/seller/offers/" + UUID.randomUUID()).with(as(seller))).andExpect(status().isNotFound());
    }

    // ---------- ТЗ 4.4 ----------

    @Test void sellerPublishesOfferAndSeesItInCabinet() throws Exception {
        var seller = account("SELLER", "seller@gmail.com");
        UUID id = createOffer(seller);
        var row = jdbc.queryForMap("select * from offers where id = ?", id);
        assertEquals(5, row.get("remaining"));
        assertEquals(1, jdbc.queryForObject("select count(*) from offer_photos where offer_id = ?", Integer.class, id));
        mvc.perform(get("/seller").with(as(seller))).andExpect(status().isOk())
            .andExpect(content().string(containsString("Набор выпечки на вечер")))
            .andExpect(content().string(containsString("5 из 5")))
            .andExpect(content().string(containsString("−50%")))
            .andExpect(content().string(containsString("Активно")));
        mvc.perform(get("/seller/offers/" + id).with(as(seller))).andExpect(status().isOk()).andExpect(view().name("seller-offer"));
    }

    @Test void offerRulesAreCheckedOnServer() throws Exception {
        var seller = account("SELLER", "seller@gmail.com");
        Map<String, Map<String, String>> forms = new LinkedHashMap<>();
        forms.put("Цена со скидкой должна быть ниже исходной цены", with(offer(), "price", "500"));
        forms.put("Количество порций — от 1 до 100", with(offer(), "portions", "0"));
        forms.put("Окно выдачи — от 30 минут до 4 часов", with(offer(), "pickupEnd", "19:20"));
        forms.put("Конец выдачи должен быть позже начала в тот же день", with(offer(), "pickupEnd", "18:00"));
        forms.put("Начало выдачи — не раньше чем через 30 минут", with(offer(), "pickupDate", LocalDate.now(ZoneId.of("Asia/Bishkek")).minusDays(1).toString()));
        forms.put("Цена — не больше двух знаков после запятой", with(offer(), "price", "249.999"));
        for (var entry : forms.entrySet())
            mvc.perform(form("/api/seller/offers", seller, entry.getValue(), png("photos")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(entry.getKey()));
        mvc.perform(form("/api/seller/offers", seller, offer())).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Добавьте хотя бы одно фото набора"));
        mvc.perform(form("/api/seller/offers", seller, offer(), png("photos"), png("photos"), png("photos"), png("photos")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Можно загрузить не больше 3 фото"));
        assertEquals(0, jdbc.queryForObject("select count(*) from offers", Integer.class));
    }

    @Test void sellerCannotTouchOtherEstablishmentOffer() throws Exception {
        var owner = account("SELLER", "owner@gmail.com");
        var other = account("SELLER", "other@gmail.com");
        UUID id = createOffer(owner);
        mvc.perform(get("/api/seller/offers/" + id).with(as(other))).andExpect(status().isNotFound());
        mvc.perform(form("/api/seller/offers/" + id, other, offer())).andExpect(status().isNotFound());
        mvc.perform(post("/api/seller/offers/" + id + "/withdraw").with(as(other)).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void afterFirstSalePriceAndTimeAreLocked() throws Exception {
        var seller = account("SELLER", "seller@gmail.com");
        UUID id = createOffer(seller);
        jdbc.update("update offers set remaining = 3 where id = ?", id); // продано 2 порции
        mvc.perform(form("/api/seller/offers/" + id, seller, with(offer(), "price", "200"))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("После первой продажи нельзя менять цену и время выдачи"));
        mvc.perform(form("/api/seller/offers/" + id, seller, with(offer(), "pickupEnd", "20:30"))).andExpect(status().isConflict());
        mvc.perform(form("/api/seller/offers/" + id, seller, with(offer(), "portions", "1"))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Нельзя сделать порций меньше, чем уже продано: 2"));
        var changed = with(with(offer(), "portions", "8"), "composition", "2 самсы, 4 булочки");
        mvc.perform(form("/api/seller/offers/" + id, seller, changed)).andExpect(status().isOk());
        mvc.perform(get("/seller/offers/" + id).with(as(seller))).andExpect(status().isOk())
            .andExpect(content().string(containsString("Уже продано порций: 2")));
        var row = jdbc.queryForMap("select * from offers where id = ?", id);
        assertEquals(8, row.get("total_portions"));
        assertEquals(6, row.get("remaining"));
        assertEquals("2 самсы, 4 булочки", row.get("composition"));
    }

    @Test void beforeFirstSaleEverythingCanChange() throws Exception {
        var seller = account("SELLER", "seller@gmail.com");
        UUID id = createOffer(seller);
        var changed = with(with(with(offer(), "price", "199.50"), "pickupStart", "18:00"), "portions", "3");
        mvc.perform(form("/api/seller/offers/" + id, seller, changed, png("photos"), png("photos"))).andExpect(status().isOk());
        var row = jdbc.queryForMap("select * from offers where id = ?", id);
        assertEquals(0, new java.math.BigDecimal("199.50").compareTo((java.math.BigDecimal) row.get("price")));
        assertEquals(3, row.get("remaining"));
        assertEquals(2, jdbc.queryForObject("select count(*) from offer_photos where offer_id = ?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("select count(*) from images", Integer.class)); // старое фото удалено
    }

    @Test void publishedOfferAppearsInCatalogWithPhotoAndDisappearsAfterWithdraw() throws Exception {
        var seller = account("SELLER", "seller@gmail.com");
        Map<String, String> place = Map.of("name", "Пекарня «Тандыр»", "type", "Пекарня", "address", "ул. Киевская, 95",
            "phone", "+996555348812", "openingHours", "08:00–22:00");
        mvc.perform(form("/api/seller/establishment", seller, place)).andExpect(status().isOk());
        UUID id = createOffer(seller);
        mvc.perform(get("/api/catalog")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("Набор выпечки на вечер"))
            .andExpect(jsonPath("$[0].type").value("Пекарня"))
            .andExpect(jsonPath("$[0].imageUrl").value("/api/catalog/" + id + "/image"));
        mvc.perform(get("/api/catalog/" + id + "/image")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/catalog/types")).andExpect(jsonPath("$[0]").value("Пекарня"));
        mvc.perform(post("/api/seller/offers/" + id + "/withdraw").with(as(seller)).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/catalog")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test void withdrawnOfferStopsSalesAndCannotBeEdited() throws Exception {
        var seller = account("SELLER", "seller@gmail.com");
        UUID id = createOffer(seller);
        mvc.perform(post("/api/seller/offers/" + id + "/withdraw").with(as(seller)).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/seller/offers/" + id + "/withdraw").with(as(seller)).with(csrf())).andExpect(status().isConflict());
        mvc.perform(form("/api/seller/offers/" + id, seller, offer())).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Снятое или завершённое предложение изменить нельзя"));
        mvc.perform(get("/seller").with(as(seller))).andExpect(content().string(containsString("Снято")));
    }
}
