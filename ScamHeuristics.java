package com.guardai.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fast, offline scoring of a phone number. This runs inside the call-screening
 * service, which must answer within a few seconds, so no network is used here.
 */
public class ScamHeuristics {

    public static class Result {
        public int score;
        public String verdict;
        public final List<String> reasons = new ArrayList<>();

        public String reasonsText() {
            return reasons.isEmpty() ? "no warning signs" : String.join("; ", reasons);
        }
    }

    // Country codes frequently reported in "one-ring / callback" fraud.
    private static final String[] CALLBACK_FRAUD_PREFIXES = {
            "+224", "+225", "+232", "+233", "+234", "+216", "+212", "+222", "+252",
            "+375", "+370", "+371", "+372"
    };

    private static final Pattern REPEATED = Pattern.compile("(\\d)\\1{5,}");

    public static Result score(Context ctx, String number, Prefs prefs) {
        Result r = new Result();
        int s = 0;
        String d = Prefs.digits(number);

        if (number == null || d.isEmpty()) {
            s += 30;
            r.reasons.add("hidden or unknown caller ID");
        } else {
            if (prefs.isBlocked(number)) {
                s += 100;
                r.reasons.add("on your blocklist");
            }
            if (REPEATED.matcher(d).find()) {
                s += 10;
                r.reasons.add("unusual repeated digits");
            }
            for (String p : CALLBACK_FRAUD_PREFIXES) {
                if (number.startsWith(p)) {
                    s += 15;
                    r.reasons.add("country code often used in callback fraud");
                    break;
                }
            }
            if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                if (inContacts(ctx, number)) {
                    s -= 40;
                    r.reasons.add("saved contact");
                } else {
                    s += 20;
                    r.reasons.add("not in your contacts");
                }
            }
            int recent = recentCalls(ctx, d);
            if (recent >= 3) {
                s += 20;
                r.reasons.add(recent + " calls from this number in the last 7 days");
            }
        }

        s = Math.max(0, Math.min(100, s));
        r.score = s;
        r.verdict = s >= 70 ? "Likely spam" : s >= 40 ? "Suspicious" : "Looks normal";
        return r;
    }

    static boolean inContacts(Context c, String number) {
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            try (Cursor cur = c.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
                return cur != null && cur.moveToFirst();
            }
        } catch (Exception e) {
            return false;
        }
    }

    static int recentCalls(Context c, String digits) {
        if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return 0;
        String tail = Prefs.tail(digits);
        long since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        int n = 0;
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER},
                CallLog.Calls.DATE + " > ?", new String[]{String.valueOf(since)}, null)) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    String num = Prefs.digits(cur.getString(0));
                    if (!num.isEmpty() && Prefs.tail(num).equals(tail)) n++;
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    /** Keep first 4 and last 2 digits when sending a number to the cloud. */
    public static String mask(String number) {
        if (number == null || number.isEmpty()) return "(hidden)";
        String d = number.replaceAll("[^0-9+]", "");
        if (d.length() <= 7) return d;
        return d.substring(0, 4) + "***" + d.substring(d.length() - 2);
    }
}
