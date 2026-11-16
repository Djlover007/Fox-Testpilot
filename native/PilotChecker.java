package de.fox.testpilot;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prüft die MediaMarkt-Testpiloten-Seite. Wird von der App und vom Hintergrund-Worker benutzt. */
public class PilotChecker {

    public static final String PAGE = "https://www.mediamarkt.de/de/campaign/testpiloten";
    static final String PREFS = "fox_testpilot";
    static final String CHANNEL = "testpilot";
    static final String CHANNEL_ALARM = "testpilot_alarm";
    static final String KIWI = "com.kiwibrowser.browser";

    /** z. B. "Diese Bewerbungsphase geht vom 14.09.2026 bis zum 17.09.2026" */
    static final Pattern PHASE = Pattern.compile(
        "bewerbungs(?:phase|zeitraum)[^0-9]{0,60}?(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})[^0-9]{1,25}?(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})",
        Pattern.CASE_INSENSITIVE);
    static final Pattern PRODUCT_ID = Pattern.compile("-(\\d{5,10})\\.html");

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean backgroundOn(Context c) {
        return prefs(c).getBoolean("background", true);
    }

    public static int intervalMinutes(Context c) {
        return Math.max(15, prefs(c).getInt("interval", 30));
    }

    public static boolean alarmOn(Context c) {
        return prefs(c).getBoolean("alarm", false);
    }

