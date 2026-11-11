package com.example.bygynal;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CatalogService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private final JdbcTemplate jdbc;

    public CatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Offer> search(String query, String type, BigDecimal min, BigDecimal max,
                              LocalTime until, String sort) {
        StringBuilder sql = new StringBuilder("""
            select o.id, o.title, o.composition, o.price, o.original_price, o.remaining,
                   o.pickup_date, o.pickup_start, o.pickup_end,
                   e.name as establishment_name, e.address, e.place_type,
                   (select op.image_id from offer_photos op where op.offer_id = o.id
                    order by op.sort_order limit 1) as image_id
              from offers o
              join establishments e on e.id = o.establishment_id
             where o.withdrawn = false and o.remaining > 0 and o.pickup_date >= ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(LocalDate.now(java.time.ZoneId.of("Asia/Bishkek")));

        if (query != null && !query.isBlank()) {
            sql.append(" and lower(concat_ws(' ', o.title, o.composition, e.name, e.address)) like ? escape '\\'");
            args.add("%" + escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%");
        }
        if (type != null && !type.isBlank()) {
            sql.append(" and e.place_type = ?");
            args.add(type.trim());
        }
        if (min != null) {
            sql.append(" and o.price >= ?");
            args.add(min);
        }
        if (max != null) {
            sql.append(" and o.price <= ?");
            args.add(max);
        }
        if (until != null) {
            sql.append(" and o.pickup_start <= ?");
            args.add(until);
        }
        sql.append(switch (sort == null ? "time" : sort) {
            case "price" -> " order by o.price asc, o.pickup_date asc, o.pickup_start asc";
            case "discount" -> " order by ((o.original_price - o.price) / o.original_price) desc, o.pickup_date asc, o.pickup_start asc";
            default -> " order by o.pickup_date asc, o.pickup_start asc";
        });

        return jdbc.query(sql.toString(), this::mapOffer, args.toArray());
    }

    public List<String> categories() {
        return jdbc.queryForList("""
            select distinct e.place_type
              from offers o join establishments e on e.id = o.establishment_id
             where o.withdrawn = false and o.remaining > 0
               and o.pickup_date >= ? and e.place_type is not null and e.place_type <> ''
             order by e.place_type
            """, String.class, LocalDate.now(java.time.ZoneId.of("Asia/Bishkek")));
    }

    public Photo photo(UUID offerId) {
        return jdbc.query("""
            select i.content_type, i.data
              from offers o
              join offer_photos op on op.offer_id = o.id
              join images i on i.id = op.image_id
             where o.id = ?
             order by op.sort_order
             limit 1
            """, rs -> rs.next() ? new Photo(rs.getString("content_type"), rs.getBytes("data")) : null, offerId);
    }

    private Offer mapOffer(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID imageId = rs.getObject("image_id", UUID.class);
        return new Offer(id, rs.getString("title"),
            rs.getString("establishment_name") + " · " + rs.getString("address"),
            rs.getString("place_type"), rs.getString("composition"),
            rs.getBigDecimal("price"), rs.getBigDecimal("original_price"), rs.getInt("remaining"),
            rs.getObject("pickup_date", LocalDate.class),
            rs.getObject("pickup_start", LocalTime.class).format(TIME_FORMAT),
            rs.getObject("pickup_end", LocalTime.class).format(TIME_FORMAT),
            imageId == null ? null : "/api/catalog/" + id + "/image");
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record Offer(UUID id, String title, String place, String type, String composition,
                        BigDecimal price, BigDecimal original, int remaining, LocalDate pickupDate,
                        String start, String end, String imageUrl) {
        @JsonProperty("discount")
        public int discount() {
            return original.subtract(price).multiply(BigDecimal.valueOf(100))
                .divide(original, 0, RoundingMode.HALF_UP).intValue();
        }
    }

    public record Photo(String contentType, byte[] data) {}
}
