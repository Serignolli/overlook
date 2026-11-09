package dev.overlook.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cliente HTTP mínimo sobre {@link Socket}, só para testes: permite mandar um {@code Host}
 * arbitrário, o que os clientes do JDK bloqueiam.
 */
final class RawHttp {

    record Response(int status, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private RawHttp() {
    }

    static Response send(int port, String method, String path, Map<String, String> headers, String body) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            Map<String, String> all = new LinkedHashMap<>();
            all.put("Host", "127.0.0.1:" + port);
            all.putAll(headers);
            all.put("Connection", "close");
            if (body != null) {
                all.put("Content-Type", "application/json");
                all.put("Content-Length", String.valueOf(payload.length));
            }
            all.forEach((k, v) -> req.append(k).append(": ").append(v).append("\r\n"));
            req.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(payload);
            out.flush();
            return parse(socket.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Response get(int port, String path) {
        return send(port, "GET", path, Map.of(), null);
    }

    private static Response parse(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        String raw = buf.toString(StandardCharsets.UTF_8);
        int split = raw.indexOf("\r\n\r\n");
        String head = raw.substring(0, split);
        String body = raw.substring(split + 4);
        String[] lines = head.split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            body = dechunk(body);
        }
        return new Response(status, headers, body);
    }

    private static String dechunk(String body) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos < body.length()) {
            int lineEnd = body.indexOf("\r\n", pos);
            int size = Integer.parseInt(body.substring(pos, lineEnd).trim(), 16);
            if (size == 0) {
                break;
            }
            // tamanho do chunk é em bytes; corpo de teste é ASCII/UTF-8
            byte[] rest = body.substring(lineEnd + 2).getBytes(StandardCharsets.UTF_8);
            String chunk = new String(rest, 0, size, StandardCharsets.UTF_8);
            out.append(chunk);
            pos = lineEnd + 2 + chunk.length() + 2;
        }
        return out.toString();
    }
}
