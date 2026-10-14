package com.guardai.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int ACCENT = 0xFF1A5FD0;
    private static final int BG = 0xFFF2F4F8;

    private static final String CHAT_SYSTEM =
            "You are GuardAI, a helpful assistant inside an Android app. You are strong at programming "
            + "(give complete, correct code in fenced blocks and explain briefly), at explaining things, "
            + "and at everyday conversation. You also help the user judge whether calls, messages and "
            + "websites are scams. Reply in the user's language. Keep answers compact for a phone screen.";

    private static final String SCAM_SYSTEM =
            "You are a fraud-detection analyst built into a phone app. Analyze the call transcript or "
            + "message the user gives you. Output:\n"
            + "1) VERDICT: SCAM / LIKELY SCAM / SUSPICIOUS / LIKELY LEGITIMATE, with a risk score 0-100.\n"
            + "2) Red flags found (quote short phrases).\n"
            + "3) Scam type if any (bank or authority impersonation, tech support, investment, romance, "
            + "parcel/delivery, prize, callback fraud, OTP theft, etc.).\n"
            + "4) What to do right now (never share OTP/PIN/passwords, hang up, call the official number "
            + "yourself, etc.).\n"
            + "Be concise. Reply in the same language as the input. If there is too little information, "
            + "say so instead of guessing. Transcripts may be noisy speech-to-text.";

    private static final String WEB_SYSTEM =
            "You are a web-safety analyst. You get facts extracted from a web page (URL flags, forms, "
            + "visible text). Output:\n"
            + "1) VERDICT: SAFE / SUSPICIOUS / DANGEROUS with confidence.\n"
            + "2) What the site is and what it wants from the visitor.\n"
            + "3) Specific reasons (phishing, fake shop, investment scam, malware bait, impersonation, "
            + "or legitimate signs).\n"
            + "4) What the user should do.\n"
            + "You cannot see the rendered page, domain age or reputation databases - say so when it "
            + "limits your confidence. Be concise and reply in the user's language if the page text suggests one.";

    private static final String LOG_SYSTEM =
            "You are a fraud-detection analyst. You get a masked recent call log plus an offline "
            + "risk score per number. Identify patterns of spam, robocalls, scam campaigns, one-ring "
            + "callback fraud (very short missed calls from unknown or foreign numbers), repeated calls "
            + "from the same unknown number, and anything else worrying. List the numbers to be careful "
            + "with and give practical advice. Be concise.";

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    private FrameLayout content;
    private final View[] tabs = new View[4];
    private final Button[] tabButtons = new Button[4];

    // chat
    private LinearLayout chatList;
    private ScrollView chatScroll;
    private EditText chatInput;
    private JSONArray chatHistory = new JSONArray();
    private boolean chatBusy = false;

    // web
    private EditText webUrl;
    private TextView webOut;

    // calls
    private TextView screeningStatus, historyView, callsOut;
    private EditText blockInput, transcript;
    private Button listenBtn;
    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private int lastAnalyzedLen = 0;

    private volatile boolean busy = false;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Color.WHITE);
        String[] names = {"Chat", "Web", "Calls", "Settings"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Button t = new Button(this);
            t.setText(names[i]);
            t.setAllCaps(false);
            t.setBackgroundColor(Color.TRANSPARENT);
            t.setOnClickListener(v -> show(idx));
            tabButtons[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        tabs[0] = buildChat();
        tabs[1] = buildWeb();
        tabs[2] = buildCalls();
        tabs[3] = buildSettings();
        for (View v : tabs) content.addView(v, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        show(prefs.apiKey().isEmpty() ? 3 : 0);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCallsTab();
    }

    @Override
    protected void onDestroy() {
        stopListen();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] results) {
        super.onRequestPermissionsResult(rc, perms, results);
        toast("Permissions updated. Tap the button again if it didn't start.");
        refreshCallsTab();
    }

    private void handleIntent(Intent in) {
        if (in == null || !Intent.ACTION_SEND.equals(in.getAction())) return;
        String shared = in.getStringExtra(Intent.EXTRA_TEXT);
        if (shared == null || shared.trim().isEmpty()) return;
        shared = shared.trim();
        if (shared.matches("(?is)^https?://\\S+$")) {
            webUrl.setText(shared);
            show(1);
            analyzeWeb();
        } else {
            transcript.setText(shared);
            show(2);
            toast("Text received. Tap \"Analyze text\" to check it.");
        }
    }

    private void show(int i) {
        for (int k = 0; k < 4; k++) {
            tabs[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabButtons[k].setTextColor(k == i ? ACCENT : 0xFF666666);
            tabButtons[k].setTypeface(null, k == i ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    // ---------------------------------------------------------------- UI helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        return b;
    }

    private TextView label(String text, boolean heading) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(heading ? Color.BLACK : 0xFF444444);
        t.setTextSize(heading ? 18 : 14);
        if (heading) t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(heading ? 14 : 4), 0, dp(4));
        return t;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        return e;
    }

    private TextView outputBox() {
        TextView t = new TextView(this);
        t.setTextIsSelectable(true);
        t.setTextSize(15);
        t.setTextColor(Color.BLACK);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(8), dp(16), dp(16));
        return l;
    }

    private ScrollView scroll(LinearLayout inner) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(inner);
        return s;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        return lp;
    }

    // ---------------------------------------------------------------- Chat tab

    private View buildChat() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        chatScroll = new ScrollView(this);
        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatList.setPadding(dp(12), dp(12), dp(12), dp(12));
        chatScroll.addView(chatList);
        box.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        row.setBackgroundColor(Color.WHITE);

        chatInput = edit("Ask anything, or paste code...");
        chatInput.setMaxLines(5);
        chatInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        row.addView(chatInput, new LinearLayout.LayoutParams(0, -2, 1f));

        Button send = button("Send");
        send.setOnClickListener(v -> sendChat());
        row.addView(send);

        Button fresh = button("New");
        fresh.setOnClickListener(v -> {
            chatHistory = new JSONArray();
            chatList.removeAllViews();
        });
        row.addView(fresh);

        box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        addBubble(false, "Hi! I can chat, write and explain code, and check suspicious calls, messages "
                + "and websites. Add your API key in Settings first.");
        return box;
    }

    private TextView addBubble(boolean mine, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextIsSelectable(true);
        t.setTextSize(15);
        t.setTextColor(Color.BLACK);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(mine ? 0xFFD6E4FF : Color.WHITE);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(mine ? dp(48) : 0, dp(4), mine ? 0 : dp(48), dp(4));
        lp.gravity = mine ? Gravity.END : Gravity.START;
        chatList.addView(t, lp);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        return t;
    }

    private void sendChat() {
        final String text = chatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        if (chatBusy) {
            toast("Still answering...");
            return;
        }
        chatBusy = true;
        chatInput.setText("");
        addBubble(true, text);
        final TextView pending = addBubble(false, "...");
        final JSONArray snapshot;
        try {
            chatHistory.put(new JSONObject().put("role", "user").put("content", text));
            snapshot = new JSONArray(chatHistory.toString());
        } catch (Exception e) {
            chatBusy = false;
            return;
        }
        io.execute(() -> {
            String res;
            boolean ok = true;
            try {
                res = Claude.ask(prefs, CHAT_SYSTEM, snapshot, 2048);
            } catch (Exception e) {
                res = "Error: " + e.getMessage();
                ok = false;
            }
            final String r = res;
            final boolean success = ok;
            ui.post(() -> {
                pending.setText(r);
                try {
                    if (success) {
                        chatHistory.put(new JSONObject().put("role", "assistant").put("content", r));
                    } else {
                        chatHistory.remove(chatHistory.length() - 1);
                    }
                } catch (Exception ignored) {
                }
                chatBusy = false;
            });
        });
    }

    // ---------------------------------------------------------------- Web tab

    private View buildWeb() {
        LinearLayout col = column();
        col.addView(label("Website checker", true));
        col.addView(label("Paste a link (or share one to GuardAI from your browser or messages). "
                + "The app loads the page, looks for phishing and scam signs, and asks the AI for a verdict.", false));
        webUrl = edit("https://example.com");
        webUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(webUrl, gap());
        Button go = button("Analyze website");
        go.setOnClickListener(v -> analyzeWeb());
        col.addView(go, gap());
        webOut = outputBox();
        col.addView(webOut, gap());
        return scroll(col);
    }

    private void analyzeWeb() {
        final String u = webUrl.getText().toString().trim();
        if (u.isEmpty()) {
            toast("Enter a URL first.");
            return;
        }
        if (busy) {
            toast("Still working on the previous request...");
            return;
        }
        busy = true;
        webOut.setText("Fetching page...");
        io.execute(() -> {
            String res;
            try {
                WebAnalyzer.Page p = WebAnalyzer.fetchSafe(u);
                ui.post(() -> webOut.setText("Asking the AI..."));
                res = Claude.ask(prefs, WEB_SYSTEM, Claude.single(WebAnalyzer.describe(p)), 1500);
                if (!p.flags.isEmpty()) {
                    StringBuilder sb = new StringBuilder("Automatic checks:\n");
                    for (String f : p.flags) sb.append("- ").append(f).append("\n");
                    res = sb + "\n" + res;
                }
            } catch (Exception e) {
                res = "Error: " + e.getMessage();
            }
            final String r = res;
            ui.post(() -> {
                webOut.setText(r);
                busy = false;
            });
        });
    }

    // ---------------------------------------------------------------- Calls tab

    private View buildCalls() {
        LinearLayout col = column();

        col.addView(label("Call screening", true));
        screeningStatus = label("", false);
        col.addView(screeningStatus);
        col.addView(label("Once GuardAI is your call screening app, every incoming call is scored on-device "
                + "(unknown number, repeat callers, callback-fraud prefixes, your blocklist). "
                + "Optionally it auto-rejects high scores (see Settings).", false));
        Button role = button("Turn on call screening");
        role.setOnClickListener(v -> enableScreening());
        col.addView(role, gap());
        Button perms = button("Grant permissions (contacts, call log, mic, notifications)");
        perms.setOnClickListener(v -> requestPermissions(new String[]{
                Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 10));
        col.addView(perms, gap());

        col.addView(label("Recently screened", true));
        historyView = outputBox();
        col.addView(historyView, gap());

        col.addView(label("Blocklist", true));
        blockInput = edit("Number to block, e.g. +84901234567");
        blockInput.setInputType(InputType.TYPE_CLASS_PHONE);
        col.addView(blockInput, gap());
        Button block = button("Add to blocklist");
        block.setOnClickListener(v -> {
            prefs.addBlock(blockInput.getText().toString());
            blockInput.setText("");
            toast("Added.");
        });
        col.addView(block, gap());

        col.addView(label("Check a call, SMS or chat", true));
        col.addView(label("Paste a message, or tap \"Start listening\" while the call is on speakerphone and "
                + "the app transcribes what it hears (Android does not let apps record call audio directly). "
                + "While listening it re-checks the conversation about every 30 seconds.", false));
        transcript = edit("Transcript or message text");
        transcript.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        transcript.setMinLines(4);
        transcript.setGravity(Gravity.TOP);
        col.addView(transcript, gap());
        listenBtn = button("Start listening (speakerphone)");
        listenBtn.setOnClickListener(v -> toggleListen());
        col.addView(listenBtn, gap());
        Button analyze = button("Analyze text");
        analyze.setOnClickListener(v -> analyzeTranscript());
        col.addView(analyze, gap());
        Button clear = button("Clear");
        clear.setOnClickListener(v -> {
            transcript.setText("");
            lastAnalyzedLen = 0;
        });
        col.addView(clear, gap());

        col.addView(label("Call log check", true));
        Button log = button("Analyze my recent calls");
        log.setOnClickListener(v -> analyzeCallLog());
        col.addView(log, gap());

        callsOut = outputBox();
        col.addView(callsOut, gap());
        return scroll(col);
    }

    private boolean screeningHeld() {
        try {
            RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            return rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshCallsTab() {
        if (screeningStatus == null) return;
        boolean on = screeningHeld();
        screeningStatus.setText("Status: " + (on ? "ON" : "OFF")
                + "   |   Auto-reject: " + (prefs.autoReject() ? "ON (score >= " + prefs.threshold() + ")" : "OFF"));
        JSONArray h = prefs.history();
        if (h.length() == 0) {
            historyView.setText("Nothing yet. Incoming calls will appear here.");
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length() && i < 15; i++) {
            JSONObject o = h.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString("n");
            sb.append(f.format(new Date(o.optLong("t")))).append("  ")
                    .append(n.isEmpty() ? "(hidden)" : n).append("\n   ")
                    .append(o.optString("v")).append(" ").append(o.optInt("s")).append("/100")
                    .append(o.optBoolean("x") ? " - REJECTED" : "")
                    .append("\n   ").append(o.optString("r")).append("\n\n");
        }
        historyView.setText(sb.toString().trim());
    }

    private void enableScreening() {
        try {
            RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                toast("This phone doesn't offer call screening apps.");
                return;
            }
            if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                toast("Call screening is already on.");
                return;
            }
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 77);
        } catch (Exception e) {
            toast("Couldn't open the setting: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        refreshCallsTab();
    }

    private void analyzeTranscript() {
        final String t = transcript.getText().toString().trim();
        if (t.isEmpty()) {
            toast("Nothing to analyze yet.");
            return;
        }
        lastAnalyzedLen = t.length();
        runAnalysis(SCAM_SYSTEM, () -> "Call transcript or message:\n\n" + t, callsOut);
    }

    private void analyzeCallLog() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG}, 11);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Send call log to the AI?")
                .setMessage("Your last ~50 calls will be summarized with numbers partly masked "
                        + "(e.g. +849***67, no names) and sent to Anthropic's API using your key.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Analyze", (d, w) -> runAnalysis(LOG_SYSTEM, this::buildCallLogSummary, callsOut))
                .show();
    }

    private String buildCallLogSummary() throws Exception {
        StringBuilder sb = new StringBuilder("Recent calls (newest first):\n");
        Set<String> scored = new HashSet<>();
        StringBuilder scores = new StringBuilder("\nOffline risk score per number:\n");
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
                        CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME},
                null, null, CallLog.Calls.DATE + " DESC")) {
            int rows = 0;
            while (c != null && c.moveToNext() && rows < 50) {
                rows++;
                String num = c.getString(0);
                int type = c.getInt(1);
                String kind = type == CallLog.Calls.INCOMING_TYPE ? "incoming"
                        : type == CallLog.Calls.OUTGOING_TYPE ? "outgoing"
                        : type == CallLog.Calls.MISSED_TYPE ? "missed"
                        : type == CallLog.Calls.REJECTED_TYPE ? "rejected"
                        : type == CallLog.Calls.BLOCKED_TYPE ? "blocked" : "other";
                boolean saved = c.getString(4) != null && !c.getString(4).isEmpty();
                sb.append(f.format(new Date(c.getLong(2)))).append(" | ")
                        .append(ScamHeuristics.mask(num)).append(" | ").append(kind)
                        .append(" | ").append(c.getInt(3)).append("s | ")
                        .append(saved ? "saved contact" : "unsaved").append("\n");
                if (num != null && !num.isEmpty() && scored.size() < 30 && scored.add(num)) {
                    ScamHeuristics.Result r = ScamHeuristics.score(this, num, prefs);
                    scores.append(ScamHeuristics.mask(num)).append(": ").append(r.score)
                            .append(" (").append(r.reasonsText()).append(")\n");
                }
            }
            if (rows == 0) throw new Exception("The call log is empty.");
        }
        return sb.append(scores).toString();
    }

    // ---- live listening (speech-to-text on the microphone) ----

    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (!listening) return;
            String t = transcript.getText().toString().trim();
            if (t.length() > lastAnalyzedLen + 40 && !busy) analyzeTranscript();
            ui.postDelayed(this, 30000);
        }
    };

    private void toggleListen() {
        if (listening) {
            stopListen();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 12);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition isn't available on this phone.");
            return;
        }
        listening = true;
        listenBtn.setText("Stop listening");
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int t, Bundle b) { }

            @Override
            public void onError(int e) {
                if (!listening) return;
                ui.postDelayed(() -> {
                    if (listening && recognizer != null) {
                        recognizer.cancel();
                        startRecognizing();
                    }
                }, 600);
            }

            @Override
            public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) {
                    transcript.append((transcript.length() > 0 ? " " : "") + r.get(0));
                }
                startRecognizing();
            }
        });
        startRecognizing();
        ui.postDelayed(autoTick, 30000);
    }

    private void startRecognizing() {
        if (!listening || recognizer == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        try {
            recognizer.startListening(i);
        } catch (Exception ignored) {
        }
    }

    private void stopListen() {
        listening = false;
        ui.removeCallbacks(autoTick);
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        if (listenBtn != null) listenBtn.setText("Start listening (speakerphone)");
    }

    private void runAnalysis(final String system, final Callable<String> promptSupplier, final TextView out) {
        if (busy) {
            toast("Still working on the previous request...");
            return;
        }
        busy = true;
        out.setText("Analyzing...");
        io.execute(() -> {
            String res;
            try {
                res = Claude.ask(prefs, system, Claude.single(promptSupplier.call()), 1200);
            } catch (Exception e) {
                res = "Error: " + e.getMessage();
            }
            final String r = res;
            ui.post(() -> {
                out.setText(r);
                busy = false;
            });
        });
    }

    // ---------------------------------------------------------------- Settings tab

    private View buildSettings() {
        LinearLayout col = column();
        col.addView(label("Settings", true));

        col.addView(label("Anthropic API key (get one at console.anthropic.com)", false));
        final EditText key = edit("sk-ant-...");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(prefs.apiKey());
        col.addView(key, gap());

        col.addView(label("Model", false));
        final EditText model = edit("claude-sonnet-5-5");
        model.setText(prefs.model());
        col.addView(model, gap());

        final Switch auto = new Switch(this);
        auto.setText("Auto-reject calls with a high spam score");
        auto.setChecked(prefs.autoReject());
        col.addView(auto, gap());

        col.addView(label("Reject threshold (10-100). Higher = fewer calls blocked.", false));
        final EditText thr = edit("70");
        thr.setInputType(InputType.TYPE_CLASS_NUMBER);
        thr.setText(String.valueOf(prefs.threshold()));
        col.addView(thr, gap());

        Button save = button("Save");
        save.setOnClickListener(v -> {
            prefs.setApiKey(key.getText().toString());
            String m = model.getText().toString().trim();
            prefs.setModel(m.isEmpty() ? "claude-sonnet-5-5" : m);
            prefs.setAutoReject(auto.isChecked());
            try {
                prefs.setThreshold(Integer.parseInt(thr.getText().toString().trim()));
            } catch (Exception ignored) {
            }
            refreshCallsTab();
            toast("Saved.");
        });
        col.addView(save, gap());

        col.addView(label("Privacy", true));
        col.addView(label("- Your API key is stored privately on this phone and sent only to api.anthropic.com.\n"
                + "- Call screening and number scoring run on-device; nothing is uploaded.\n"
                + "- Text you analyze, web page text, and (if you confirm) a masked call log are sent to "
                + "Anthropic's API with your key.\n"
                + "- The app never records call audio. \"Listen\" uses the mic with Android's speech "
                + "recognizer, which may send audio to your phone's speech service.\n"
                + "- Scores are hints, not proof. A legitimate caller can look suspicious, so keep "
                + "auto-reject conservative.", false));
        return scroll(col);
    }
}
