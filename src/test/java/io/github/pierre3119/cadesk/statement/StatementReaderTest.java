package io.github.pierre3119.cadesk.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pierre3119.cadesk.Samples;
import io.github.pierre3119.cadesk.domain.Statement;
import io.github.pierre3119.cadesk.domain.StatementLine;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class StatementReaderTest {

    private static Statement read(String text) {
        return StatementReader.read(text.getBytes(Samples.WINDOWS_1252));
    }

    @Test
    void theFormatIsRecognisedFromTheContent() {
        Statement csv = read(Samples.statement(Samples.BAKERY, Samples.SALARY));
        Statement ofx = read(Samples.ofxStatement(Samples.BAKERY_OFX, Samples.SALARY_OFX));

        assertThat(csv.lines()).hasSize(2).noneMatch(StatementLine::summary);
        assertThat(csv.holder()).isEqualTo("M. MARTIN JEAN");
        assertThat(ofx.lines()).hasSize(2).allMatch(StatementLine::summary);
        assertThat(ofx.accountNumber()).isEqualTo(csv.accountNumber());
    }

    @Test
    void aFileThatIsNeitherIsRefusedWithBothFormatsNamed() {
        assertThatThrownBy(() -> read("nom;prenom\nMartin;Jean\n")).isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("n'est pas un relevé du Crédit Agricole").hasMessageContaining("CSV").hasMessageContaining("OFX");
        assertThatThrownBy(() -> StatementReader.read(new byte[0])).isInstanceOf(StatementFormatException.class);
        assertThatThrownBy(() -> StatementReader.read("%PDF-1.7 \u00e9\u00e8".getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(StatementFormatException.class);
    }

    @Test
    void onlyStatementExtensionsAreOffered() {
        assertThat(StatementReader.hasStatementExtension("releve.csv")).isTrue();
        assertThat(StatementReader.hasStatementExtension("CA20260305_101500.ofx")).isTrue();
        assertThat(StatementReader.hasStatementExtension("RELEVE.OFX")).isTrue();
        assertThat(StatementReader.hasStatementExtension("releve.pdf")).isFalse();
        assertThat(StatementReader.hasStatementExtension("ofx")).isFalse();
    }
}
