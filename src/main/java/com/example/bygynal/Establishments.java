package com.example.bygynal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

/** ТЗ 4.3. Сведения о заведении: сотрудник заполняет их для своего заведения. */
@Service
public class Establishments {
    static final List<String> TYPES = List.of("Кафе", "Пекарня", "Ресторан", "Кондитерская");
    private final JdbcTemplate jdbc;
    private final Images images;

    public Establishments(JdbcTemplate jdbc, Images images) { this.jdbc = jdbc; this.images = images; }

    /** Данные формы. Все поля приходят строками, проверяем их сами, чтобы сообщения были на русском. */
    public record Form(String name, String type, String address, String phone, String openingHours, String description) {}

    public record View(UUID id, String name, String type, String address, String phone,
                       String openingHours, String description, UUID logoId) {
        /** Заполнены ли сведения, которые нужны покупателю на странице набора. */
        public boolean complete() { return type != null && phone != null && openingHours != null; }
    }

    public Optional<View> find(UUID id) {
        return jdbc.query("select id, name, place_type, address, phone, opening_hours, description, logo_id from establishments where id = ?",
            (rs, n) -> new View(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("place_type"),
                rs.getString("address"), rs.getString("phone"), rs.getString("opening_hours"),
                rs.getString("description"), rs.getObject("logo_id", UUID.class)), id).stream().findFirst();
    }

    /** Фото принадлежит заведению: это его логотип или фото одного из его предложений. */
    public boolean ownsImage(UUID establishmentId, UUID imageId) {
        Integer n = jdbc.queryForObject("""
            select count(*) from establishments e where e.id = ? and e.logo_id = ?""", Integer.class, establishmentId, imageId);
        if (n != null && n > 0) return true;
        n = jdbc.queryForObject("""
            select count(*) from offer_photos p join offers o on o.id = p.offer_id
            where o.establishment_id = ? and p.image_id = ?""", Integer.class, establishmentId, imageId);
        return n != null && n > 0;
    }

    @Transactional
    public void update(UUID id, Form f, MultipartFile logo, boolean removeLogo) {
        View current = find(id).orElseThrow(() -> new ApiError(404, "Заведение не найдено"));
        String name = required(f.name(), 160, "Укажите название заведения", "Название — не длиннее 160 символов");
        String address = required(f.address(), 300, "Укажите адрес", "Адрес — не длиннее 300 символов");
        String type = trim(f.type());
        if (type == null || !TYPES.contains(type)) throw new ApiError(400, "Выберите тип заведения");
        String phone = trim(f.phone());
        if (phone == null || !phone.matches("\\+?[0-9 ()-]{7,25}")) throw new ApiError(400, "Укажите корректный телефон");
        String hours = required(f.openingHours(), 100, "Укажите часы работы", "Часы работы — не длиннее 100 символов");
        String description = optional(f.description(), 1000, "Описание — не длиннее 1000 символов");

        UUID logoId = current.logoId();
        if (Images.present(logo)) logoId = images.save(logo);
        else if (removeLogo) logoId = null;

        jdbc.update("update establishments set name = ?, place_type = ?, address = ?, phone = ?, opening_hours = ?, description = ?, logo_id = ? where id = ?",
            name, type, address, phone, hours, description, logoId, id);
        // Старый логотип удаляем только после того, как заведение перестало на него ссылаться.
        if (current.logoId() != null && !current.logoId().equals(logoId)) images.delete(current.logoId());
    }

    static String trim(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    static String required(String value, int max, String emptyMessage, String longMessage) {
        String s = trim(value);
        if (s == null) throw new ApiError(400, emptyMessage);
        if (s.length() > max) throw new ApiError(400, longMessage);
        return s;
    }

    static String optional(String value, int max, String longMessage) {
        String s = trim(value);
        if (s != null && s.length() > max) throw new ApiError(400, longMessage);
        return s;
    }
}
