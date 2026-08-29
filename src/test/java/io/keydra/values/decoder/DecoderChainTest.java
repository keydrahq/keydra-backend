package io.keydra.values.decoder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import io.airlift.compress.zstd.ZstdOutputStream;
import io.keydra.values.dto.EncodedValue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;

@QuarkusTest
class DecoderChainTest {

    @Inject DecoderChain chain;

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(String value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(utf8(value));
        }
        return out.toByteArray();
    }

    private static byte[] deflate(String value) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(utf8(value));
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[256];
        while (!deflater.finished()) {
            out.write(chunk, 0, deflater.deflate(chunk));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] msgpack() throws IOException {
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packMapHeader(1).packString("name").packString("keydra");
            return packer.toByteArray();
        }
    }

    private static byte[] zstd(String value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(out)) {
            zstd.write(utf8(value));
        }
        return out.toByteArray();
    }

    private static byte[] lz4Frame(String value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
            lz4.write(utf8(value));
        }
        return out.toByteArray();
    }

    @Test
    void fallsBackToPlainText() {
        EncodedValue value = chain.decode(utf8("just a string"), null);

        assertThat(value.encoding(), equalTo(PlainDecoder.ID));
        assertThat(value.text(), equalTo("just a string"));
        assertThat(value.truncated(), is(false));
    }

    @Test
    void prettyPrintsJson() {
        EncodedValue value = chain.decode(utf8("{\"b\":2,\"a\":1}"), null);

        assertThat(value.encoding(), equalTo(JsonDecoder.ID));
        // Pretty-printed, not the original one-liner.
        assertThat(value.text(), containsString("\n"));
        assertThat(value.text(), containsString("\"b\" : 2"));
    }

    @Test
    void doesNotMistakeABareNumberForJson() {
        // "42" parses as valid JSON, but reporting a counter as JSON would be misleading.
        assertThat(chain.decode(utf8("42"), null).encoding(), equalTo(PlainDecoder.ID));
        assertThat(chain.decode(utf8("\"quoted\""), null).encoding(), equalTo(PlainDecoder.ID));
    }

    @Test
    void unwrapsGzip() throws IOException {
        EncodedValue value = chain.decode(gzip("compressed payload"), null);

        assertThat(value.encoding(), equalTo(GzipDecoder.ID));
        assertThat(value.text(), equalTo("compressed payload"));
        // The reported size is the stored value's, not the expansion's.
        assertThat(value.size() < "compressed payload".length() + 40, is(true));
    }

    @Test
    void rendersMsgPackAsJson() throws IOException {
        EncodedValue value = chain.decode(msgpack(), null);

        assertThat(value.encoding(), equalTo(MsgPackDecoder.ID));
        assertThat(value.text(), containsString("keydra"));
    }

    @Test
    void neverGuessesHexOrBase64() {
        // Any bytes are valid hex and much text is valid base64; guessing either would
        // hide the real format behind a dump.
        assertThat(chain.decode(utf8("deadbeef"), null).encoding(), equalTo(PlainDecoder.ID));
        assertThat(chain.decode(utf8("aGVsbG8="), null).encoding(), equalTo(PlainDecoder.ID));
    }

    @Test
    void appliesAnExplicitlyRequestedDecoder() {
        assertThat(chain.decode(utf8("AB"), HexDecoder.ID).text(), equalTo("41 42"));
        assertThat(chain.decode(utf8("hello"), Base64Decoder.ID).text(), equalTo("aGVsbG8="));
        // A requested decoder wins over detection.
        assertThat(chain.decode(utf8("{\"a\":1}"), PlainDecoder.ID).text(), equalTo("{\"a\":1}"));
    }

    @Test
    void ignoresAnUnknownDecoderRatherThanFailing() {
        EncodedValue value = chain.decode(utf8("{\"a\":1}"), "does-not-exist");

        assertThat(value.encoding(), equalTo(JsonDecoder.ID));
    }

    @Test
    void truncatesAValueTooLargeToRenderAndSaysSo() {
        byte[] huge = new byte[300_000];
        java.util.Arrays.fill(huge, (byte) 'x');

        EncodedValue value = chain.decode(huge, null);

        assertThat(value.truncated(), is(true));
        // The reported size is the whole value's, so the UI can say what was withheld.
        assertThat(value.size(), equalTo(300_000));
        assertThat(value.text().length(), equalTo(262_144));
    }

    @Test
    void listsEveryDecoderAClientMayRequest() {
        assertThat(
                chain.available(),
                hasItems(
                        PlainDecoder.ID,
                        JsonDecoder.ID,
                        HexDecoder.ID,
                        Base64Decoder.ID,
                        GzipDecoder.ID,
                        MsgPackDecoder.ID,
                        ZstdDecoder.ID,
                        Lz4Decoder.ID,
                        BrotliDecoder.ID));
    }

    @Test
    void handlesAnAbsentValue() {
        EncodedValue value = chain.decode(null, null);

        assertThat(value.text(), equalTo(""));
        assertThat(value.encoding(), startsWith(PlainDecoder.ID));
    }

    @Test
    void inflatesDeflatedValues() {
        EncodedValue value = chain.decode(deflate("a compressed payload"), null);

        assertThat(value.encoding(), equalTo(DeflateDecoder.ID));
        assertThat(value.text(), equalTo("a compressed payload"));
    }

    @Test
    void doesNotMistakePlainTextForDeflate() {
        // The zlib header check is weak enough to match by accident, so the decoder only
        // claims a value it has already inflated. Ordinary text must still read as text.
        assertThat(chain.decode(utf8("hello world"), null).encoding(), equalTo(PlainDecoder.ID));
        assertThat(chain.decode(utf8("{\"a\":1}"), null).encoding(), equalTo(JsonDecoder.ID));
    }

    @Test
    void unwrapsZstd() throws IOException {
        EncodedValue value = chain.decode(zstd("a zstandard payload"), null);

        assertThat(value.encoding(), equalTo(ZstdDecoder.ID));
        assertThat(value.text(), equalTo("a zstandard payload"));
    }

    @Test
    void unwrapsLz4Frames() throws IOException {
        EncodedValue value = chain.decode(lz4Frame("an lz4 payload"), null);

        assertThat(value.encoding(), equalTo(Lz4Decoder.ID));
        assertThat(value.text(), equalTo("an lz4 payload"));
    }

    @Test
    void unwrapsBrotli() {
        // Fixed rather than compressed here: the brotli artifact is a decoder, on purpose —
        // Keydra reads values it did not write, and has no reason to be able to write one.
        byte[] raw =
                Base64.getDecoder()
                        .decode("G08BYARqc6leSMb+oEEEBV1ZL6LJQqOpDKsqvq/YkB8CSI+FlWRwPoMC");

        EncodedValue value = chain.decode(raw, null);

        assertThat(value.encoding(), equalTo(BrotliDecoder.ID));
        assertThat(value.text(), startsWith("keydra reads a value nobody wrote for it."));
    }

    @Test
    void doesNotMistakeAnythingElseForBrotli() {
        // Brotli has no magic number, so the guess is fenced: readable text is never a
        // candidate, and bytes that decompress to noise are not claimed either.
        assertThat(chain.decode(utf8("hello world"), null).encoding(), equalTo(PlainDecoder.ID));
        byte[] noise = {(byte) 0xfe, (byte) 0x01, (byte) 0x9c, (byte) 0x77, (byte) 0xa3};
        assertThat(chain.decode(noise, null).encoding(), not(equalTo(BrotliDecoder.ID)));
    }
}
