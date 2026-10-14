package com.guardai.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;

/**
 * Android calls this for every incoming call once the user makes GuardAI their
 * "call screening app". We score the number offline and, if the user enabled it,
 * reject calls that score above the threshold. Everything else rings normally.
 */
public class CallScreener extends CallScreeningService {

    @Override
    public void onScreenCall(Call.Details details) {
        CallResponse.Builder b = new CallResponse.Builder();

        if (details.getCallDirection() != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, b.build());
            return;
        }

        String number = null;
        try {
            Uri h = details.getHandle();
            if (h != null) number = h.getSchemeSpecificPart();
        } catch (Exception ignored) {
        }

        Prefs prefs = new Prefs(this);
        ScamHeuristics.Result r;
        try {
            r = ScamHeuristics.score(this, number, prefs);
        } catch (Exception e) {
            respondToCall(details, b.build());
            return;
        }

        boolean reject = prefs.autoReject() && r.score >= prefs.threshold();
        if (reject) {
            b.setDisallowCall(true);
            b.setRejectCall(true);
            b.setSkipNotification(true);
        }
        respondToCall(details, b.build());

        prefs.addHistory(number, r.score, r.verdict, r.reasonsText(), reject);
        if (reject || r.score >= 40) notifyUser(number, r, reject);
    }

    private void notifyUser(String number, ScamHeuristics.Result r, boolean rejected) {
        try {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("screening", "Call screening",
                    NotificationManager.IMPORTANCE_DEFAULT));
            String who = (number == null || number.isEmpty()) ? "Hidden number" : number;
            Notification n = new Notification.Builder(this, "screening")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle((rejected ? "Blocked: " : "Warning: ") + r.verdict + " (" + r.score + "/100)")
                    .setContentText(who + " - " + r.reasonsText())
                    .setStyle(new Notification.BigTextStyle().bigText(who + "\n" + r.reasonsText()))
                    .setAutoCancel(true)
                    .build();
            nm.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), n);
        } catch (Exception ignored) {
        }
    }
}
