package io.github.pierre3119.cadesk.db;

import io.github.pierre3119.cadesk.domain.Account;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Accès aux comptes. Les méthodes s'exécutent sur la connexion d'une transaction de {@link Database}. */
public final class AccountRepository {

    /**
     * Renvoie le compte portant ce numéro, en le créant à la première rencontre. Un compte créé par un relevé
     * qui ne nomme pas son titulaire (OFX) prend l'intitulé et le titulaire du premier relevé qui les donne.
     */
    public Account findOrCreate(Connection c, String number, String label, String holder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, number, label, holder FROM account WHERE number = ?")) {
            ps.setString(1, number);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Account known = map(rs);
                    return known.holder() == null && holder != null ? describe(c, known, label, holder) : known;
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO account (number, label, holder) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, number);
            ps.setString(2, label);
            ps.setString(3, holder);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Account(keys.getLong(1), number, label, holder);
            }
        }
    }

    private static Account describe(Connection c, Account account, String label, String holder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE account SET label = ?, holder = ? WHERE id = ?")) {
            ps.setString(1, label);
            ps.setString(2, holder);
            ps.setLong(3, account.id());
            ps.executeUpdate();
        }
        return new Account(account.id(), account.number(), label, holder);
    }

    /** Tous les comptes, celui du dernier import en premier. */
    public List<Account> findAll(Connection c) throws SQLException {
        String sql = """
                SELECT a.id, a.number, a.label, a.holder
                FROM account a
                ORDER BY (SELECT MAX(i.id) FROM statement_import i WHERE i.account_id = a.id) DESC, a.id
                """;
        List<Account> accounts = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                accounts.add(map(rs));
            }
        }
        return accounts;
    }

    private static Account map(ResultSet rs) throws SQLException {
        return new Account(rs.getLong("id"), rs.getString("number"), rs.getString("label"), rs.getString("holder"));
    }
}
