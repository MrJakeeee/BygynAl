package db.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.UUID;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class V2__bootstrap_admin extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        String email = normalizeEmail(System.getenv("ADMIN_EMAIL"));
        String password = System.getenv("ADMIN_PASSWORD");
        if (email == null && (password == null || password.isBlank())) {
            return;
        }
        if (email == null || password == null || password.length() < 12
                || !password.matches("(?s)(?=.*[A-Za-z])(?=.*[0-9]).*")) {
            throw new FlywayException("Set a Gmail ADMIN_EMAIL and an ADMIN_PASSWORD of at least 12 characters with letters and digits");
        }

        try (PreparedStatement exists = context.getConnection()
                .prepareStatement("select 1 from app_users where email = ?")) {
            exists.setString(1, email);
            try (ResultSet rows = exists.executeQuery()) {
                if (rows.next()) {
                    return;
                }
            }
        }

        try (PreparedStatement insert = context.getConnection().prepareStatement(
                "insert into app_users (id, email, name, phone, password_hash, role, blocked, two_factor, must_change_password, failed_logins, session_version) "
                        + "values (?, ?, ?, ?, ?, 'ADMIN', false, false, false, 0, 0)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, email);
            insert.setString(3, "Administrator");
            insert.setString(4, "not specified");
            insert.setString(5, new BCryptPasswordEncoder(12).encode(password));
            insert.executeUpdate();
        }
    }

    private String normalizeEmail(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String email = value.trim().toLowerCase(Locale.ROOT);
        if (!email.matches("[a-z0-9][a-z0-9.]{0,63}@gmail\\.com")) {
            return null;
        }
        return email.substring(0, email.indexOf('@')).replace(".", "") + "@gmail.com";
    }
}
