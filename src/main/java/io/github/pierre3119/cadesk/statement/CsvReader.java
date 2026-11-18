package io.github.pierre3119.cadesk.statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Lecteur CSV minimal (RFC 4180) : champs entre guillemets pouvant contenir le séparateur, des sauts de
 * ligne et des guillemets doublés. Suffisant pour les exports bancaires, sans dépendance externe.
 */
final class CsvReader {

    /** Un enregistrement et le numéro (à partir de 1) de la ligne du fichier où il commence. */
    record Row(int line, List<String> fields) {

        boolean isBlank() {
            return fields.stream().allMatch(String::isBlank);
        }
    }

    private CsvReader() {
    }

    static List<Row> read(String text, char separator, int firstLineNumber) {
        List<Row> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = firstLineNumber;
        int rowLine = firstLineNumber;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == separator) {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                fields.add(field.toString());
                field.setLength(0);
                rows.add(new Row(rowLine, List.copyOf(fields)));
                fields.clear();
                line++;
                rowLine = line;
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (quoted) {
            throw new StatementFormatException("Guillemet non refermé dans le fichier (ligne " + rowLine + ").");
        }
        if (!field.isEmpty() || !fields.isEmpty()) {
            fields.add(field.toString());
            rows.add(new Row(rowLine, List.copyOf(fields)));
        }
        return rows;
    }
}
