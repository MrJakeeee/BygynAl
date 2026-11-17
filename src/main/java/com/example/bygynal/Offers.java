package com.example.bygynal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * ТЗ 4.4. Предложения заведения: размещение, изменение и снятие с продажи.
 *
 * В базе храним только признак «снято» (withdrawn). Остальные статусы вычисляем при чтении:
 * «Завершено» — окно выдачи уже прошло, «Распродано» — порций не осталось, иначе «Активно».
 * Поэтому для статуса «Завершено» не нужен фоновый процесс: он всегда соответствует текущему времени.
 */
@Service
public class Offers {
    static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");
    static final int MAX_PHOTOS = 3;
    static final BigDecimal MAX_PRICE = new BigDecimal("100000");

    private final JdbcTemplate jdbc;
    private final Images images;
    private final Clock clock;

    @Autowired
    public Offers(JdbcTemplate jdbc, Images images) { this(jdbc, images, Clock.system(BISHKEK)); }

    /** Для тестов: можно подставить «часы» с нужным временем. */
    Offers(JdbcTemplate jdbc, Images images, Clock clock) { this.jdbc = jdbc; this.images = images; this.clock = clock; }

    /** Поля формы приходят строками: так мы сами проверяем их и показываем понятные ошибки на русском. */
    public record Form(String title, String composition, String allergens, String originalPrice, String price,
                       String portions, String pickupDate, String pickupStart, String pickupEnd) {}

    public enum Status {
        ACTIVE("Активно"), SOLD_OUT("Распродано"), WITHDRAWN("Снято"), FINISHED("Завершено");
        public final String title;
        Status(String title) { this.title = title; }
    }

    public record View(UUID id, UUID establishmentId, String title, String composition, String allergens,
                       BigDecimal originalPrice, BigDecimal price, int totalPortions, int remaining,
                       LocalDate pickupDate, LocalTime pickupStart, LocalTime pickupEnd,
                       Status status, List<UUID> photoIds) {
        /** Продано порций: всё, что уже ушло из остатка (оплаченные и забронированные заказы). */
        public int sold() { return totalPortions - remaining; }
        /** Процент скидки считается автоматически (ТЗ 4.4.2), округляем вниз. */
        public int discount() {
            return originalPrice.subtract(price).multiply(BigDecimal.valueOf(100))
                .divide(originalPrice, 0, RoundingMode.DOWN).intValue();
        }
        public boolean editable() { return status == Status.ACTIVE || status == Status.SOLD_OUT; }
        public boolean priceAndTimeLocked() { return sold() > 0; }
    }

    // ---------- чтение ----------

    public List<View> forEstablishment(UUID establishmentId) {
        return jdbc.query("select * from offers where establishment_id = ? order by pickup_date desc, pickup_start desc",
            mapper(), establishmentId);
    }

    /** Предложение своего заведения. Чужое — как будто его нет (404), чтобы не раскрывать чужие данные. */
    public View get(UUID establishmentId, UUID offerId) {
        return jdbc.query("select * from offers where id = ? and establishment_id = ?", mapper(), offerId, establishmentId)
            .stream().findFirst().orElseThrow(() -> new ApiError(404, "Предложение не найдено"));
    }

    private RowMapper<View> mapper() {
        LocalDateTime now = LocalDateTime.now(clock);
        return (rs, n) -> {
            UUID id = rs.getObject("id", UUID.class);
            int remaining = rs.getInt("remaining");
            LocalDate date = rs.getObject("pickup_date", LocalDate.class);
            LocalTime end = rs.getObject("pickup_end", LocalTime.class);
            Status status = rs.getBoolean("withdrawn") ? Status.WITHDRAWN
                : !now.isBefore(LocalDateTime.of(date, end)) ? Status.FINISHED
                : remaining == 0 ? Status.SOLD_OUT : Status.ACTIVE;
            return new View(id, rs.getObject("establishment_id", UUID.class), rs.getString("title"),
                rs.getString("composition"), rs.getString("allergens"),
                rs.getBigDecimal("original_price"), rs.getBigDecimal("price"),
                rs.getInt("total_portions"), remaining, date, rs.getObject("pickup_start", LocalTime.class), end,
                status, photoIds(id));
        };
    }

