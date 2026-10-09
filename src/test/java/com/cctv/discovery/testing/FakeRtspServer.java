package com.cctv.discovery.testing;

import com.cctv.discovery.util.AuthUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An RTSP server that answers DESCRIBE the way real devices on a survey were
 * measured to, so the parts of a scan that depend on those answers can be
 * tested without the hardware, and without risking the hardware: a test that
 * deliberately fails logins against a real Hikvision device locks its account.
 *
 * <p>Measured on 2026-10-09 against a Hikvision recorder and its six cameras:
 * a camera challenges every path, "/" included; the recorder answers "/" with
 * 404 and challenges only its real stream paths.
 */
public final class FakeRtspServer implements AutoCloseable {

    public enum Style {
        /** Challenges every unauthenticated request, "/" included. */
        CAMERA,
        /** Answers "/" with 404, and challenges only real paths. */
        RECORDER,
        /** Asks for no password at all. */
        OPEN
    }

    private static final String SDP = """
            v=0\r
            o=- 0 0 IN IP4 127.0.0.1\r
            s=Fake stream\r
            c=IN IP4 0.0.0.0\r
            t=0 0\r
            m=video 0 RTP/AVP 96\r
            a=rtpmap:96 H264/90000\r
            a=fmtp:96 packetization-mode=1;profile-level-id=4D401F\r
            a=control:trackID=1\r
            """;

    private final ServerSocket socket;
    private final Style style;
    private final String goodAuthorization;
    private final Set<String> streamPaths;
    private final AtomicInteger rejectedLogins = new AtomicInteger();
    private final AtomicInteger acceptedLogins = new AtomicInteger();
    private final List<String> requestLog = Collections.synchronizedList(new ArrayList<>());
    private final Thread acceptor;

    /**
     * @param streamPaths the paths that serve video once signed in; anything
     *                    else signed in gets 404, as a real device does
     */
    public FakeRtspServer(Style style, String username, String password, Set<String> streamPaths)
            throws IOException {
        this.style = style;
        this.goodAuthorization = AuthUtils.basic(username, password);
        this.streamPaths = Set.copyOf(streamPaths);
        this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptor = Thread.ofVirtual().name("fake-rtsp").start(this::acceptLoop);
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** Requests that carried credentials the server refused: failed logins. */
    public int rejectedLogins() {
        return rejectedLogins.get();
    }

    public int acceptedLogins() {
        return acceptedLogins.get();
    }

    /** "METHOD path [auth]" for every request, in arrival order. */
    public List<String> requestLog() {
        synchronized (requestLog) {
            return List.copyOf(requestLog);
        }
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Thread.ofVirtual().start(() -> serve(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
             OutputStream out = client.getOutputStream()) {
            String requestLine;
            while ((requestLine = in.readLine()) != null) {
                if (requestLine.isEmpty()) {
                    continue;
                }
                Map<String, String> headers = new LinkedHashMap<>();
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                    }
                }
                out.write(answer(requestLine, headers).getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        } catch (IOException ignored) {
            // The client hung up, which it does after every probe.
        }
    }

    private String answer(String requestLine, Map<String, String> headers) {
        String[] parts = requestLine.split(" ");
        String method = parts[0];
        String url = parts.length > 1 ? parts[1] : "/";
        String cseq = headers.getOrDefault("cseq", "0");
        String path = pathOf(url);
        String authorization = headers.get("authorization");

        requestLog.add(method + " " + path + (authorization == null ? "" : " [auth]"));

        if ("OPTIONS".equals(method)) {
            return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nPublic: OPTIONS, DESCRIBE\r\n\r\n";
        }

        if (authorization != null) {
            if (!authorization.equals(goodAuthorization)) {
                rejectedLogins.incrementAndGet();
                return challenge(cseq);
            }
            acceptedLogins.incrementAndGet();
            return streamPaths.contains(path) ? describe(cseq, url) : status(cseq, "404 Not Found");
        }

        return switch (style) {
            case CAMERA -> challenge(cseq);
            case RECORDER -> "/".equals(path) ? status(cseq, "404 Not Found") : challenge(cseq);
            case OPEN -> streamPaths.contains(path) ? describe(cseq, url) : status(cseq, "404 Not Found");
        };
    }

    private static String pathOf(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String challenge(String cseq) {
        return "RTSP/1.0 401 Unauthorized\r\nCSeq: " + cseq
                + "\r\nWWW-Authenticate: Basic realm=\"fake\"\r\n\r\n";
    }

    private static String status(String cseq, String status) {
        return "RTSP/1.0 " + status + "\r\nCSeq: " + cseq + "\r\n\r\n";
    }

    private static String describe(String cseq, String url) {
        byte[] body = SDP.getBytes(StandardCharsets.ISO_8859_1);
        return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nContent-Type: application/sdp\r\nContent-Base: "
                + url + "/\r\nContent-Length: " + body.length + "\r\n\r\n" + SDP;
    }

    @Override
    public void close() throws IOException {
        socket.close();
        acceptor.interrupt();
    }
}
