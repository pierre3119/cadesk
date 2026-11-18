package io.github.pierre3119.cadesk.db;

import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.StatementLine;
import io.github.pierre3119.cadesk.importer.Reconciler.Update;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Accès aux opérations. Les méthodes s'exécutent sur la connexion d'une transaction de {@link Database}. */
public final class OperationRepository {

    private static final String COLUMNS = "id, op_date, value_date, type, label, amount_cents, category_id, category_source";
    private static final String ASSISTANT = "ASSISTANT";
    private static final String MANUAL = "MANUAL";

    /** Toutes les opérations d'un compte, de la plus récente à la plus ancienne. */
    public List<Operation> findByAccount(Connection c, long accountId) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM operation WHERE account_id = ? ORDER BY op_date DESC, id DESC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            return list(ps);
        }
    }

    /** Opérations d'un compte dont la date d'opération est dans l'intervalle, bornes comprises. */
    public List<Operation> findBetween(Connection c, long accountId, LocalDate from, LocalDate to) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM operation WHERE account_id = ? AND op_date BETWEEN ? AND ? ORDER BY id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            return list(ps);
        }
    }

    /** Identifiants des opérations qu'un import a ajoutées. */
    public List<Long> findIdsByImport(Connection c, long importId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM operation WHERE import_id = ? ORDER BY id")) {
            ps.setLong(1, importId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** Enregistre les lignes d'un relevé ; faute de date de valeur (relevé OFX), la date d'opération en tient lieu. */
    public void insertAll(Connection c, long accountId, long importId, List<StatementLine> lines) throws SQLException {
        String sql = "INSERT INTO operation (account_id, op_date, value_date, type, label, amount_cents, import_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (StatementLine l : lines) {
                ps.setLong(1, accountId);
                ps.setString(2, l.date().toString());
                ps.setString(3, (l.valueDate() != null ? l.valueDate() : l.date()).toString());
                ps.setString(4, l.type());
                ps.setString(5, l.label());
                ps.setLong(6, l.amountCents());
                ps.setLong(7, importId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Donne à des opérations connues le libellé et la date de valeur qu'un relevé plus récent leur attribue. */
    public void updateAll(Connection c, long importId, List<Update> updates) throws SQLException {
        String sql = "UPDATE operation SET label = ?, value_date = ?, updated_import_id = ? WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Update u : updates) {
                ps.setString(1, u.newLabel());
                ps.setString(2, u.newValueDate().toString());
                ps.setLong(3, importId);
                ps.setLong(4, u.operationId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static List<Operation> list(PreparedStatement ps) throws SQLException {
        List<Operation> operations = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long categoryId = rs.getLong("category_id");
                Long manualCategoryId = rs.wasNull() ? null : categoryId;
                operations.add(new Operation(rs.getLong("id"), LocalDate.parse(rs.getString("op_date")),
                        LocalDate.parse(rs.getString("value_date")), rs.getString("type"), rs.getString("label"),
                        rs.getLong("amount_cents"), manualCategoryId,
                        manualCategoryId != null && ASSISTANT.equals(rs.getString("category_source"))));
            }
        }
        return operations;
    }

    /**
     * Fixe la catégorie choisie à la main pour ces opérations ; {@code null} rend la main au classement
     * automatique.
     */
    public void setCategory(Connection c, Collection<Long> operationIds, Long categoryId) throws SQLException {
        setCategory(c, operationIds, categoryId, false);
    }

    /**
     * Enregistre une catégorie avec ces opérations : elle passe avant les règles.
     *
     * @param categoryId la catégorie, ou {@code null} pour rendre la main au classement par les règles
     * @param assisted   vrai si la catégorie vient de l'assistant, faux si elle est choisie à la main
     */
    public void setCategory(Connection c, Collection<Long> operationIds, Long categoryId, boolean assisted) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE operation SET category_id = ?, category_source = ? WHERE id = ?")) {
            for (Long id : operationIds) {
                ps.setObject(1, categoryId);
                ps.setString(2, assisted && categoryId != null ? ASSISTANT : MANUAL);
                ps.setLong(3, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