    private List<UUID> photoIds(UUID offerId) {
        return jdbc.queryForList("select image_id from offer_photos where offer_id = ? order by sort_order", UUID.class, offerId);
    }

    // ---------- размещение (ТЗ 4.4.1–4.4.3) ----------

    @Transactional
    public UUID create(UUID establishmentId, Form f, List<MultipartFile> photos) {
        Checked c = check(f);
        checkStartTime(c);
        List<MultipartFile> files = present(photos);
        if (files.isEmpty()) throw new ApiError(400, "Добавьте хотя бы одно фото набора");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into offers (id, establishment_id, title, composition, allergens, original_price, price,
              total_portions, remaining, pickup_date, pickup_start, pickup_end, withdrawn, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?)""",
            id, establishmentId, c.title, c.composition, c.allergens, c.originalPrice, c.price,
            c.portions, c.portions, c.date, c.start, c.end, Timestamp.from(clock.instant()));
        savePhotos(id, files);
        return id;
    }

    // ---------- изменение (ТЗ 4.4.4–4.4.5) ----------

    @Transactional
    public void update(UUID establishmentId, UUID offerId, Form f, List<MultipartFile> photos) {
        lock(establishmentId, offerId);
        View old = get(establishmentId, offerId);
        if (!old.editable()) throw new ApiError(409, "Снятое или завершённое предложение изменить нельзя");
        Checked c = check(f);
        int sold = old.sold();
        if (sold > 0) {
            // После первой продажи покупатели уже заплатили по этой цене и рассчитывают на это время.
            boolean changed = c.price.compareTo(old.price()) != 0 || c.originalPrice.compareTo(old.originalPrice()) != 0
                || !c.date.equals(old.pickupDate()) || !c.start.equals(old.pickupStart()) || !c.end.equals(old.pickupEnd());
            if (changed) throw new ApiError(409, "После первой продажи нельзя менять цену и время выдачи");
            if (c.portions < sold) throw new ApiError(409, "Нельзя сделать порций меньше, чем уже продано: " + sold);
        } else if (!c.date.equals(old.pickupDate()) || !c.start.equals(old.pickupStart())) {
            checkStartTime(c);
        }
        jdbc.update("""
            update offers set title = ?, composition = ?, allergens = ?, original_price = ?, price = ?,
              total_portions = ?, remaining = ?, pickup_date = ?, pickup_start = ?, pickup_end = ?
            where id = ?""",
            c.title, c.composition, c.allergens, c.originalPrice, c.price,
            c.portions, c.portions - sold, c.date, c.start, c.end, offerId);
        List<MultipartFile> files = present(photos);
        if (!files.isEmpty()) {
            // Новые фото заменяют старые целиком.
            List<UUID> oldPhotos = photoIds(offerId);
            jdbc.update("delete from offer_photos where offer_id = ?", offerId);
            oldPhotos.forEach(images::delete);
            savePhotos(offerId, files);
        }
    }

    // ---------- снятие с продажи (ТЗ 4.4.6) ----------

    @Transactional
    public void withdraw(UUID establishmentId, UUID offerId) {
        lock(establishmentId, offerId);
        View old = get(establishmentId, offerId);
        if (old.status() == Status.WITHDRAWN) throw new ApiError(409, "Предложение уже снято с продажи");
        if (old.status() == Status.FINISHED) throw new ApiError(409, "Окно выдачи уже прошло");
        // Новые продажи останавливаются; уже оплаченные заказы остаются в силе и выдаются как обычно.
        jdbc.update("update offers set withdrawn = true where id = ?", offerId);
    }

    /**
     * Блокируем строку до конца транзакции. Позже бронирование будет уменьшать remaining,
     * и без блокировки изменение количества порций могло бы потерять чужую продажу.
     */
    private void lock(UUID establishmentId, UUID offerId) {
        List<UUID> found = jdbc.queryForList("select id from offers where id = ? and establishment_id = ? for update",
            UUID.class, offerId, establishmentId);
        if (found.isEmpty()) throw new ApiError(404, "Предложение не найдено");
    }

    private void savePhotos(UUID offerId, List<MultipartFile> files) {
        int order = 1; // в БД порядок фото — от 1 до 3
        for (MultipartFile file : files) {
            UUID imageId = images.save(file);
            jdbc.update("insert into offer_photos (offer_id, sort_order, image_id) values (?, ?, ?)", offerId, order++, imageId);
        }
    }

    private static List<MultipartFile> present(List<MultipartFile> photos) {
        List<MultipartFile> files = photos == null ? List.of() : photos.stream().filter(Images::present).toList();
        if (files.size() > MAX_PHOTOS) throw new ApiError(400, "Можно загрузить не больше 3 фото");
        return files;
    }

    // ---------- проверка формы ----------

    /** Проверенные и приведённые к нужным типам данные формы. */
    private record Checked(String title, String composition, String allergens, BigDecimal originalPrice,
                           BigDecimal price, int portions, LocalDate date, LocalTime start, LocalTime end) {}

    private Checked check(Form f) {
        String title = Establishments.required(f.title(), 120, "Укажите название набора", "Название — не длиннее 120 символов");
        String composition = Establishments.required(f.composition(), 1000, "Укажите состав набора", "Состав — не длиннее 1000 символов");
        String allergens = Establishments.optional(f.allergens(), 300, "Аллергены — не длиннее 300 символов");
        BigDecimal original = money(f.originalPrice(), "Укажите исходную цену");
        BigDecimal price = money(f.price(), "Укажите цену со скидкой");
        if (price.compareTo(original) >= 0) throw new ApiError(400, "Цена со скидкой должна быть ниже исходной цены");
        int portions;
        try { portions = Integer.parseInt(Objects.requireNonNullElse(f.portions(), "").trim()); }
        catch (NumberFormatException e) { throw new ApiError(400, "Укажите количество порций числом"); }
        if (portions < 1 || portions > 100) throw new ApiError(400, "Количество порций — от 1 до 100");
        LocalDate date; LocalTime start, end;
        try {
            date = LocalDate.parse(Objects.requireNonNullElse(f.pickupDate(), "").trim());
            start = LocalTime.parse(Objects.requireNonNullElse(f.pickupStart(), "").trim());
            end = LocalTime.parse(Objects.requireNonNullElse(f.pickupEnd(), "").trim());
        } catch (DateTimeParseException e) { throw new ApiError(400, "Укажите дату и время выдачи"); }
        // Окно выдачи — в пределах одного дня, от 30 минут до 4 часов (ТЗ 4.4.3).
        if (!end.isAfter(start)) throw new ApiError(400, "Конец выдачи должен быть позже начала в тот же день");
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < 30 || minutes > 240) throw new ApiError(400, "Окно выдачи — от 30 минут до 4 часов");
        return new Checked(title, composition, allergens, original, price, portions, date, start, end);
    }

    /** Начало выдачи — не раньше чем через 30 минут после размещения (ТЗ 4.4.3). */
    private void checkStartTime(Checked c) {
        if (LocalDateTime.of(c.date, c.start).isBefore(LocalDateTime.now(clock).plusMinutes(30)))
            throw new ApiError(400, "Начало выдачи — не раньше чем через 30 минут");
    }

    /** Сумма в сомах: больше нуля, не больше двух знаков после запятой. Запятую тоже принимаем. */
    private static BigDecimal money(String value, String emptyMessage) {
        String s = Establishments.trim(value);
        if (s == null) throw new ApiError(400, emptyMessage);
        BigDecimal v;
        try { v = new BigDecimal(s.replace(',', '.').replace(" ", "")); }
        catch (NumberFormatException e) { throw new ApiError(400, "Цена должна быть числом"); }
        if (v.signum() <= 0 || v.compareTo(MAX_PRICE) > 0) throw new ApiError(400, "Цена — от 0,01 до 100 000 сом");
        if (v.stripTrailingZeros().scale() > 2) throw new ApiError(400, "Цена — не больше двух знаков после запятой");
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }
}
