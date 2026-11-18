package io.github.pierre3119.cadesk.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/** Gestion de la colonne {@code position} qui ordonne catégories et règles. */
final class Positions {

    /** Les tables ordonnées. Un nom de table ne se passe pas en paramètre d'une requête : seuls ceux-ci y sont écrits. */
    private static final Set<String> TABLES = Set.of("category", "category_rule");

    private Positions() {
    }

    /** Échange le rang de deux lignes de {@code table} (nom de table interne, jamais saisi par l'utilisateur). */
    static void swap(Connection c, String table, long firstId, long secondId) throws SQLException {
        int first = position(c, table, firstId);
        int second = position(c, table, secondId);
        set(c, table, firstId, second);
        set(c, table, secondId, first);
    }

    static void set(Connection c, String table, long id, int position) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + known(table) + " SET position = ? WHERE id = ?")) {
            ps.setInt(1, position);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static int position(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT position FROM " + known(table) + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Ligne introuvable dans " + table + " : " + id);
                }
                return rs.getInt(1);
            }
        }
    }

    private static String known(String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("Table sans rang : " + table);
        }
        return table;
    }
}
