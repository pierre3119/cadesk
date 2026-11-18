package io.github.pierre3119.cadesk.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Préférences de l'application (clé / valeur). */
public final class SettingsRepository {

    public Optional<String> get(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM setting WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public void put(Connection c, String name, String value) throws SQLException {
        String sql = "INSERT INTO setting (name, value) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET value = excluded.value";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
