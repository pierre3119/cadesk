package io.github.pierre3119.cadesk.db;

import io.github.pierre3119.cadesk.domain.Rule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Accès aux règles de classement. Les méthodes s'exécutent sur la connexion d'une transaction de {@link Database}. */
public final class RuleRepository {

    /** Toutes les règles, de la plus prioritaire à la moins prioritaire. */
    public List<Rule> findAll(Connection c) throws SQLException {
        String sql = "SELECT id, category_id, poste, op_type, keywords, position FROM category_rule ORDER BY position, id";
        List<Rule> rules = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String keywords = rs.getString("keywords");
                rules.add(new Rule(rs.getLong("id"), rs.getLong("category_id"), rs.getString("poste"), rs.getString("op_type"),
                        keywords.isEmpty() ? List.of() : List.of(keywords.split("\n")), rs.getInt("position")));
            }
        }
        return rules;
    }

    /** Ajoute une règle en tête : elle passe avant toutes les autres. Renvoie la règle avec son identifiant. */
    public Rule insertFirst(Connection c, Rule rule) throws SQLException {
        int position = firstPosition(c) - 10;
        String sql = "INSERT INTO category_rule (category_id, poste, op_type, keywords, position) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, rule);
            ps.setInt(5, position);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Rule(keys.getLong(1), rule.categoryId(), rule.poste(), rule.operationType(), rule.keywords(), position);
            }
        }
    }

    /** Enregistre la catégorie, le poste, le type et les mots-clés d'une règle ; son rang ne change pas. */
    public void update(Connection c, Rule rule) throws SQLException {
        String sql = "UPDATE category_rule SET category_id = ?, poste = ?, op_type = ?, keywords = ? WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, rule);
            ps.setLong(5, rule.id());
            ps.executeUpdate();
        }
    }

    public void delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM category_rule WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Échange le rang de deux règles. */
    public void swapPositions(Connection c, long firstId, long secondId) throws SQLException {
        Positions.swap(c, "category_rule", firstId, secondId);
    }

    /** Place une règle avant toutes les autres. */
    public void moveFirst(Connection c, long id) throws SQLException {
        Positions.set(c, "category_rule", id, firstPosition(c) - 10);
    }

    private static int firstPosition(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MIN(position), 0) FROM category_rule")) {
            return rs.getInt(1);
        }
    }

    private static void bind(PreparedStatement ps, Rule rule) throws SQLException {
        ps.setLong(1, rule.categoryId());
        ps.setString(2, rule.poste());
        ps.setString(3, rule.operationType());
        ps.setString(4, String.join("\n", rule.keywords()));
    }
}
