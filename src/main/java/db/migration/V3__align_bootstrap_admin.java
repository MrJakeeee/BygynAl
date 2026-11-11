package db.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** One-time repair when bootstrap administrator values changed after V2 ran. */
public class V3__align_bootstrap_admin extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        String email = normalizeEmail(System.getenv("ADMIN_EMAIL"));
        String password = System.getenv("ADMIN_PASSWORD");
        if (email == null && (password == null || password.isBlank())) return;
        if (email == null || password == null || password.length() < 8) {
            throw new FlywayException("Set ADMIN_EMAIL and ADMIN_PASSWORD before repairing the administrator");
        }

        try (PreparedStatement target = context.getConnection().prepareStatement(
                "select id from app_users where email = ? and role = 'ADMIN'")) {
            target.setString(1, email);
            try (ResultSet rows = target.executeQuery()) {
                if (rows.next()) {
                    update(context, email, password, rows.getObject(1));
                    return;
                }
            }
        }

        try (PreparedStatement admins = context.getConnection().prepareStatement(
                "select id from app_users where role = 'ADMIN'")) {
            try (ResultSet rows = admins.executeQuery()) {
                if (!rows.next()) throw new FlywayException("No administrator account found to repair");
                Object id = rows.getObject(1);
                if (rows.next()) throw new FlywayException("More than one administrator exists; update the intended account manually");
                update(context, email, password, id);
            }
        }
    }

    private void update(Context context, String email, String password, Object id) throws Exception {
        try (PreparedStatement statement = context.getConnection().prepareStatement(
                "update app_users set email = ?, password_hash = ?, blocked = false, failed_logins = 0, locked_until = null, session_version = session_version + 1 where id = ?")) {
            statement.setString(1, email);
            statement.setString(2, new BCryptPasswordEncoder(12).encode(password));
            statement.setObject(3, id);
            statement.executeUpdate();
        }
    }

    private String normalizeEmail(String value) {
        if (value == null || value.isBlank()) return null;
        String email = value.trim().toLowerCase(Locale.ROOT);
        if (!email.matches("[a-z0-9][a-z0-9.]{0,63}@gmail\\.com")) return null;
        return email.substring(0, email.indexOf('@')).replace(".", "") + "@gmail.com";
    }
}
