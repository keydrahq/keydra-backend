package io.keydra.connections.persistence;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class EncryptedStringConverterTest {

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    @ParameterizedTest
    @ValueSource(strings = {"hunter2", "p@ss:w/rd?#", "üğşçöİ"})
    void roundTripsAnyValue(String plaintext) {
        String stored = converter.convertToDatabaseColumn(plaintext);

        assertThat(stored, startsWith(EncryptedStringConverter.PREFIX));
        assertThat(stored, not(containsString(plaintext)));
        assertThat(converter.convertToEntityAttribute(stored), equalTo(plaintext));
    }

    @Test
    void roundTripsAValueLongEnoughToSpanTheColumn() {
        // The column is 2048 chars; a 400-char secret still fits once base64-encoded.
        String plaintext = "a".repeat(400);

        String stored = converter.convertToDatabaseColumn(plaintext);

        assertThat(stored.length(), lessThanOrEqualTo(2048));
        assertThat(converter.convertToEntityAttribute(stored), equalTo(plaintext));
    }

    @Test
    void producesADifferentCiphertextEachTime() {
        // A fresh IV per encryption means equal passwords do not produce equal
        // ciphertexts, so the column cannot be used to spot shared secrets.
        String first = converter.convertToDatabaseColumn("same-password");
        String second = converter.convertToDatabaseColumn("same-password");

        assertNotEquals(first, second);
        assertThat(converter.convertToEntityAttribute(first), equalTo("same-password"));
        assertThat(converter.convertToEntityAttribute(second), equalTo("same-password"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void passesThroughNullAndEmpty(String value) {
        assertThat(converter.convertToDatabaseColumn(value), equalTo(value));
        assertThat(converter.convertToEntityAttribute(value), equalTo(value));
    }

    @Test
    void leavesUnprefixedLegacyValuesAlone() {
        // A column written before encryption existed must not be mangled on read.
        assertThat(converter.convertToEntityAttribute("plaintext"), equalTo("plaintext"));
    }
}
