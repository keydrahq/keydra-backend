package io.keydra.alerts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A mail server that only has to be enough of one.
 *
 * <p>Here so the mail path is actually tested rather than described. The alternative was to leave
 * it to somebody running a catcher by hand, which means it is tested once and then only when it
 * breaks — and what breaks in a mail delivery is never the part anybody would think to re-check: an
 * address that had to be escaped, a subject that never made it out of the headers.
 *
 * <p>Roughly sixty lines of RFC 5321, which is all a client needs to hear: a greeting, agreement to
 * everything, and somewhere to put the message.
 */
public final class FakeSmtp implements AutoCloseable {

    private final ServerSocket socket;
    private final Thread thread;
    private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

    public FakeSmtp() throws IOException {
        socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"));
        thread = new Thread(this::accept, "fake-smtp");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** Everything that has arrived, headers and body together as the client sent it. */
    public List<String> messages() {
        return List.copyOf(messages);
    }

    /** Forgets what has arrived, so one test cannot read another test's post. */
    public void clear() {
        messages.clear();
    }

    private void accept() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                converse(client);
            } catch (IOException closed) {
                // The socket closing is how this thread is asked to stop.
                return;
            }
        }
    }

    private void converse(Socket client) throws IOException {
        BufferedReader in =
                new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = client.getOutputStream();
        say(out, "220 keydra-test ready");

        String line;
        while ((line = in.readLine()) != null) {
            String command = line.toUpperCase(java.util.Locale.ROOT);
            if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                // No STARTTLS advertised: this is a test server, and a client told to use TLS
                // will only try when the server says it can.
                say(out, "250-keydra-test");
                say(out, "250 8BITMIME");
            } else if (command.startsWith("DATA")) {
                say(out, "354 End data with <CR><LF>.<CR><LF>");
                messages.add(readMessage(in));
                say(out, "250 OK");
            } else if (command.startsWith("QUIT")) {
                say(out, "221 Bye");
                return;
            } else {
                say(out, "250 OK");
            }
        }
    }

    private static String readMessage(BufferedReader in) throws IOException {
        StringBuilder message = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null && !".".equals(line)) {
            message.append(line).append('\n');
        }
        return message.toString();
    }

    private static void say(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a listening socket that has already gone is not a failure worth having.
        }
        thread.interrupt();
    }
}
