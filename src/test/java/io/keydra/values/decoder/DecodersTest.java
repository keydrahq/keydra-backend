package io.keydra.values.decoder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The decoders that read a format without running it.
 *
 * <p>Three of the four below deliberately stop short of reconstructing the value: a Java stream and
 * a pickle both name things to construct, and constructing what an untrusted value names is how a
 * cache entry becomes code that runs. These tests pin that they describe rather than open.
 */
class DecodersTest {

    private final PhpSerializedDecoder php = new PhpSerializedDecoder();
    private final JavaSerializedDecoder javaStream = new JavaSerializedDecoder();
    private final PickleDecoder pickle = new PickleDecoder();
    private final ProtobufDecoder protobuf = new ProtobufDecoder();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A protocol 2 pickle: the version marker, then the given opcodes. */
    private static byte[] pickleOf(String opcodes) {
        byte[] body = bytes(opcodes);
        byte[] raw = new byte[body.length + 2];
        raw[0] = (byte) 0x80;
        raw[1] = 0x02;
        System.arraycopy(body, 0, raw, 2, body.length);
        return raw;
    }

    @Test
    void readsAPhpSession() {
        byte[] raw = bytes("a:2:{s:4:\"name\";s:5:\"alice\";s:3:\"age\";i:30;}");

        assertThat(php.canDecode(raw), is(true));
        String rendered = php.decode(raw);
        assertThat(rendered, containsString("\"name\": \"alice\""));
        assertThat(rendered, containsString("\"age\": 30"));
    }

    @Test
    void namesAPhpObjectsClassWithoutConstructingIt() {
        byte[] raw = bytes("O:8:\"stdClass\":1:{s:2:\"id\";i:7;}");

        assertThat(php.canDecode(raw), is(true));
        assertThat(php.decode(raw), containsString("stdClass {"));
    }

    @Test
    void refusesSomethingThatOnlyStartsLikePhp() {
        // Two characters is not a format. Claiming it would take a value away from the
        // decoder that could actually read it.
        assertThat(php.canDecode(bytes("s:5:\"trunc")), is(false));
        assertThat(php.canDecode(bytes("a:1:{}extra")), is(false));
    }

    @Test
    void identifiesAJavaStreamAndSaysItDidNotOpenIt() {
        // The magic, then a class description naming java.lang.String.
        byte[] raw =
                new byte[] {
                    (byte) 0xac,
                    (byte) 0xed,
                    0x00,
                    0x05,
                    0x72,
                    0x00,
                    0x10,
                    'j',
                    'a',
                    'v',
                    'a',
                    '.',
                    'l',
                    'a',
                    'n',
                    'g',
                    '.',
                    'S',
                    't',
                    'r',
                    'i',
                    'n',
                    'g'
                };

        assertThat(javaStream.canDecode(raw), is(true));
        String rendered = javaStream.decode(raw);
        assertThat(rendered, containsString("java.lang.String"));
        // A reader who sees only class names must know the value was not opened rather
        // than assume it was empty.
        assertThat(rendered, containsString("not deserialized"));
    }

    @Test
    void identifiesAPickleAndNamesWhatItWouldImport() {
        // Protocol 2: the version marker, an import of os.system, then the stop opcode.
        // Built as bytes rather than from a string, because 0x80 is two bytes in UTF-8.
        byte[] raw = pickleOf("cos\nsystem\n.");

        assertThat(pickle.canDecode(raw), is(true));
        String rendered = pickle.decode(raw);
        // The whole point: the value said "import os, take system", and saying so is more
        // useful and much safer than doing it.
        assertThat(rendered, containsString("os.system"));
        assertThat(rendered, containsString("not unpickled"));
    }

    @Test
    void doesNotClaimAPickleWithoutItsEndMarker() {
        assertThat(pickle.canDecode(pickleOf("cos\nsystem\n")), is(false));
    }

    @Test
    void leavesAnOlderPickleToTheTextDecoders() {
        // Protocol 0 is plain ASCII with no marker of its own, so claiming it would mean
        // claiming any text that happens to end in a full stop.
        assertThat(pickle.canDecode(bytes("cos\nsystem\n.")), is(false));
    }

    @Test
    void showsWhatAProtobufMessageCarriesWithoutASchema() {
        // Field 1 holding the varint 150, then field 2 holding the string "hi".
        byte[] raw = new byte[] {0x08, (byte) 0x96, 0x01, 0x12, 0x02, 'h', 'i'};

        assertThat(protobuf.canDecode(raw), is(true));
        String rendered = protobuf.decode(raw);
        // Numbers rather than names, because the names live in a .proto Keydra never saw.
        assertThat(rendered, containsString("1: 150"));
        assertThat(rendered, containsString("2: \"hi\""));
    }

    @Test
    void willNotClaimAValueThatIsMerelyShort() {
        // One varint is indistinguishable from a two-character string, so protobuf must
        // not take it from the decoder that can actually read it.
        assertThat(protobuf.canDecode(new byte[] {0x08, 0x01}), is(false));
    }

    @Test
    void willNotClaimSomethingThatReadsAsText() {
        // A run of 'x' parses as field 15 repeated once per byte. Claiming it would turn a
        // plain string into pages of invented structure, which is how this decoder would
        // do the most damage.
        byte[] plain = new byte[3000];
        java.util.Arrays.fill(plain, (byte) 'x');

        assertThat(protobuf.canDecode(plain), is(false));
        assertThat(protobuf.canDecode(bytes("hello there")), is(false));
    }

    @Test
    void everyDecoderHasItsOwnName() {
        assertThat(php.id(), equalTo("php"));
        assertThat(javaStream.id(), equalTo("java"));
        assertThat(pickle.id(), equalTo("pickle"));
        assertThat(protobuf.id(), equalTo("protobuf"));
        assertThat(php.id(), not(equalTo(protobuf.id())));
    }
}
