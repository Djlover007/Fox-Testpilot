package de.fox.testpilot;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Startbildschirm-Widget: Status und Startzeit je Shop. */
public class PilotWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        updateAll(c);
    }

    static String left(long target) {
        long min = Math.max(0, (target - System.currentTimeMillis()) / 60000L);
        long d = min / 1440, h = (min % 1440) / 60;
        if (d > 0) return "noch " + d + (d == 1 ? " Tag " : " Tage ") + h + " Std.";
        if (h > 0) return "noch " + h + " Std. " + (min % 60) + " Min.";
        return "noch " + min + " Min.";
    }

    static String line(JSONObject s) {
        String name = s.optString("name"), st = s.optString("status");
        long startAt = s.optLong("startAt"), endAt = s.optLong("endAt"), now = System.currentTimeMillis();
        SimpleDateFormat f = new SimpleDateFormat("EEE dd.MM. HH:mm", Locale.GERMANY);
        if (st.equals("open")) {
            if (endAt > now) return "🚀 " + name + ": offen, " + left(endAt);
            return "🚀 " + name + ": Bewerbung offen" + (s.optBoolean("endUnknown") || s.optString("end").isEmpty() ? "" : " bis " + s.optString("end"));
        }
        if (st.equals("soon")) {
            if (startAt > now) return "📅 " + name + ": " + f.format(new Date(startAt)) + " (" + left(startAt) + ")";
            if (startAt > 0) return "🚀 " + name + ": sollte jetzt starten";
            if (!s.optString("start").isEmpty()) return "📅 " + name + ": ab " + s.optString("start");
            return "📅 " + name + ": demnächst";
        }
        return "· " + name + ": keine Testaktion";
    }

    public static void updateAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, PilotWidget.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            JSONObject last = PilotChecker.getLast(c);
            JSONArray shops = last == null ? null : last.optJSONArray("shops");
            String l1 = "Noch nicht geprüft – App öffnen", l2 = "";
            if (shops != null && shops.length() > 0) {
                l1 = line(shops.getJSONObject(0));
                if (shops.length() > 1) l2 = line(shops.getJSONObject(1));
            }
            v.setTextViewText(R.id.w_l1, l1);
            v.setTextViewText(R.id.w_l2, l2);
            v.setTextViewText(R.id.w_time, last == null ? "" : new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(last.optLong("time"))));
            Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (open != null) {
                v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 77, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            m.updateAppWidget(ids, v);
        } catch (Exception ignored) {
        }
    }
}
