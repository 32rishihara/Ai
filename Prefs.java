package com.guardai.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small wrapper around SharedPreferences (private to this app). */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("guardai", Context.MODE_PRIVATE);
    }

    public String apiKey() { return sp.getString("key", ""); }
    public void setApiKey(String k) { sp.edit().putString("key", k.trim()).apply(); }

    public String model() { return sp.getString("model", "claude-sonnet-5-5"); }
    public void setModel(String m) { sp.edit().putString("model", m.trim()).apply(); }

    public boolean autoReject() { return sp.getBoolean("autoReject", false); }
    public void setAutoReject(boolean b) { sp.edit().putBoolean("autoReject", b).apply(); }

    public int threshold() { return sp.getInt("threshold", 70); }
    public void setThreshold(int t) { sp.edit().putInt("threshold", Math.max(10, Math.min(100, t))).apply(); }

    // ---- blocklist (digits only) ----
    public List<String> blocklist() {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString("block", "").split("\n")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    public void addBlock(String number) {
        String d = digits(number);
        if (d.length() < 5) return;
        List<String> l = blocklist();
        if (!l.contains(d)) l.add(d);
        sp.edit().putString("block", String.join("\n", l)).apply();
    }

    public boolean isBlocked(String number) {
        String d = digits(number);
        if (d.isEmpty()) return false;
        for (String b : blocklist()) {
            if (tail(b).equals(tail(d))) return true;
        }
        return false;
    }

    static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    static String tail(String d) {
        return d.length() > 9 ? d.substring(d.length() - 9) : d;
    }

    // ---- screened-call history ----
    public synchronized void addHistory(String number, int score, String verdict, String reasons, boolean rejected) {
        try {
            JSONArray old = new JSONArray(sp.getString("hist", "[]"));
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("n", number == null ? "" : number);
            o.put("s", score);
            o.put("v", verdict);
            o.put("r", reasons);
            o.put("x", rejected);
            JSONArray n = new JSONArray();
            n.put(o);
            for (int i = 0; i < old.length() && i < 99; i++) n.put(old.get(i));
            sp.edit().putString("hist", n.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public JSONArray history() {
        try {
            return new JSONArray(sp.getString("hist", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