    public static JSONObject getLast(Context c) {
        try {
            String s = prefs(c).getString("last", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static JSONObject getApplied(Context c) {
        try {
            return new JSONObject(prefs(c).getString("applied", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void setApplied(Context c, String id, boolean on) {
        JSONObject a = getApplied(c);
        try {
            if (on) a.put(id, new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(new Date()));
            else a.remove(id);
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("applied", a.toString()).apply();
    }

    static int ymd(int d, int m, int y) {
        return y * 10000 + m * 100 + d;
    }

    static int today() {
        Calendar cal = Calendar.getInstance();
        return ymd(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR));
    }

    static String fmt(int v) {
        return String.format(Locale.GERMANY, "%02d.%02d.%04d", v % 100, (v / 100) % 100, v / 10000);
    }

    static String fmtShort(int v) {
        return String.format(Locale.GERMANY, "%02d.%02d.", v % 100, (v / 100) % 100);
    }

    static Set<String> stringSet(Context c, String key) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    static void saveSet(Context c, String key, Set<String> set, int max) {
        JSONArray a = new JSONArray();
        List<String> l = new ArrayList<>(set);
        for (int i = Math.max(0, l.size() - max); i < l.size(); i++) a.put(l.get(i));
        prefs(c).edit().putString(key, a.toString()).apply();
    }

    static void history(Context c, String line) {
        try {
            JSONArray h = new JSONArray(prefs(c).getString("history", "[]"));
            JSONArray n = new JSONArray();
            n.put(new SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(new Date()) + "  " + line);
            for (int i = 0; i < h.length() && i < 49; i++) n.put(h.get(i));
            prefs(c).edit().putString("history", n.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static boolean looksBlocked(String text) {
        String t = text.length() > 4000 ? text.substring(0, 4000) : text;
        t = t.toLowerCase(Locale.ROOT);
        return t.contains("access denied") || t.contains("captcha") || t.contains("zugriff verweigert")
                || t.contains("pardon our interruption") || t.contains("request unsuccessful");
    }

    /** Überschrift der Aktion, z. B. "Jetzt Testpilot werden" oder "Die nächsten Testpiloten starten demnächst!" */
    static String headline(String text) {
        for (String line : text.split("\n")) {
            String l = line.trim();
            String low = l.toLowerCase(Locale.GERMANY);
            if (l.length() > 8 && l.length() < 90 && low.contains("testpilot") && !low.contains("teilnahmebeding")) return l;
        }
        return "";
    }

    static String productNameFromUrl(String url) {
        try {
            String s = url.replaceAll(".*/product/_?", "").replaceAll("-\\d+\\.html.*$", "");
            StringBuilder b = new StringBuilder();
            for (String w : s.split("-")) {
                if (w.isEmpty()) continue;
                if (b.length() > 0) b.append(' ');
                b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
            return b.toString();
        } catch (Exception e) {
            return "Produkt";
        }
    }

    public static JSONObject run(Context c, boolean manual) throws Exception {
        return run(c, true, manual);
    }

    public static synchronized JSONObject run(Context c, boolean notify, boolean manual) throws Exception {
        JSONObject st = new JSONObject();
        st.put("time", System.currentTimeMillis());
        JSONObject prev = getLast(c);

        WebLoader.Result w = WebLoader.load(c, PAGE, 40000);
        if (w.error == null && looksBlocked(w.text)) w.error = "MediaMarkt hat den Abruf blockiert – später nochmal";
        if (w.error != null) {
            // Letzten guten Stand behalten, nur den Fehler dazuschreiben
            if (prev != null) {
                st = new JSONObject(prev.toString());
                st.put("time", System.currentTimeMillis());
            }
            st.put("error", w.error);
            prefs(c).edit().putString("last", st.toString()).apply();
            if (manual) history(c, "⚠️ " + w.error);
            return st;
        }
        String text = w.text;

        // Bewerbungsphase
        int start = 0, end = 0;
        Matcher m = PHASE.matcher(text);
        if (m.find()) {
            start = ymd(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            end = ymd(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        }
        int now = today();
        String status;
        if (start > 0 && end >= start) {
            if (now < start) status = "soon";
            else if (now <= end) status = "open";
            else status = "over";
        } else {
            status = text.toLowerCase(Locale.GERMANY).contains("demnächst") ? "soon" : "none";
        }
        String phaseKey = start > 0 ? start + "-" + end : "";

        // Produkte: bevorzugt die, die eindeutig im Testpiloten-Bereich stehen
        JSONArray all = w.items;
        boolean anyTest = false;
        for (int i = 0; i < all.length(); i++) if (all.optJSONObject(i).optBoolean("test")) anyTest = true;
        JSONArray products = new JSONArray();
        for (int i = 0; i < all.length() && products.length() < 40; i++) {
            JSONObject o = all.optJSONObject(i);
            if (anyTest && !o.optBoolean("test")) continue;
            if (o.optString("name").isEmpty()) o.put("name", productNameFromUrl(o.optString("url")));
            products.put(o);
        }

        // Neue Produkte erkennen
        boolean firstRun = !prefs(c).contains("seen");
        Set<String> seen = stringSet(c, "seen");
        String lastPhase = prefs(c).getString("phaseKey", "");
        boolean phaseChanged = !phaseKey.isEmpty() && !phaseKey.equals(lastPhase);
        Set<String> newIds = phaseChanged ? new HashSet<>() : stringSet(c, "newIds");
        List<String> freshNames = new ArrayList<>();
        for (int i = 0; i < products.length(); i++) {
            JSONObject o = products.getJSONObject(i);
            String id = o.optString("id");
            if (!seen.contains(id)) {
                if (!firstRun) {
                    newIds.add(id);
                    freshNames.add(o.optString("name"));
                }
                seen.add(id);
            }
            o.put("isNew", newIds.contains(id));
        }
        saveSet(c, "seen", seen, 400);
        saveSet(c, "newIds", newIds, 100);
        if (!phaseKey.isEmpty()) prefs(c).edit().putString("phaseKey", phaseKey).apply();

        st.put("status", status);
        st.put("start", start > 0 ? fmt(start) : "");
        st.put("end", end > 0 ? fmt(end) : "");
        st.put("startYmd", start);
        st.put("endYmd", end);
        st.put("today", now);
        st.put("headline", headline(text));
        st.put("products", products);
        st.put("allLinks", all.length());

        // Benachrichtigungen
        Set<String> notified = stringSet(c, "notified");
        JSONObject applied = getApplied(c);
        int appliedCount = 0;
        for (int i = 0; i < products.length(); i++) if (applied.has(products.getJSONObject(i).optString("id"))) appliedCount++;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < products.length() && i < 4; i++) {
            if (names.length() > 0) names.append(", ");
            names.append(products.getJSONObject(i).optString("name"));
        }
        if (products.length() > 4) names.append(" …");
        boolean told = false;

        if (notify && !phaseKey.isEmpty()) {
            if (status.equals("open") && notified.add("open:" + phaseKey)) {
                showNotification(c, 101, "🚀 Testpiloten: Bewerbung ist offen!",
                        "Bewerben bis " + fmtShort(end) + (products.length() > 0 ? " – " + products.length() + " Testprodukte: " + names : ""),
                        alarmOn(c), PAGE);
                history(c, "🚀 Bewerbungsphase offen bis " + fmt(end));
                told = true;
            } else if (status.equals("soon") && notified.add("soon:" + phaseKey)) {
                showNotification(c, 102, "📅 Neue Testpiloten-Runde angekündigt",
                        "Bewerbung vom " + fmtShort(start) + " bis " + fmtShort(end), false, PAGE);
                history(c, "📅 Neue Runde angekündigt: " + fmt(start) + " – " + fmt(end));
                told = true;
            }
            if (status.equals("open") && now == end && notified.add("last:" + phaseKey)) {
                showNotification(c, 103, "⏰ Heute letzter Tag für die Testpiloten",
                        products.length() > 0 ? "Du hast dich für " + appliedCount + " von " + products.length() + " Produkten beworben." : "Heute endet die Bewerbungsphase.",
                        false, PAGE);
                history(c, "⏰ Letzter Bewerbungstag");
            }
        }
        if (notify && !freshNames.isEmpty() && !told) {
            String n = freshNames.size() == 1 ? freshNames.get(0) : android.text.TextUtils.join(", ", freshNames.subList(0, Math.min(4, freshNames.size())));
            showNotification(c, 104, "🆕 " + freshNames.size() + " neue" + (freshNames.size() == 1 ? "s Testprodukt" : " Testprodukte"), n, false, PAGE);
        }
        if (!freshNames.isEmpty()) history(c, "🆕 Neu: " + android.text.TextUtils.join(", ", freshNames));
        saveSet(c, "notified", notified, 60);

        if (prev == null || !status.equals(prev.optString("status"))) {
            history(c, "Status: " + statusText(status) + (start > 0 ? " (" + fmt(start) + " – " + fmt(end) + ")" : ""));
        }
        prefs(c).edit().putString("last", st.toString()).apply();
        return st;
    }

    static String statusText(String s) {
        switch (s) {
            case "open": return "Bewerbung offen";
            case "soon": return "Nächste Runde angekündigt";
            case "over": return "Runde beendet";
            default: return "Gerade keine Testaktion";
        }
    }

    static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean notificationsAllowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    /** alarm = lauter Alarmton. target = Link, der beim Tippen geöffnet wird (Kiwi, sonst Browser). */
    public static boolean showNotification(Context c, int id, String title, String body, boolean alarm, String target) {
        if (!notificationsAllowed(c)) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Testpiloten-Meldungen", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Neue Testpiloten-Runden und Produkte");
            nm.createNotificationChannel(ch);
            NotificationChannel al = new NotificationChannel(CHANNEL_ALARM, "Bewerbung offen (Alarm)", NotificationManager.IMPORTANCE_HIGH);
            al.setDescription("Lauter Ton, sobald die Bewerbungsphase startet");
            al.enableVibration(true);
            al.setVibrationPattern(new long[] { 0, 700, 300, 700 });
            al.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(al);
        }
        Intent open;
        if (target != null && !target.isEmpty()) {
            open = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target));
            if (isInstalled(c, KIWI)) open.setPackage(KIWI);
        } else {
            open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        }
        PendingIntent pi = null;
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, alarm ? CHANNEL_ALARM : CHANNEL)
                .setSmallIcon(c.getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(alarm ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true);
        if (pi != null) b.setContentIntent(pi);
        try {
            NotificationManagerCompat.from(c).notify(id, b.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
