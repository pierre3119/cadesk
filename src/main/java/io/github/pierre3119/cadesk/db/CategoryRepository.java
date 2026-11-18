package io.github.pierre3119.cadesk.db;

import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.Category.Role;
import io.github.pierre3119.cadesk.domain.Nature;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Accès aux catégories. Les méthodes s'exécutent sur la connexion d'une transaction de {@link Database}. */
public final class CategoryRepository {

    /** Toutes les catégories, dans l'ordre du bilan. */
    public List<Category> findAll(Connection c) throws SQLException {
        String sql = "SELECT id, name, nature, inflow, position, role, income_kind, saving_pct FROM category ORDER BY position, id";
        List<Category> categories = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                categories.add(new Category(rs.getLong("id"), rs.getString("name"), Nature.valueOf(rs.getString("nature")),
                        rs.getInt("inflow") == 1, rs.getInt("position"), Role.valueOf(rs.getString("role")),
                        IncomeKind.valueOf(rs.getString("income_kind")), rs.getInt("saving_pct")));
            }
        }
        return categories;
    }

    /** Ajoute une catégorie ordinaire à la fin du bilan et la renvoie avec son identifiant. */
    public Category insert(Connection c, Category category) throws SQLException {
        int position;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(position), 0) + 1 FROM category")) {
            position = rs.getInt(1);
        }
        String sql = "INSERT INTO category (name, nature, inflow, position, role, income_kind, saving_pct) VALUES (?, ?, ?, ?, 'STANDARD', ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, category.name().strip());
            ps.setString(2, category.nature().name());
            ps.setInt(3, category.inflow() ? 1 : 0);
            ps.setInt(4, position);
            ps.setString(5, category.incomeKind().name());
            ps.setInt(6, category.savingPercent());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Category(keys.getLong(1), category.name().strip(), category.nature(), category.inflow(), position,
                        Role.STANDARD, category.incomeKind(), category.savingPercent());
            }
        }
    }

    /**
     * Enregistre le nom, la nature, le sens et les réglages d'une catégorie ; son rôle et son rang ne changent pas.
     *
     * @throws IllegalArgumentException si le sens d'une catégorie par défaut serait inversé
     */
    public void update(Connection c, Category category) throws SQLException {
        if (category.role() == Role.DEFAULT_OUT && category.inflow() || category.role() == Role.DEFAULT_IN && !category.inflow()) {
            throw new IllegalArgumentException("Le sens d'une catégorie par défaut ne peut pas être inversé.");
        }
        String sql = "UPDATE category SET name = ?, nature = ?, inflow = ?, income_kind = ?, saving_pct = ? WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, category.name().strip());
            ps.setString(2, category.nature().name());
            ps.setInt(3, category.inflow() ? 1 : 0);
            ps.setString(4, category.incomeKind().name());
            ps.setInt(5, category.savingPercent());
            ps.setLong(6, category.id());
            ps.executeUpdate();
        }
    }

    public void setSavingPercent(Connection c, long id, int percent) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE category SET saving_pct = ? WHERE id = ?")) {
            ps.setInt(1, percent);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * Supprime une catégorie ordinaire. Ses règles disparaissent avec elle ; les opérations qui y avaient
     * été rangées à la main reviennent au classement automatique.
     *
     * @throws IllegalArgumentException pour une catégorie par défaut, qui ne se supprime pas
     */
    public void delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM category WHERE id = ? AND role = 'STANDARD'")) {
            ps.setLong(1, id);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("Cette catégorie ne peut pas être supprimée.");
            }
        }
    }

    /** Échange le rang de deux catégories dans le bilan. */
    public void swapPositions(Connection c, long firstId, long secondId) throws SQLException {
        Positions.swap(c, "category", firstId, secondId);
    }
}
