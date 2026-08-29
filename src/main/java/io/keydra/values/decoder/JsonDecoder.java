package io.keydra.values.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;

/** Pretty-prints JSON, which is what most string values in a cache turn out to be. */
@ApplicationScoped
public class JsonDecoder implements ValueDecoder {

    public static final String ID = "json";

    /** Its own mapper: reformatting values must not depend on how the API serialises responses. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean canDecode(byte[] raw) {
        if (raw.length < 2) {
            return false;
        }
        // Cheap shape check before paying for a parse: only objects and arrays are worth
        // pretty-printing, and a bare number or word would match otherwise.
        char first = (char) raw[0];
        if (first != '{' && first != '[') {
            return false;
        }
        try {
            MAPPER.readTree(raw);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @Override
    public String decode(byte[] raw) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(raw));
        } catch (java.io.IOException e) {
            // canDecode already parsed it; if that changed, the bytes are still readable.
            return new String(raw, StandardCharsets.UTF_8);
        }
    }
}
