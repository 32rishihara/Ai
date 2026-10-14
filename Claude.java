package com.guardai.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Minimal client for the Anthropic Messages API. Call from a background thread. */
public class Claude {

    public static JSONArray single(String userText) throws Exception {
        JSONArray a = new JSONArray();
        a.put(new JSONObject().put("role", "user").put("content", userText));
        return a;
    }

    public static String ask(Prefs p, String system, JSONArray messages, int maxTokens) throws Exception {
        if (p.apiKey().isEmpty()) {
            throw new Exception("Add your Anthropic API key in Settings first.");
        }
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("x-api-key", p.apiKey());
        c.setRequestProperty("anthropic-version", "2023-06-01");
        c.setRequestProperty("content-type", "application/json");
        c.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("model", p.model());
        body.put("max_tokens", maxTokens);
        if (system != null) body.put("system", system);
        body.put("messages", messages);

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }

        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        String resp = readAll(is);
        c.disconnect();

        if (code >= 400) {
            String msg = resp;
            try {
                msg = new JSONObject(resp).getJSONObject("error").getString("message");
            } catch (Exception ignored) {
            }
            throw new Exception("API error " + code + ": " + msg);
        }

        JSONArray content = new JSONObject(resp).getJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject blk = content.getJSONObject(i);
            if ("text".equals(blk.optString("type"))) sb.append(blk.optString("text"));
        }
        return sb.toString().trim();
    }

    static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }
}
