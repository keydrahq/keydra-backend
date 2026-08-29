package io.keydra.alerts;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Meta's Cloud API, reduced to the part Keydra talks to.
 *
 * <p>A test resource rather than something a test starts, because unlike a mail server — whose
 * address is a column on the row — the Cloud API's address is configuration, and configuration is
 * fixed by the time a test method runs. Starting it here is what lets the endpoint be a port nobody
 * else is on.
 *
 * <p>It exists so this path is tested rather than described. What breaks in a delivery like this is
 * never the part anybody re-reads: the bearer token that went into the wrong header, the JSON field
 * the API insists on, the recipient that arrived with its plus sign eaten.
 */
public class FakeWhatsApp implements QuarkusTestResourceLifecycleManager {

    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> LAST_PATH = new AtomicReference<>();

    private HttpServer server;

    /** The JSON body of the last message, or null when nothing has been sent. */
    public static String lastBody() {
        return LAST_BODY.get();
    }

    /** The Authorization header the last message carried. */
    public static String lastAuthorization() {
        return LAST_AUTHORIZATION.get();
    }

    /** The path the last message was posted to, which names the sending number. */
    public static String lastPath() {
        return LAST_PATH.get();
    }

    public static void forget() {
        LAST_BODY.set(null);
        LAST_AUTHORIZATION.set(null);
        LAST_PATH.set(null);
    }

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake Cloud API", e);
        }
        server.createContext(
                "/",
                exchange -> {
                    try (InputStream body = exchange.getRequestBody()) {
                        LAST_BODY.set(new String(body.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    LAST_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    LAST_PATH.set(exchange.getRequestURI().getPath());
                    byte[] answer =
                            "{\"messaging_product\":\"whatsapp\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, answer.length);
                    exchange.getResponseBody().write(answer);
                    exchange.close();
                });
        server.start();
        return Map.of(
                "keydra.alerts.whatsapp.base-uri",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
