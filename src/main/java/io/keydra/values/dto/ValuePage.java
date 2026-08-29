package io.keydra.values.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One slice of a key's value.
 *
 * <p>Sealed rather than one interface per type: a caller must handle every shape, and a new type
 * added to an engine becomes a compile error at each place that reads values instead of a silent
 * fall-through.
 *
 * <p>Every variant carries {@code cursor} — the position to resume from, or null when the value has
 * been read to its end — and {@code total} where the store can report one cheaply.
 */
@Schema(name = "ValuePage", description = "A slice of a key's value")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ValuePage.StringPage.class, name = "string"),
    @JsonSubTypes.Type(value = ValuePage.HashPage.class, name = "hash"),
    @JsonSubTypes.Type(value = ValuePage.ListPage.class, name = "list"),
    @JsonSubTypes.Type(value = ValuePage.SetPage.class, name = "set"),
    @JsonSubTypes.Type(value = ValuePage.ZSetPage.class, name = "zset"),
    @JsonSubTypes.Type(value = ValuePage.StreamPage.class, name = "stream"),
})
public sealed interface ValuePage {

    /** Where to resume, or null when there is nothing more to read. */
    String cursor();

    /** Elements in the whole value, or null when the store cannot say cheaply. */
    Long total();

    /** A string value, already decoded. */
    record StringPage(EncodedValue value) implements ValuePage {
        @Override
        public String cursor() {
            // A string arrives whole; there is never a second page.
            return null;
        }

        @Override
        public Long total() {
            // A string is one value, not a collection, so there is nothing to count.
            // Its size in bytes is on the value itself.
            return null;
        }
    }

    /** Hash fields. */
    record HashPage(List<Field> fields, String cursor, Long total) implements ValuePage {
        public record Field(String name, EncodedValue value) {}
    }

    /** List elements, in index order. */
    record ListPage(List<Element> elements, String cursor, Long total) implements ValuePage {
        public record Element(long index, EncodedValue value) {}
    }

    /** Set members, which have no order. */
    record SetPage(List<EncodedValue> members, String cursor, Long total) implements ValuePage {}

    /** Sorted-set members with their scores. */
    record ZSetPage(List<Member> members, String cursor, Long total) implements ValuePage {
        public record Member(EncodedValue value, double score) {}
    }

    /** Stream entries with their fields. */
    record StreamPage(List<Entry> entries, String cursor, Long total) implements ValuePage {
        public record Entry(String id, List<HashPage.Field> fields) {}
    }
}
