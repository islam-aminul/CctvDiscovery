package com.cctv.discovery.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal RTSP/1.0 client (RFC 2326) over one TCP connection.
 *
 * <p>Handles CSeq numbering, session tracking, Basic and Digest authentication
 * (recomputed per request, since the Digest hash covers method and URI) and
 * RTP interleaved on the control connection, which lets media delivery be
 * verified without inbound UDP reaching the host through a firewall.
 *
 * <p>Credentials never appear in log output or exception messages.
 */
public final class RtspClient implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(RtspClient.class);

    public static final String USER_AGENT = "CCTV-Discovery/2.0";
    private static final int MAX_BODY_BYTES = 512 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int MAX_SKIPPED_PACKETS = 512;

    /** An RTSP response. Header lookups are case-insensitive. */
    public record Response(int status, String reason, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        public List<String> headerValues(String name) {
            return headers.getOrDefault(name, List.of());
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean unauthorized() {
            return status == 401;
        }

        /** Status line only: safe to log. */
        public String statusLine() {
            return status + " " + reason;
        }
    }

    /** One interleaved binary packet ({@code $} framing, RFC 2326 section 10.12). */
    public record InterleavedPacket(int channel, byte[] payload) {

        /** True when the payload looks like an RTP packet (version 2). */
        public boolean isRtp() {
            return payload.length >= 12 && ((payload[0] & 0xC0) >> 6) == 2;
        }

        public int payloadType() {
            return payload.length >= 2 ? payload[1] & 0x7F : -1;
        }
    }

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private int cseq;
    private String session;
    private String username;
    private String password;
    private AuthUtils.Authenticator authenticator;
    private boolean authenticationRequired;
    private boolean authenticationAccepted;

    public RtspClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** Set the credentials used when the server challenges. Optional. */
    public RtspClient withCredentials(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    /** True when the server answered 401 at any point on this connection. */
    public boolean authenticationRequired() {
        return authenticationRequired;
    }

    /** True when a request succeeded after credentials were supplied. */
    public boolean authenticationAccepted() {
        return authenticationAccepted;
    }

    /** Session identifier from the last SETUP, or null. */
    public String session() {
        return session;
    }

    public void connect() throws IOException {
        socket = new Socket();
        socket.setSoTimeout(readTimeoutMs);
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    private void ensureConnected() throws IOException {
        if (socket == null || socket.isClosed()) {
            connect();
        }
    }

    /**
     * Send a request, transparently answering a 401 challenge when credentials
     * were supplied. At most one authenticated retry is made per call, so a
     * wrong password cannot loop.
     */
    public Response request(String method, String url, Map<String, String> extraHeaders) throws IOException {
        Response response = sendOnce(method, url, extraHeaders);
        if (!response.unauthorized()) {
            if (response.ok() && authenticator != null) {
                authenticationAccepted = true;
            }
            return response;
        }

        authenticationRequired = true;
        if (username == null || username.isEmpty()) {
            return response;
        }

        List<AuthUtils.AuthChallenge> challenges = AuthUtils.parseChallenges(response.headerValues("WWW-Authenticate"));
        if (challenges.isEmpty()) {
            logger.debug("RTSP {}:{} returned 401 without a usable challenge", host, port);
            return response;
        }
        authenticator = new AuthUtils.Authenticator(challenges.getFirst(), username, password);
        logger.debug("RTSP {}:{} challenged with {}", host, port, challenges.getFirst());

        Response retry = sendOnce(method, url, extraHeaders);
        if (retry.ok()) {
            authenticationAccepted = true;
        } else if (retry.unauthorized()) {
            authenticator = null;
        }
        return retry;
    }

    private Response sendOnce(String method, String url, Map<String, String> extraHeaders) throws IOException {
        ensureConnected();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("CSeq", String.valueOf(++cseq));
        headers.put("User-Agent", USER_AGENT);
        if (session != null) {
            headers.put("Session", session);
        }
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        if (authenticator != null) {
            headers.put("Authorization", authenticator.authorization(method, requestUri(url)));
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        Response response = readResponse();
        logger.debug("RTSP {} {} -> {}", method, AuthUtils.redact(url), response.statusLine());
        return response;
    }

    /**
     * The URI used in the Digest hash. RFC 7616 uses the request target, which
     * for RTSP is the absolute URL sent on the request line.
     */
    private static String requestUri(String url) {
        return url;
    }

    /** Read one response, skipping any interleaved media that arrives first. */
    private Response readResponse() throws IOException {
        for (int skipped = 0; skipped <= MAX_SKIPPED_PACKETS; skipped++) {
            int first = in.read();
            if (first < 0) {
                throw new EOFException("Connection closed by " + host + ":" + port);
            }
            if (first == '$') {
                readInterleavedBody();
                continue;
            }
            String statusLine = (char) first + readLine();
            return readAfterStatusLine(statusLine);
        }
        throw new IOException("No RTSP response after " + MAX_SKIPPED_PACKETS + " interleaved packets");
    }

    private Response readAfterStatusLine(String statusLine) throws IOException {
        if (!statusLine.startsWith("RTSP/")) {
            throw new IOException("Not an RTSP response: " + abbreviate(statusLine));
        }
        String[] parts = statusLine.split(" ", 3);
        int status;
        try {
            status = Integer.parseInt(parts[1].trim());
        } catch (RuntimeException e) {
            throw new IOException("Malformed RTSP status line: " + abbreviate(statusLine));
        }
        String reason = parts.length > 2 ? parts[2].trim() : "";

        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int contentLength = 0;
        String line;
        while (!(line = readLine()).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            if (name.equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Math.min(Integer.parseInt(value), MAX_BODY_BYTES);
                } catch (NumberFormatException ignored) {
                    contentLength = 0;
                }
            } else if (name.equalsIgnoreCase("Session")) {
                session = value.split(";")[0].trim();
            }
        }

        String body = "";
        if (contentLength > 0) {
            body = new String(readFully(contentLength), StandardCharsets.UTF_8);
        }
        return new Response(status, reason, headers, body);
    }

    /**
     * Read one interleaved packet, waiting up to {@code timeoutMs}. Returns null
     * when the wait elapses or an RTSP message arrives instead.
     */
    public InterleavedPacket readInterleaved(int timeoutMs) throws IOException {
        int previous = socket.getSoTimeout();
        socket.setSoTimeout(Math.max(1, timeoutMs));
        try {
            int first = in.read();
            if (first < 0) {
                throw new EOFException("Connection closed by " + host + ":" + port);
            }
            if (first != '$') {
                // An RTSP message (for example a keep-alive response); consume it.
                readAfterStatusLine((char) first + readLine());
                return null;
            }
            return readInterleavedBody();
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(previous);
        }
    }

    private InterleavedPacket readInterleavedBody() throws IOException {
        int channel = in.read();
        int hi = in.read();
        int lo = in.read();
        if (channel < 0 || hi < 0 || lo < 0) {
            throw new EOFException("Truncated interleaved frame");
        }
        int length = (hi << 8) | lo;
        byte[] payload = readFully(Math.min(length, MAX_BODY_BYTES));
        return new InterleavedPacket(channel, payload);
    }

    private byte[] readFully(int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) {
                throw new EOFException("Truncated body: expected " + length + " bytes, got " + read);
            }
            read += n;
        }
        return buffer;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int previous = -1;
        while (buffer.size() < MAX_LINE_BYTES) {
            int b = in.read();
            if (b < 0) {
                if (buffer.size() == 0) {
                    throw new EOFException("Connection closed by " + host + ":" + port);
                }
                break;
            }
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = previous == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, Math.max(0, length), StandardCharsets.UTF_8);
            }
            buffer.write(b);
            previous = b;
        }
        throw new IOException("RTSP header line too long");
    }

    private static String abbreviate(String s) {
        String clean = s.replaceAll("[\\p{Cntrl}]", "");
        return clean.length() <= 60 ? clean : clean.substring(0, 60) + "...";
    }

    /** Send TEARDOWN for the current session, ignoring failures. */
    public void teardown(String url) {
        if (session == null || socket == null || socket.isClosed()) {
            return;
        }
        try {
            sendOnce("TEARDOWN", url, Map.of());
        } catch (Exception e) {
            logger.debug("TEARDOWN failed for {}: {}", host, e.getMessage());
        } finally {
            session = null;
        }
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // closing a probe socket
        }
    }

    /** Build {@code rtsp://host:port/path} with the path normalised. */
    public static String url(String host, int port, String path) {
        String p = path == null || path.isEmpty() ? "/" : path;
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return "rtsp://" + host + ":" + port + p;
    }

    /** Host part of an RTSP URL, ignoring any userinfo. */
    public static String hostOf(String rtspUrl) {
        String rest = stripScheme(rtspUrl);
        int at = rest.indexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int end = indexOfAny(rest, ":/");
        return end < 0 ? rest : rest.substring(0, end);
    }

    /** Port of an RTSP URL, or 554 when absent. */
    public static int portOf(String rtspUrl) {
        String rest = stripScheme(rtspUrl);
        int at = rest.indexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int colon = rest.indexOf(':');
        if (colon < 0) {
            return 554;
        }
        int slash = rest.indexOf('/', colon);
        String portText = slash < 0 ? rest.substring(colon + 1) : rest.substring(colon + 1, slash);
        try {
            return Integer.parseInt(portText.trim());
        } catch (NumberFormatException e) {
            return 554;
        }
    }

    /** Remove any {@code user:pass@} from an RTSP URL. */
    public static String stripCredentials(String rtspUrl) {
        int schemeEnd = rtspUrl.indexOf("://");
        if (schemeEnd < 0) {
            return rtspUrl;
        }
        String rest = rtspUrl.substring(schemeEnd + 3);
        int at = rest.indexOf('@');
        int slash = rest.indexOf('/');
        if (at < 0 || (slash >= 0 && at > slash)) {
            return rtspUrl;
        }
        return rtspUrl.substring(0, schemeEnd + 3) + rest.substring(at + 1);
    }

    private static String stripScheme(String url) {
        int i = url.indexOf("://");
        return i < 0 ? url : url.substring(i + 3);
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** Percent-encode userinfo so ':' '@' '/' '?' '#' in credentials cannot break a URL. */
    public static String encodeUserInfo(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return sb.toString();
    }

    /** Insert credentials into an RTSP URL, replacing any already present. */
    public static String withCredentials(String rtspUrl, String username, String password) {
        if (username == null || username.isEmpty()) {
            return rtspUrl;
        }
        String clean = stripCredentials(rtspUrl);
        int schemeEnd = clean.indexOf("://");
        if (schemeEnd < 0) {
            return clean;
        }
        String userInfo = encodeUserInfo(username) + ":" + encodeUserInfo(password == null ? "" : password);
        return clean.substring(0, schemeEnd + 3) + userInfo + "@" + clean.substring(schemeEnd + 3);
    }
}
