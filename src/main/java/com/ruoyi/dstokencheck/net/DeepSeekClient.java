package com.ruoyi.dstokencheck.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.dstokencheck.model.BalanceSnapshot;
import com.ruoyi.dstokencheck.model.Wallet;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the account balance from the officially documented endpoint
 * {@code GET https://api.deepseek.com/user/balance}, authenticated with an API key.
 *
 * <p>This is the only data path the app uses. The platform's web sign-in flow was removed: it is
 * guarded by server-side device risk control that a plain HTTP client cannot satisfy, whereas the
 * API-key endpoint is a supported public interface.
 */
public class DeepSeekClient {

    public static final String API_BASE = "https://api.deepseek.com";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/125.0.0.0 Safari/537.36";

    /** Raised for any expected, user-explicable failure (bad key, network, malformed response). */
    public static class DeepSeekException extends Exception {
        private final String rawResponse;
        private final boolean authFailure;

        public DeepSeekException(String message) {
            this(message, null, false);
        }

        public DeepSeekException(String message, String rawResponse) {
            this(message, rawResponse, false);
        }

        public DeepSeekException(String message, String rawResponse, boolean authFailure) {
            super(message);
            this.rawResponse = rawResponse;
            this.authFailure = authFailure;
        }

        public String getRawResponse() {
            return rawResponse;
        }

        /** True when the server rejected the credential, so the user must supply a new one. */
        public boolean isAuthFailure() {
            return authFailure;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Http http = new Http(UA);
    private String apiKey;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Shortens a key for display, e.g. {@code sk-abc…wxyz}. Used for the widget's label and never
     * written to the config file.
     */
    public static String maskKey(String key) {
        if (key == null) {
            return "";
        }
        String k = key.trim();
        if (k.isEmpty()) {
            return "";
        }
        if (k.length() <= 12) {
            return "\u2026" + k.substring(Math.max(0, k.length() - 4));
        }
        return k.substring(0, 6) + "\u2026" + k.substring(k.length() - 4);
    }

    /** Fetches the balance for the key currently held by this client. */
    public BalanceSnapshot fetchBalance() throws DeepSeekException {
        return fetchBalance(apiKey);
    }

    /**
     * Fetches the balance for an explicit key.
     *
     * @throws DeepSeekException with a message suitable for showing to the user
     */
    public BalanceSnapshot fetchBalance(String key) throws DeepSeekException {
        if (key == null || key.trim().isEmpty()) {
            throw new DeepSeekException("请输入 API Key");
        }
        String trimmed = key.trim();

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + trimmed);
        headers.put("Accept", "application/json");

        Http.Response resp;
        try {
            resp = http.get(API_BASE + "/user/balance", headers, 30000);
        } catch (IOException e) {
            throw new DeepSeekException("网络请求失败: " + e.getMessage());
        }

        if (resp.getStatus() == 401) {
            throw new DeepSeekException("API Key 无效或已被撤销（HTTP 401）", resp.getBody(), true);
        }
        if (!resp.isOk()) {
            throw new DeepSeekException("接口返回 HTTP " + resp.getStatus() + ": "
                    + truncate(resp.getBody(), 300), resp.getBody());
        }

        JsonNode root = parse(resp.getBody());

        List<Wallet> wallets = new ArrayList<Wallet>();
        JsonNode infos = root.path("balance_infos");
        if (infos.isArray()) {
            for (JsonNode w : infos) {
                wallets.add(new Wallet(
                        w.path("currency").asText(""),
                        decimal(w.path("total_balance").asText(null)),
                        null,
                        false));
            }
        }
        if (wallets.isEmpty()) {
            throw new DeepSeekException("接口未返回余额信息: " + truncate(resp.getBody(), 300), resp.getBody());
        }

        return new BalanceSnapshot(wallets, null, null, System.currentTimeMillis(), "api.deepseek.com");
    }

    private JsonNode parse(String json) throws DeepSeekException {
        try {
            return mapper.readTree(json == null || json.isEmpty() ? "{}" : json);
        } catch (IOException e) {
            throw new DeepSeekException("响应不是合法 JSON: " + truncate(json, 200));
        }
    }

    private static BigDecimal decimal(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
