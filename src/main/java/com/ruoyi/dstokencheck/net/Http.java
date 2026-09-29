package com.ruoyi.dstokencheck.net;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP client for Java 8, built on {@link HttpURLConnection} so the produced jar runs on a
 * plain JRE 8. Used only for the API-key balance endpoint, so there is no session or cookie state
 * to carry between calls.
 */
public final class Http {

    /** Result of a single HTTP exchange; never null, transport failures raise IOException. */
    public static final class Response {
        private final int status;
        private final String body;
        private final Map<String, List<String>> headers;

        Response(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.headers = headers;
        }

        public int getStatus() {
            return status;
        }

        public String getBody() {
            return body;
        }

        public Map<String, List<String>> getHeaders() {
            return headers;
        }

        public boolean isOk() {
            return status >= 200 && status < 300;
        }

        @Override
        public String toString() {
            return "HTTP " + status + " " + body;
        }
    }

    private final String userAgent;

    public Http(String userAgent) {
        this.userAgent = userAgent;
    }

    public Response get(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        return request("GET", url, null, headers, timeoutMs);
    }

    public Response request(String method, String url, String body, Map<String, String> headers, int timeoutMs)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(Math.min(timeoutMs, 20000));
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);

            conn.setRequestProperty("User-Agent", userAgent);
            // Ask for an unencoded body so nothing has to be gunzipped by hand.
            conn.setRequestProperty("Accept-Encoding", "identity");

            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }

            if (body != null) {
                conn.setDoOutput(true);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(payload);
                    os.flush();
                } finally {
                    closeQuietly(os);
                }
            }

            int status = conn.getResponseCode();
            InputStream in = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);

            Map<String, List<String>> hdrs = new LinkedHashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() != null) {
                    hdrs.put(e.getKey(), e.getValue());
                }
            }
            return new Response(status, text, Collections.unmodifiableMap(hdrs));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        } finally {
            closeQuietly(in);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }
}
