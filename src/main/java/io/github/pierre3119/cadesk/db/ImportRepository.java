package io.github.pierre3119.cadesk.db;

import io.github.pierre3119.cadesk.domain.ImportRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Accès à l'historique des imports. Les méthodes s'exécutent sur la connexion d'une transaction de {@link Database}. */
public final class ImportRepository {

    /** Enregistre l'import et renvoie la trace complète, identifiant compris. */
    public ImportRecord insert(Connection c, ImportRecord r, String fileSha256) throws SQLException {
        String sql = """
                INSERT INTO statement_import (account_id, file_name, file_sha256, imported_at, period_start, period_end,
                                              balance_cents, balance_date, rows_read, rows_new, rows_known, rows_updated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, r.accountId());
            ps.setString(2, r.fileName());
            ps.setString(3, fileSha256);
            ps.setString(4, r.importedAt().toString());
            ps.setString(5, text(r.periodStart()));
            ps.setString(6, text(r.periodEnd()));
            if (r.balanceCents() == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setLong(7, r.balanceCents());
            }
            ps.setString(8, text(r.balanceDate()));
            ps.setInt(9, r.rowsRead());
            ps.setInt(10, r.rowsNew());
            ps.setInt(11, r.rowsKnown());
            ps.setInt(12, r.rowsUpdated());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new ImportRecord(keys.getLong(1), r.accountId(), r.fileName(), r.importedAt(), r.periodStart(),
                        r.periodEnd(), r.balanceCents(), r.balanceDate(), r.rowsRead(), r.rowsNew(), r.rowsKnown(),
                        r.rowsUpdated());
            }
        }
    }

    /** Date du premier import de ce même fichier (même contenu) pour ce compte, s'il y en a eu un. */
    public Optional<LocalDateTime> firstImportOf(Connection c, long accountId, String fileSha256) throws SQLException {
        String sql = "SELECT MIN(imported_at) FROM statement_import WHERE account_id = ? AND file_sha256 = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setString(2, fileSha256);
            try (ResultSet rs = ps.executeQuery()) {
                String at = rs.next() ? rs.getString(1) : null;
                return Optional.ofNullable(at).map(LocalDateTime::parse);
            }
        }
    }

    /** Imports d'un compte, du plus récent au plus ancien. */
    public List<ImportRecord> findByAccount(Connection c, long accountId) throws SQLException {
        List<ImportRecord> imports = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM statement_import WHERE account_id = ? ORDER BY id DESC")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long balance = rs.getLong("balance_cents");
                    boolean noBalance = rs.wasNull();
                    imports.add(new ImportRecord(rs.getLong("id"), rs.getLong("account_id"), rs.getString("file_name"),
                            LocalDateTime.parse(rs.getString("imported_at")), date(rs.getString("period_start")),
                            date(rs.getString("period_end")), noBalance ? null : balance,
                            date(rs.getString("balance_date")), rs.getInt("rows_read"), rs.getInt("rows_new"),
                            rs.getInt("rows_known"), rs.getInt("rows_updated")));
                }
            }
        }
        return imports;
    }

    private static String text(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static LocalDate date(String s) {
        return s == null ? null : LocalDate.parse(s);
    }
}
