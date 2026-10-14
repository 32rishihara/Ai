package com.guardai.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches a page and extracts facts that help judge phishing / scam risk. */
public class WebAnalyzer {

    public static class Page {
        public String url = "", finalUrl = "", title = "", text = "", error = "";
        public int status = 0, forms = 0, passwordFields = 0, links = 0;
        public final List<String> flags = new ArrayList<>();
        public final List<String> redirects = new ArrayList<>();
    }

    private static final List<String> BAD_TLDS = Arrays.asList(
            "zip", "mov", "top", "xyz", "click", "icu", "sbs", "cfd", "gq", "tk", "ml", "cf", "ga", "work", "rest");
    private static final List<String> SHORTENERS = Arrays.asList(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "shorturl.at", "ow.ly");
    private static final List<String> BRANDS = Arrays.asList(
            "paypal", "apple", "google", "microsoft", "amazon", "facebook", "netflix", "instagram",
            "vietcombank", "techcombank", "vietinbank", "bidv", "mbbank", "momo", "zalo", "shopee", "lazada",
            "dhl", "fedex", "usps", "binance", "metamask");

    public static Page fetchSafe(String input) {
        Page p = new Page();
        try {
            fetch(input, p);
        } catch (Exception e) {
            p.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return p;
    }

    static void fetch(String input, Page p) throws Exception {
        String u = input.trim();
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        p.url = u;
        urlFlags(p, u);

        String cur = u;
        HttpURLConnection c = null;
        boolean done = false;
        for (int i = 0; i < 6 && !done; i++) {
            URL url = new URL(cur);
            c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) GuardAI/1.0");
            c.setRequestProperty("Accept", "text/html,*/*");
            int code = c.getResponseCode();
            p.status = code;
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                cur = new URL(url, c.getHeaderField("Location")).toString();
                p.redirects.add(cur);
                c.disconnect();
            } else {
                done = true;
            }
        }
        if (!done) throw new Exception("Too many redirects (" + p.redirects.size() + ")");

        p.finalUrl = cur;
        if (!host(cur).equals(host(u))) {
            p.flags.add("Redirects to a different domain: " + host(cur));
            urlFlags(p, cur);
        }

        String ctype = c.getContentType();
        InputStream is = p.status < 400 ? c.getInputStream() : c.getErrorStream();
        if (is == null) return;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0 && bos.size() < 200000) bos.write(buf, 0, n);
        is.close();
        c.disconnect();

        if (ctype != null && !ctype.toLowerCase().contains("html") && !ctype.toLowerCase().contains("text")) {
            p.text = "(non-text content: " + ctype + ")";
            return;
        }
        String html = bos.toString("UTF-8");

        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (m.find()) p.title = clean(m.group(1));

        p.forms = count(html, "(?is)<form\\b");
        p.passwordFields = count(html, "(?i)type\\s*=\\s*[\"']?password");
        p.links = count(html, "(?i)<a\\s");

        Matcher fm = Pattern.compile("(?is)<form[^>]*action\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);
        while (fm.find()) {
            try {
                String h = host(new URL(new URL(cur), fm.group(1)).toString());
                if (!h.equals(host(cur))) {
                    p.flags.add("A form sends data to another domain: " + h);
                }
            } catch (Exception ignored) {
            }
        }
        if (p.passwordFields > 0 && !cur.toLowerCase().startsWith("https")) {
            p.flags.add("Asks for a password over an unencrypted (http) connection");
        }

        String t = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ");
        t = clean(t);
        p.text = t.length() > 6000 ? t.substring(0, 6000) : t;
    }

    static void urlFlags(Page p, String url) throws Exception {
        URL u = new URL(url);
        String host = u.getHost().toLowerCase();
        if (!url.toLowerCase().startsWith("https")) addFlag(p, "Not using HTTPS");
        if (host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) addFlag(p, "Address is a raw IP, not a domain name");
        if (host.contains("xn--")) addFlag(p, "Punycode domain (can disguise look-alike characters)");
        if (url.contains("@")) addFlag(p, "URL contains '@' (can hide the real destination)");
        if (host.split("-", -1).length - 1 >= 3) addFlag(p, "Many hyphens in the domain");
        if (host.split("\\.").length >= 5) addFlag(p, "Very many subdomains");
        if (url.length() > 120) addFlag(p, "Very long URL");
        for (String t : BAD_TLDS) {
            if (host.endsWith("." + t)) addFlag(p, "Top-level domain '." + t + "' is frequently abused");
        }
        for (String s : SHORTENERS) {
            if (host.equals(s)) addFlag(p, "Link shortener hides the real destination");
        }
        String[] labels = host.split("\\.");
        String sld = labels.length >= 2 ? labels[labels.length - 2] : host;
        if (labels.length >= 3 && Arrays.asList("com", "co", "net", "org", "gov", "edu").contains(sld)) {
            sld = labels[labels.length - 3];
        }
        for (String b : BRANDS) {
            if (host.contains(b) && !sld.equals(b)) {
                addFlag(p, "Domain mentions '" + b + "' but is not that brand's main domain - verify it is official");
            }
        }
    }

    static void addFlag(Page p, String f) {
        if (!p.flags.contains(f)) p.flags.add(f);
    }

    static String host(String url) {
        try {
            return new URL(url).getHost().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    static int count(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static String clean(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("\\s+", " ").trim();
    }

    /** Text handed to the AI model. */
    public static String describe(Page p) {
        StringBuilder sb = new StringBuilder();
        sb.append("URL entered: ").append(p.url).append("\n");
        if (!p.finalUrl.isEmpty()) sb.append("Final URL after redirects: ").append(p.finalUrl).append("\n");
        if (p.status != 0) sb.append("HTTP status: ").append(p.status).append("\n");
        if (!p.error.isEmpty()) sb.append("Could not fetch the page: ").append(p.error).append("\n");
        if (!p.title.isEmpty()) sb.append("Title: ").append(p.title).append("\n");
        sb.append("Forms: ").append(p.forms).append(", password fields: ").append(p.passwordFields)
                .append(", links: ").append(p.links).append("\n");
        sb.append("Automatic red flags:\n");
        if (p.flags.isEmpty()) sb.append("- none\n");
        for (String f : p.flags) sb.append("- ").append(f).append("\n");
        if (!p.text.isEmpty()) sb.append("\nVisible page text (truncated):\n").append(p.text).append("\n");
        return sb.toString();
    }
}
