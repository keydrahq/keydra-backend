package io.keydra.engine;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a command answered, in terms no protocol owns.
 *
 * <p>RESP has a dozen reply types and another store would have its own; this is the shape both can
 * be described in, so the console renders results without knowing which store produced them.
 *
 * <p>Sealed for the same reason the value types are: a renderer that forgets a shape is a compile
 * error rather than a blank line on screen.
 */
@Schema(name = "ConsoleValue", description = "A command's reply")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ConsoleValue.Text.class, name = "text"),
    @JsonSubTypes.Type(value = ConsoleValue.Number.class, name = "number"),
    @JsonSubTypes.Type(value = ConsoleValue.Decimal.class, name = "decimal"),
    @JsonSubTypes.Type(value = ConsoleValue.Bool.class, name = "boolean"),
    @JsonSubTypes.Type(value = ConsoleValue.Failure.class, name = "error"),
    @JsonSubTypes.Type(value = ConsoleValue.Nil.class, name = "nil"),
    @JsonSubTypes.Type(value = ConsoleValue.Sequence.class, name = "sequence"),
})
public sealed interface ConsoleValue {

    /** A string reply, whether the protocol called it simple or bulk. */
    record Text(String value) implements ConsoleValue {}

    record Number(long value) implements ConsoleValue {}

    record Decimal(double value) implements ConsoleValue {}

    record Bool(boolean value) implements ConsoleValue {}

    /**
     * An error the store returned.
     *
     * <p>A reply, not an exception: a console that threw on {@code WRONGTYPE} would lose the
     * message the user asked for. The transport succeeded; the command did not.
     */
    record Failure(String message) implements ConsoleValue {}

    /** The absence of a value — RESP's nil, distinct from an empty string. */
    record Nil() implements ConsoleValue {}

    /** An ordered reply: an array, or a set whose order the store did not promise. */
    record Sequence(List<ConsoleValue> items) implements ConsoleValue {}
}
