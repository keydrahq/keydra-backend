package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

/** Renders msgpack as JSON, which is the only readable form of a binary-packed value. */
@ApplicationScoped
public class MsgPackDecoder implements ValueDecoder {

    public static final String ID = "msgpack";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public boolean canDecode(byte[] raw) {
        if (raw.length < 2) {
            return false;
        }
        try (var unpacker = MessagePack.newDefaultUnpacker(raw)) {
            Value value = unpacker.unpackValue();
            // A single scalar is not evidence of msgpack — almost any byte decodes as one.
            // Only a map or array, consuming the whole input, is convincing.
            return (value.isMapValue() || value.isArrayValue()) && !unpacker.hasNext();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String decode(byte[] raw) {
        try (var unpacker = MessagePack.newDefaultUnpacker(raw)) {
            return unpacker.unpackValue().toJson();
        } catch (IOException e) {
            return new String(raw, StandardCharsets.UTF_8);
        }
    }
}
