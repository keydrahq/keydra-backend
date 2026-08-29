package io.keydra.values.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A value as text, plus how it was read.
 *
 * <p>Decoding happens on the server so the browser never has to hold or transform raw bytes, and so
 * a gzipped or msgpack value is readable without the UI shipping a decoder for every format.
 *
 * @param text the decoded representation
 * @param encoding which decoder produced it
 * @param size the raw value's size in bytes, before decoding
 * @param truncated true when the value was too large to return whole
 */
@Schema(name = "EncodedValue", description = "A decoded value with the encoding that produced it")
public record EncodedValue(String text, String encoding, int size, boolean truncated) {}
