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

/** Prüft die Testpiloten-Seiten von MediaMarkt und Saturn. Wird von der App und vom Hintergrund-Worker benutzt. */
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

    // ---- Personen im Haushalt: jede mit eigenen Daten, eigenen Konten und eigener Bewerbungsliste ----

    public static JSONArray persons(Context c) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("persons", "[]"));
            if (a.length() > 0) return a;
        } catch (Exception ignored) {
        }
        JSONArray a = new JSONArray();
        try {
            a.put(new JSONObject().put("id", "p1").put("label", "Ich"));
        } catch (Exception ignored) {
        }
        return a;
    }

    public static String activePerson(Context c) {
        String id = prefs(c).getString("activePerson", "p1");
        JSONArray a = persons(c);
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return id;
        return a.optJSONObject(0).optString("id");
    }

    /** Die erste Person behält die bisherigen Speicher-Namen. */
    static String sfx(String personId) {
        return "p1".equals(personId) ? "" : "_" + personId;
    }

    public static String profileKey(Context c) {
        return "profile" + sfx(activePerson(c));
    }

    public static JSONObject getApplied(Context c) {
        return getApplied(c, activePerson(c));
    }

    public static JSONObject getApplied(Context c, String personId) {
        try {
            return new JSONObject(prefs(c).getString("applied" + sfx(personId), "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void setApplied(Context c, String id, boolean on) {
        setApplied(c, id, on, null, null);
    }

    // ---- Meine Bewerbungen: Name, Shop, Ergebnis, Bewertungs-Frist je Person ----

    public static JSONObject getInfo(Context c, String personId) {
        try {
            return new JSONObject(prefs(c).getString("ainfo" + sfx(personId), "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void saveInfo(Context c, String personId, JSONObject info) {
        prefs(c).edit().putString("ainfo" + sfx(personId), info.toString()).apply();
    }

    static String personLabel(Context c, String personId) {
        JSONArray a = persons(c);
        for (int i = 0; i < a.length(); i++) if (personId.equals(a.optJSONObject(i).optString("id"))) return a.optJSONObject(i).optString("label");
        return "";
    }

    /** result: "" (offen), "yes" (ausgewählt), "no" (nicht ausgewählt). due: Bewertungs-Frist als JJJJ-MM-TT oder "". */
    public static void setResult(Context c, String aid, String result, String due) throws Exception {
        String pid = activePerson(c);
        JSONObject info = getInfo(c, pid);
        JSONObject o = info.optJSONObject(aid);
        if (o == null) o = new JSONObject();
        o.put("result", result);
        o.put("due", due);
        info.put(aid, o);
        saveInfo(c, pid, info);
        androidx.work.WorkManager wm = androidx.work.WorkManager.getInstance(c);
        String base = "fox-review-" + pid + "-" + aid;
        wm.cancelUniqueWork(base + "-3");
        wm.cancelUniqueWork(base + "-0");
        if (!"yes".equals(result) || due == null || due.isEmpty()) return;
        Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).parse(due);
        if (d == null) return;
        long day9 = d.getTime() + 9 * 3600000L;
        String who = persons(c).length() > 1 ? " (" + personLabel(c, pid) + ")" : "";
        String name = o.optString("name", "Testprodukt");
        String nice = new SimpleDateFormat("dd.MM.", Locale.GERMANY).format(d);
        int nid = 5000 + Math.abs((pid + aid).hashCode() % 4000);
        scheduleReminder(c, base + "-3", day9 - 3 * 86400000L, nid, "✍️ Bewertung in 3 Tagen fällig" + who, name + " – Frist " + nice, false);
        scheduleReminder(c, base + "-0", day9, nid + 1, "⏰ Bewertung heute fällig" + who, name + " – heute ist der letzte Tag", false);
    }

    public static void removeInfo(Context c, String aid) throws Exception {
        setResult(c, aid, "", "");
        String pid = activePerson(c);
        JSONObject info = getInfo(c, pid);
        info.remove(aid);
        saveInfo(c, pid, info);
        setApplied(c, aid, false);
    }

    static void scheduleReminder(Context c, String unique, long at, int nid, String title, String body, boolean alarm) {
        try {
            long delay = at - System.currentTimeMillis();
            if (delay <= 0) return;
            androidx.work.Data data = new androidx.work.Data.Builder().putInt("id", nid).putString("title", title).putString("body", body).putBoolean("alarm", alarm).build();
            androidx.work.OneTimeWorkRequest req = new androidx.work.OneTimeWorkRequest.Builder(ReminderWorker.class)
                    .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS).setInputData(data).build();
            androidx.work.WorkManager.getInstance(c).enqueueUniqueWork(unique, androidx.work.ExistingWorkPolicy.REPLACE, req);
        } catch (Exception ignored) {
        }
    }

    public static void setApplied(Context c, String id, boolean on, String name, String shop) {
        try {
            String pid = activePerson(c);
            JSONObject info = getInfo(c, pid);
            if (on) {
                JSONObject o = info.optJSONObject(id);
                if (o == null) o = new JSONObject();
                if (name != null && !name.isEmpty()) o.put("name", name);
                if (shop != null && !shop.isEmpty()) o.put("shop", shop);
                if (!o.has("date")) o.put("date", new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(new Date()));
                info.put(id, o);
                saveInfo(c, pid, info);
            } else if (info.has(id) && info.optJSONObject(id).optString("result").isEmpty()) {
                info.remove(id);
                saveInfo(c, pid, info);
            }
        } catch (Exception ignored) {
        }
        JSONObject a = getApplied(c);
        try {
            if (on) a.put(id, new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(new Date()));
            else a.remove(id);
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("applied" + sfx(activePerson(c)), a.toString()).apply();
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

    /** Die überwachten Shops: Schlüssel, Name, Testpiloten-Seite. */
    static final String[][] SHOPS = {
        { "mm", "MediaMarkt", PAGE },
        { "saturn", "Saturn", "https://www.saturn.de/de/campaign/testpiloten" },
    };

    public static JSONObject run(Context c, boolean manual) throws Exception {
        return run(c, true, manual);
    }

    public static synchronized JSONObject run(Context c, boolean notify, boolean manual) throws Exception {
        JSONObject prev = getLast(c);
        JSONArray prevShops = prev == null ? null : prev.optJSONArray("shops");
        JSONObject st = new JSONObject();
        st.put("time", System.currentTimeMillis());
        JSONArray shops = new JSONArray();
        for (int i = 0; i < SHOPS.length; i++) {
            JSONObject before = null;
            for (int j = 0; prevShops != null && j < prevShops.length(); j++) {
                if (SHOPS[i][0].equals(prevShops.getJSONObject(j).optString("key"))) before = prevShops.getJSONObject(j);
            }
            shops.put(checkShop(c, i, before, notify, manual));
        }
        st.put("shops", shops);
        prefs(c).edit().putString("last", st.toString()).commit();
        PilotWidget.updateAll(c);
        return st;
    }

    static JSONObject checkShop(Context c, int idx, JSONObject prev, boolean notify, boolean manual) throws Exception {
        String key = SHOPS[idx][0], shop = SHOPS[idx][1], page = SHOPS[idx][2];
        String sfx = key.equals("mm") ? "" : "_" + key;   // MediaMarkt behält die alten Speicher-Namen
        String idPrefix = key.equals("mm") ? "" : key.substring(0, 1);
        int nid = 100 * (idx + 1);
        JSONObject st = new JSONObject();
        st.put("key", key);
        st.put("name", shop);
        st.put("url", page);

        WebLoader.Result w = WebLoader.load(c, page, 40000);
        if (w.error == null && looksBlocked(w.text)) w.error = shop + " hat den Abruf blockiert – später nochmal";
        if (w.error != null) {
            // Letzten guten Stand behalten, nur den Fehler dazuschreiben
            if (prev != null) st = new JSONObject(prev.toString());
            st.put("error", w.error);
            if (manual) history(c, "⚠️ " + shop + ": " + w.error);
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
        // Countdown auf der Seite ("Tage Stunden Minuten") -> genaue Uhrzeit
        long startAt = 0, endAt = 0;
        long cd = countdownMs(text);
        if (cd >= 0) {
            long target = snap(System.currentTimeMillis() + cd);
            int targetDay = ymdOf(target);
            if (status.equals("open") && targetDay > start) {
                endAt = target;                       // läuft schon: Countdown zählt bis zum Ende
            } else {
                startAt = target;                     // zählt bis zum Start
                status = target > System.currentTimeMillis() ? "soon" : "open";
                if (start == 0) {
                    start = targetDay;
                    end = targetDay;
                    st.put("endUnknown", true);
                }
            }
        }
        String phaseKey = start > 0 ? start + "-" + end : "";
        if (startAt > System.currentTimeMillis()) scheduleStartCheck(c, key, startAt);

        // Produkte: bevorzugt die, die eindeutig im Testpiloten-Bereich stehen
        JSONArray all = w.items;
        boolean anyTest = false;
        for (int i = 0; i < all.length(); i++) if (all.optJSONObject(i).optBoolean("test")) anyTest = true;
        JSONArray products = new JSONArray();
        for (int i = 0; i < all.length() && products.length() < 40; i++) {
            JSONObject o = all.optJSONObject(i);
            if (anyTest && !o.optBoolean("test")) continue;
            if (o.optString("name").isEmpty()) o.put("name", productNameFromUrl(o.optString("url")));
            o.put("aid", idPrefix + o.optString("id"));
            products.put(o);
        }

        // Neue Produkte erkennen
        boolean firstRun = !prefs(c).contains("seen" + sfx);
        Set<String> seen = stringSet(c, "seen" + sfx);
        String lastPhase = prefs(c).getString("phaseKey" + sfx, "");
        boolean phaseChanged = !phaseKey.isEmpty() && !phaseKey.equals(lastPhase);
        Set<String> newIds = phaseChanged ? new HashSet<>() : stringSet(c, "newIds" + sfx);
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
        saveSet(c, "seen" + sfx, seen, 400);
        saveSet(c, "newIds" + sfx, newIds, 100);
        if (!phaseKey.isEmpty()) prefs(c).edit().putString("phaseKey" + sfx, phaseKey).apply();

        st.put("status", status);
        st.put("start", start > 0 ? fmt(start) : "");
        st.put("end", end > 0 ? fmt(end) : "");
        st.put("startYmd", start);
        st.put("endYmd", end);
        st.put("startAt", startAt);
        st.put("endAt", endAt);
        st.put("headline", headline(text));
        st.put("products", products);

        // Benachrichtigungen
        Set<String> notified = stringSet(c, "notified" + sfx);
        JSONObject applied = getApplied(c);
        int appliedCount = 0;
        for (int i = 0; i < products.length(); i++) if (applied.has(products.getJSONObject(i).optString("aid"))) appliedCount++;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < products.length() && i < 4; i++) {
            if (names.length() > 0) names.append(", ");
            names.append(products.getJSONObject(i).optString("name"));
        }
        if (products.length() > 4) names.append(" …");
        boolean told = false;

        if (notify && !phaseKey.isEmpty()) {
            if (status.equals("open") && notified.add("open:" + phaseKey)) {
                showNotification(c, nid + 1, "🚀 " + shop + " Testpiloten: Bewerbung ist offen!",
                        (st.optBoolean("endUnknown") ? "Jetzt bewerben" : "Bewerben bis " + fmtShort(end)) + (products.length() > 0 ? " – " + products.length() + " Testprodukte: " + names : ""),
                        alarmOn(c), page);
                history(c, "🚀 " + shop + ": Bewerbungsphase offen" + (st.optBoolean("endUnknown") ? "" : " bis " + fmt(end)));
                told = true;
            } else if (status.equals("soon") && notified.add("soon:" + phaseKey)) {
                String when = startAt > 0
                        ? "Start am " + new SimpleDateFormat("EEE dd.MM. 'um' HH:mm 'Uhr'", Locale.GERMANY).format(new Date(startAt))
                        : "Bewerbung vom " + fmtShort(start) + " bis " + fmtShort(end);
                showNotification(c, nid + 2, "📅 " + shop + ": neue Testpiloten-Runde angekündigt", when, false, page);
                history(c, "📅 " + shop + ": neue Runde angekündigt – " + when);
                told = true;
            }
            if (status.equals("open") && now == end && !st.optBoolean("endUnknown") && notified.add("last:" + phaseKey)) {
                showNotification(c, nid + 3, "⏰ " + shop + ": heute letzter Tag für die Testpiloten",
                        products.length() > 0 ? "Du hast dich für " + appliedCount + " von " + products.length() + " Produkten beworben." : "Heute endet die Bewerbungsphase.",
                        false, page);
                history(c, "⏰ " + shop + ": letzter Bewerbungstag");
            }
        }
        if (notify && !freshNames.isEmpty() && !told) {
            String n = freshNames.size() == 1 ? freshNames.get(0) : android.text.TextUtils.join(", ", freshNames.subList(0, Math.min(4, freshNames.size())));
            showNotification(c, nid + 4, "🆕 " + shop + ": " + freshNames.size() + " neue" + (freshNames.size() == 1 ? "s Testprodukt" : " Testprodukte"), n, false, page);
        }
        if (!freshNames.isEmpty()) history(c, "🆕 " + shop + " neu: " + android.text.TextUtils.join(", ", freshNames));
        saveSet(c, "notified" + sfx, notified, 60);

        if (prev == null || !status.equals(prev.optString("status"))) {
            history(c, shop + ": " + statusText(status) + (start > 0 ? " (" + fmt(start) + " – " + fmt(end) + ")" : ""));
        }
        return st;
    }

    static final String N = "(\\d{1,3})", SEP = "[\\s:.|]*";
    static final String T = "(?:Tage?n?|Tg\\.?)", H = "(?:Stunden?|Std\\.?)", M = "(?:Minuten?|Min\\.?)";
    static final Pattern[] COUNTDOWNS = {
        // 12 Tage 05 Stunden 33 Minuten
        Pattern.compile(N + SEP + T + SEP + N + SEP + H + SEP + N + SEP + M, Pattern.CASE_INSENSITIVE),
        // Tage 12 Stunden 05 Minuten 33
        Pattern.compile("\\b" + T + SEP + N + SEP + H + SEP + N + SEP + M + SEP + N, Pattern.CASE_INSENSITIVE),
        // 12 05 33 (10)  Tage Stunden Minuten
        Pattern.compile(N + SEP + N + SEP + N + "(?:" + SEP + "\\d{1,2})?" + SEP + T + SEP + H + SEP + M, Pattern.CASE_INSENSITIVE),
        // Tage Stunden Minuten (Sekunden)  12 05 33
        Pattern.compile("\\b" + T + SEP + H + SEP + M + "(?:" + SEP + "(?:Sekunden?|Sek\\.?))?" + SEP + N + SEP + N + SEP + N, Pattern.CASE_INSENSITIVE),
    };

    /** Liest den Countdown der Seite. Ergebnis: verbleibende Millisekunden oder -1. */
    static long countdownMs(String text) {
        for (Pattern p : COUNTDOWNS) {
            Matcher m = p.matcher(text);
            if (!m.find()) continue;
            long d = Long.parseLong(m.group(1)), h = Long.parseLong(m.group(2)), min = Long.parseLong(m.group(3));
            if (h > 23 || min > 59 || d > 120) continue;
            return ((d * 24 + h) * 60 + min) * 60000L;
        }
        return -1;
    }

    /** Auf die Minute runden; liegt das Ziel höchstens 2 Min. neben einer vollen Viertelstunde, diese nehmen. */
    static long snap(long t) {
        long q = 15 * 60000L, near = Math.round(t / (double) q) * q;
        return Math.abs(near - t) <= 2 * 60000L ? near : Math.round(t / 60000.0) * 60000L;
    }

    static int ymdOf(long t) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        return ymd(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR));
    }

    /** Plant eine Extra-Prüfung kurz nach dem Start, damit die Meldung pünktlich kommt. */
    static void scheduleStartCheck(Context c, String key, long startAt) {
        try {
            long delay = Math.max(0, startAt - System.currentTimeMillis()) + 60000L;
            androidx.work.OneTimeWorkRequest req = new androidx.work.OneTimeWorkRequest.Builder(CheckWorker.class)
                    .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .setConstraints(new androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                    .build();
            androidx.work.WorkManager.getInstance(c).enqueueUniqueWork("fox-start-" + key, androidx.work.ExistingWorkPolicy.REPLACE, req);
            // Erinnerung 10 Minuten vor dem Start
            String shop = key, page = PAGE;
            for (String[] s : SHOPS) if (s[0].equals(key)) { shop = s[1]; page = s[2]; }
            String at = new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(startAt));
            if (startAt - System.currentTimeMillis() > 11 * 60000L) {
                androidx.work.Data data = new androidx.work.Data.Builder().putInt("id", 700 + Math.abs(key.hashCode() % 50))
                        .putString("title", "⏰ In 10 Minuten: " + shop + " Testpiloten")
                        .putString("body", "Die Bewerbung startet um " + at + " Uhr.").putString("target", page).build();
                androidx.work.OneTimeWorkRequest pre = new androidx.work.OneTimeWorkRequest.Builder(ReminderWorker.class)
                        .setInitialDelay(startAt - System.currentTimeMillis() - 10 * 60000L, java.util.concurrent.TimeUnit.MILLISECONDS).setInputData(data).build();
                androidx.work.WorkManager.getInstance(c).enqueueUniqueWork("fox-pre-" + key, androidx.work.ExistingWorkPolicy.REPLACE, pre);
            }
        } catch (Exception ignored) {
        }
    }

    /** Diagnose: was die App auf den Seiten wirklich liest – zum Kopieren und Weitergeben. */
    public static String diagnose(Context c) {
        StringBuilder b = new StringBuilder("Fox Testpilot Diagnose " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(new Date()) + "\n");
        for (String[] shop : SHOPS) {
            b.append("\n== ").append(shop[1]).append(" ==\n");
            try {
                WebLoader.Result w = WebLoader.load(c, shop[2], 40000);
                b.append("URL: ").append(w.finalUrl).append("\n");
                if (w.error != null) b.append("Fehler: ").append(w.error).append("\n");
                String text = w.text == null ? "" : w.text;
                b.append("Textlänge: ").append(text.length()).append(looksBlocked(text) ? " (sieht blockiert aus)" : "").append("\n");
                b.append("Überschrift: ").append(headline(text)).append("\n");
                Matcher m = PHASE.matcher(text);
                b.append("Zeitraum: ").append(m.find() ? m.group(0).replace("\n", " ") : "nicht gefunden").append("\n");
                String cd = "nicht gefunden";
                for (Pattern p : COUNTDOWNS) {
                    Matcher cm = p.matcher(text);
                    if (cm.find()) { cd = cm.group(0).replace("\n", " "); break; }
                }
                long ms = countdownMs(text);
                b.append("Countdown: ").append(cd).append(ms >= 0 ? " -> " + new SimpleDateFormat("EEE dd.MM. HH:mm", Locale.GERMANY).format(new Date(snap(System.currentTimeMillis() + ms))) : "").append("\n");
                int test = 0;
                for (int i = 0; i < w.items.length(); i++) if (w.items.optJSONObject(i).optBoolean("test")) test++;
                b.append("Produkt-Links: ").append(w.items.length()).append(", davon im Testbereich: ").append(test).append("\n");
                for (int i = 0; i < w.items.length() && i < 8; i++) {
                    JSONObject o = w.items.optJSONObject(i);
                    b.append(o.optBoolean("test") ? " T " : " - ").append(o.optString("name")).append(" (").append(o.optString("id")).append(")\n");
                }
                b.append("Wichtige Zeilen:\n");
                int shown = 0;
                for (String line : text.split("\n")) {
                    String l = line.trim(), low = l.toLowerCase(Locale.GERMANY);
                    if (l.length() < 3 || l.length() > 160) continue;
                    if (low.contains("testpilot") || low.contains("bewerb") || low.contains("stunden") || low.contains("teilnahme") || low.contains("demnächst")) {
                        b.append(" > ").append(l).append("\n");
                        if (++shown >= 14) break;
                    }
                }
            } catch (Exception e) {
                b.append("Ausnahme: ").append(e).append("\n");
            }
        }
        return b.toString();
    }

    static boolean isLoginUrl(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return u.contains("login") || u.contains("/auth") || u.contains("signin") || u.contains("anmeld");
    }

    /** Ist die gewählte Person im App-Browser angemeldet? 1 = ja, 0 = nein, -1 = unklar. */
    public static int loginState(Context c, String domain) {
        try {
            WebLoader.Result w = WebLoader.load(c, "https://www." + domain + "/de/myaccount", 25000, 9000);
            if (w.error != null || looksBlocked(w.text)) return -1;
            String t = w.text.toLowerCase(Locale.GERMANY);
            if (isLoginUrl(w.finalUrl)) return 0;
            if (t.contains("passwort") && (t.contains("anmelden") || t.contains("einloggen")) && !t.contains("abmelden")) return 0;
            return 1;
        } catch (Exception e) {
            return -1;
        }
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
    /** Nachtruhe: Meldungen kommen dann lautlos (außer dem Start-Alarm und der 10-Minuten-Erinnerung). */
    static boolean inQuiet(Context c) {
        if (!prefs(c).getBoolean("quiet", false)) return false;
        int from = prefs(c).getInt("quietFrom", 22), to = prefs(c).getInt("quietTo", 7);
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return from == to ? false : from < to ? (h >= from && h < to) : (h >= from || h < to);
    }

    public static boolean showNotification(Context c, int id, String title, String body, boolean alarm, String target) {
        // Zusätzlich nach Discord (auch wenn Handy-Benachrichtigungen aus sind); Test-Meldung (999) läuft separat
        if (id != 999) {
            boolean ping = alarm || id % 100 == 1 || (id >= 700 && id < 750);
            Discord.sendAsync(c, title, body, target, ping && prefs(c).getBoolean("discordPing", false));
        }
        if (!notificationsAllowed(c)) return false;
        boolean quiet = !alarm && !(id >= 700 && id < 750) && inQuiet(c);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Testpiloten-Meldungen", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Neue Testpiloten-Runden und Produkte bei MediaMarkt und Saturn");
            nm.createNotificationChannel(ch);
            NotificationChannel al = new NotificationChannel(CHANNEL_ALARM, "Bewerbung offen (Alarm)", NotificationManager.IMPORTANCE_HIGH);
            al.setDescription("Lauter Ton, sobald die Bewerbungsphase startet");
            al.enableVibration(true);
            al.setVibrationPattern(new long[] { 0, 700, 300, 700 });
            al.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(al);
            NotificationChannel q = new NotificationChannel("testpilot_leise", "Meldungen in der Nachtruhe", NotificationManager.IMPORTANCE_LOW);
            q.setDescription("Lautlos, während die Nachtruhe gilt");
            nm.createNotificationChannel(q);
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
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, alarm ? CHANNEL_ALARM : quiet ? "testpilot_leise" : CHANNEL)
                .setSmallIcon(c.getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(quiet ? NotificationCompat.PRIORITY_LOW : NotificationCompat.PRIORITY_HIGH)
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
