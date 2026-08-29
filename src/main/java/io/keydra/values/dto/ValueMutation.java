package io.keydra.values.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A change to one key's value.
 *
 * <p>Sealed for the same reason {@link ValuePage} is: an engine must handle every operation, and a
 * new one becomes a compile error rather than a silently ignored request.
 *
 * <p>Each variant names the key it acts on, so an engine needs nothing else to carry out the work.
 */
@Schema(name = "ValueMutation", description = "A change to a key's value")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "operation")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ValueMutation.SetString.class, name = "setString"),
    @JsonSubTypes.Type(value = ValueMutation.SetHashField.class, name = "setHashField"),
    @JsonSubTypes.Type(value = ValueMutation.DeleteHashField.class, name = "deleteHashField"),
    @JsonSubTypes.Type(value = ValueMutation.SetListElement.class, name = "setListElement"),
    @JsonSubTypes.Type(value = ValueMutation.PushListElement.class, name = "pushListElement"),
    @JsonSubTypes.Type(value = ValueMutation.RemoveListElement.class, name = "removeListElement"),
    @JsonSubTypes.Type(
            value = ValueMutation.RemoveListElementAt.class,
            name = "removeListElementAt"),
    @JsonSubTypes.Type(value = ValueMutation.AddSetMember.class, name = "addSetMember"),
    @JsonSubTypes.Type(value = ValueMutation.RemoveSetMember.class, name = "removeSetMember"),
    @JsonSubTypes.Type(value = ValueMutation.AddScoredMember.class, name = "addScoredMember"),
    @JsonSubTypes.Type(value = ValueMutation.RemoveScoredMember.class, name = "removeScoredMember"),
    @JsonSubTypes.Type(value = ValueMutation.AddStreamEntry.class, name = "addStreamEntry"),
    @JsonSubTypes.Type(value = ValueMutation.DeleteStreamEntry.class, name = "deleteStreamEntry"),
})
public sealed interface ValueMutation {

    String key();

    /** Replaces a string value outright. */
    record SetString(String key, String value) implements ValueMutation {}

    /** Creates or replaces one hash field. */
    record SetHashField(String key, String field, String value) implements ValueMutation {}

    record DeleteHashField(String key, String field) implements ValueMutation {}

    /** Replaces the element at an index; the index must already exist. */
    record SetListElement(String key, long index, String value) implements ValueMutation {}

    /**
     * @param toHead push to the head rather than the tail
     */
    record PushListElement(String key, String value, boolean toHead) implements ValueMutation {}

    /**
     * Removes list elements equal to a value.
     *
     * <p>Lists have no element identity, so removal is by value — the same reason Redis' own LREM
     * works this way.
     *
     * @param count how many to remove, from the head when positive and the tail when negative
     */
    record RemoveListElement(String key, String value, long count) implements ValueMutation {}

    /**
     * Removes the one element at an index.
     *
     * <p>Distinct from {@link RemoveListElement}, which removes elements <em>equal to</em> a value:
     * a list may hold the same text twice, and a button on the third row that removes the first is
     * not the button it appeared to be. Redis has no removal by index, so this is the two-step the
     * idiom uses — write a value nothing else can hold at that index, then remove that value.
     */
    record RemoveListElementAt(String key, long index) implements ValueMutation {}

    record AddSetMember(String key, String member) implements ValueMutation {}

    record RemoveSetMember(String key, String member) implements ValueMutation {}

    record AddScoredMember(String key, String member, double score) implements ValueMutation {}

    record RemoveScoredMember(String key, String member) implements ValueMutation {}

    /** Appends a stream entry; a null id lets the store assign one. */
    record AddStreamEntry(String key, String id, java.util.Map<String, String> fields)
            implements ValueMutation {}

    record DeleteStreamEntry(String key, String id) implements ValueMutation {}
}
