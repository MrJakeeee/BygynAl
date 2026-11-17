package com.example.bygynal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Хранение фотографий (логотип заведения, фото наборов) в таблице images.
 * Принимаем только JPG и PNG до 5 МБ (ТЗ 4.4.1). Тип файла проверяем по первым байтам,
 * а не по расширению или заголовку браузера, чтобы под видом картинки нельзя было загрузить другой файл.
 */
@Service
public class Images {
    static final long MAX_SIZE = 5L * 1024 * 1024;
    private final JdbcTemplate jdbc;

    public Images(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Image(String contentType, byte[] data) {}

    /** Пустой файл (поле формы без выбранного файла) не считаем фотографией. */
    static boolean present(MultipartFile file) { return file != null && !file.isEmpty(); }

    public UUID save(MultipartFile file) {
        if (file.getSize() > MAX_SIZE) throw new ApiError(400, "Фото должно быть не больше 5 МБ");
        byte[] data;
        try { data = file.getBytes(); }
        catch (IOException e) { throw new ApiError(400, "Не удалось прочитать файл"); }
        String type = detectType(data);
        if (type == null) throw new ApiError(400, "Можно загрузить только фото в формате JPG или PNG");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into images (id, content_type, data, created_at) values (?, ?, ?, ?)",
            id, type, data, Timestamp.from(Instant.now()));
        return id;
    }

    public Optional<Image> find(UUID id) {
        return jdbc.query("select content_type, data from images where id = ?",
            (rs, n) -> new Image(rs.getString("content_type"), rs.getBytes("data")), id).stream().findFirst();
    }

    public void delete(UUID id) {
        if (id != null) jdbc.update("delete from images where id = ?", id);
    }

    /** JPG начинается с байтов FF D8 FF, PNG — с 89 50 4E 47. */
    static String detectType(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (d.length >= 4 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        return null;
    }
}
