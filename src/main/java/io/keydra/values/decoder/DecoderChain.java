package io.keydra.values.decoder;

import io.keydra.values.dto.EncodedValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Picks a decoder for a value, or applies the one a client asked for.
 *
 * <p>Decoders are discovered as CDI beans and ordered by priority, so adding a format means adding
 * a bean. Automatic selection takes the first that recognises the bytes; formats that are ways of
 * looking at bytes rather than formats they are stored in (hex, base64) decline automatic selection
 * and are only used on request.
 */
@ApplicationScoped
public class DecoderChain {

    private final List<ValueDecoder> ordered;
    private final Map<String, ValueDecoder> byId;
    private final int maxRenderedBytes;

    @Inject
    DecoderChain(
            @Any Instance<ValueDecoder> decoders,
            @ConfigProperty(name = "keydra.values.max-rendered-bytes", defaultValue = "262144")
                    int maxRenderedBytes) {
        this.ordered =
                decoders.stream().sorted(Comparator.comparingInt(ValueDecoder::priority)).toList();
        this.byId =
                this.ordered.stream()
                        .collect(Collectors.toMap(ValueDecoder::id, Function.identity()));
        this.maxRenderedBytes = maxRenderedBytes;
    }

    /** Names of every decoder a client may request. */
    public List<String> available() {
        return ordered.stream().map(ValueDecoder::id).sorted().toList();
    }

    /**
     * Decodes a value.
     *
     * @param requested a decoder id to force, or null to detect one
     */
    public EncodedValue decode(byte[] raw, String requested) {
        if (raw == null) {
            return new EncodedValue("", PlainDecoder.ID, 0, false);
        }
        // A single value can be larger than anything a browser should be asked to render;
        // cut it and say so rather than sending megabytes nobody reads.
        boolean truncated = raw.length > maxRenderedBytes;
        byte[] shown = truncated ? java.util.Arrays.copyOf(raw, maxRenderedBytes) : raw;

        ValueDecoder decoder = select(shown, requested);
        return new EncodedValue(decoder.decode(shown), decoder.id(), raw.length, truncated);
    }

    private ValueDecoder select(byte[] raw, String requested) {
        if (requested != null) {
            ValueDecoder named = byId.get(requested);
            if (named != null) {
                return named;
            }
        }
        return ordered.stream()
                .filter(decoder -> decoder.canDecode(raw))
                .findFirst()
                // PlainDecoder always matches, so this is unreachable in practice.
                .orElseThrow(() -> new IllegalStateException("No decoder accepted the value"));
    }
}
