package de.fox.testpilot;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(
    name = "FoxTools",
    permissions = { @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications") }
)
public class FoxToolsPlugin extends Plugin {

    static final String WORK = "fox-testpilot-check";

    @Override
    public void load() {
        applyBackground(false);
    }

    private void applyBackground(boolean replace) {
        try {
            WorkManager wm = WorkManager.getInstance(getContext());
            if (!PilotChecker.backgroundOn(getContext())) {
                wm.cancelUniqueWork(WORK);
                return;
            }
            Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(CheckWorker.class, PilotChecker.intervalMinutes(getContext()), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
            wm.enqueueUniquePeriodicWork(WORK, replace ? ExistingPeriodicWorkPolicy.UPDATE : ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Exception ignored) {
        }
    }

    @PluginMethod
    public void getState(PluginCall call) {
        try {
            JSObject r = new JSObject();
            JSONObject last = PilotChecker.getLast(getContext());
            if (last != null) r.put("last", JSObject.fromJSONObject(last));
            r.put("applied", JSObject.fromJSONObject(PilotChecker.getApplied(getContext())));
            org.json.JSONArray persons = PilotChecker.persons(getContext());
            JSObject all = new JSObject();
            for (int i = 0; i < persons.length(); i++) {
                String pid = persons.getJSONObject(i).optString("id");
                all.put(pid, JSObject.fromJSONObject(PilotChecker.getApplied(getContext(), pid)));
            }
            r.put("persons", new JSArray(persons.toString()));
            r.put("active", PilotChecker.activePerson(getContext()));
            r.put("appliedAll", all);
            r.put("notifications", PilotChecker.notificationsAllowed(getContext()));
            r.put("background", PilotChecker.backgroundOn(getContext()));
            r.put("interval", PilotChecker.intervalMinutes(getContext()));
            r.put("alarm", PilotChecker.alarmOn(getContext()));
            r.put("quiet", PilotChecker.prefs(getContext()).getBoolean("quiet", false));
            r.put("theme", PilotChecker.prefs(getContext()).getString("theme", "auto"));
            r.put("customPages", new JSArray(PilotChecker.customPages(getContext()).toString()));
            r.put("probe", PilotChecker.prefs(getContext()).getBoolean("probe", true));
            r.put("discordOn", PilotChecker.prefs(getContext()).getBoolean("discordOn", false));
            r.put("discordPing", PilotChecker.prefs(getContext()).getBoolean("discordPing", false));
            r.put("discordSet", Discord.valid(PilotChecker.prefs(getContext()).getString("discordUrl", "")));
            r.put("introDone", PilotChecker.prefs(getContext()).getBoolean("introDone", false));
            r.put("quietFrom", PilotChecker.prefs(getContext()).getInt("quietFrom", 22));
            r.put("quietTo", PilotChecker.prefs(getContext()).getInt("quietTo", 7));
            r.put("info", JSObject.fromJSONObject(PilotChecker.getInfo(getContext(), PilotChecker.activePerson(getContext()))));
            r.put("history", new JSArray(PilotChecker.prefs(getContext()).getString("history", "[]")));
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void checkNow(PluginCall call) {
        new Thread(() -> {
            try {
                call.resolve(JSObject.fromJSONObject(PilotChecker.run(getContext(), true)));
            } catch (Exception e) {
                call.reject("Prüfen fehlgeschlagen: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void setApplied(PluginCall call) {
        try {
            PilotChecker.setApplied(getContext(), call.getString("id", ""), Boolean.TRUE.equals(call.getBoolean("on", true)), call.getString("name", ""), call.getString("shop", ""));
            call.resolve(JSObject.fromJSONObject(PilotChecker.getApplied(getContext())));
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void setSettings(PluginCall call) {
        android.content.SharedPreferences.Editor e = PilotChecker.prefs(getContext()).edit();
        if (call.hasOption("background")) e.putBoolean("background", Boolean.TRUE.equals(call.getBoolean("background")));
        if (call.hasOption("interval")) e.putInt("interval", Math.max(15, call.getInt("interval", 30)));
        if (call.hasOption("alarm")) e.putBoolean("alarm", Boolean.TRUE.equals(call.getBoolean("alarm")));
        if (call.hasOption("probe")) e.putBoolean("probe", Boolean.TRUE.equals(call.getBoolean("probe")));
        if (call.hasOption("discordOn")) e.putBoolean("discordOn", Boolean.TRUE.equals(call.getBoolean("discordOn")));
        if (call.hasOption("discordPing")) e.putBoolean("discordPing", Boolean.TRUE.equals(call.getBoolean("discordPing")));
        if (call.hasOption("theme")) e.putString("theme", call.getString("theme", "auto"));
        if (call.hasOption("introDone")) e.putBoolean("introDone", Boolean.TRUE.equals(call.getBoolean("introDone")));
        if (call.hasOption("quiet")) e.putBoolean("quiet", Boolean.TRUE.equals(call.getBoolean("quiet")));
        if (call.hasOption("quietFrom")) e.putInt("quietFrom", call.getInt("quietFrom", 22));
        if (call.hasOption("quietTo")) e.putInt("quietTo", call.getInt("quietTo", 7));
        e.commit();
        applyBackground(true);
        getState(call);
    }

    /** Eigene Seite hinzufügen (id leer) oder löschen (remove=true). */
    @PluginMethod
    public void setCustom(PluginCall call) {
        try {
            org.json.JSONArray a = PilotChecker.customPages(getContext()), out = new org.json.JSONArray();
            String id = call.getString("id", "");
            if (Boolean.TRUE.equals(call.getBoolean("remove", false))) {
                for (int i = 0; i < a.length(); i++) if (!id.equals(a.getJSONObject(i).optString("id"))) out.put(a.getJSONObject(i));
            } else {
                String url = call.getString("url", "").trim();
                if (!url.matches("^https?://[^\\s/]+\\.[^\\s]+$")) {
                    call.reject("Bitte eine vollständige Adresse eingeben (https://…)");
                    return;
                }
                if (call.getString("keywords", "").trim().isEmpty()) {
                    call.reject("Bitte mindestens ein Stichwort eingeben");
                    return;
                }
                if (a.length() >= 8) {
                    call.reject("Höchstens 8 eigene Seiten");
                    return;
                }
                out = a;
                String name = call.getString("name", "").trim();
                if (name.isEmpty()) name = android.net.Uri.parse(url).getHost();
                out.put(new JSONObject().put("id", "c" + System.currentTimeMillis()).put("name", name).put("url", url).put("keywords", call.getString("keywords", "").trim()));
            }
            PilotChecker.prefs(getContext()).edit().putString("custom", out.toString()).commit();
            getState(call);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /** Webhook speichern (leer = entfernen). Die Adresse geht nie zurück an die Oberfläche. */
    @PluginMethod
    public void setDiscord(PluginCall call) {
        String url = call.getString("url", "").trim();
        if (!url.isEmpty() && !Discord.valid(url)) {
            call.reject("Das ist keine Discord-Webhook-Adresse (https://discord.com/api/webhooks/…)");
            return;
        }
        PilotChecker.prefs(getContext()).edit().putString("discordUrl", url).putBoolean("discordOn", !url.isEmpty()).commit();
        getState(call);
    }

    @PluginMethod
    public void testDiscord(PluginCall call) {
        new Thread(() -> {
            String hook = PilotChecker.prefs(getContext()).getString("discordUrl", "");
            if (!Discord.valid(hook)) {
                call.reject("Erst eine Webhook-Adresse speichern");
                return;
            }
            int code = Discord.send(getContext(), hook, "🦊 Fox Testpilot ist verbunden",
                    "Ab jetzt kommen die Testpiloten-Meldungen auch hier an.", PilotChecker.PAGE,
                    PilotChecker.prefs(getContext()).getBoolean("discordPing", false));
            JSObject r = new JSObject();
            r.put("code", code);
            r.put("ok", code >= 200 && code < 300);
            call.resolve(r);
        }).start();
    }

    @PluginMethod
    public void testNotification(PluginCall call) {
        boolean ok = PilotChecker.showNotification(getContext(), 999, "🦊 Fox Testpilot", "Benachrichtigungen funktionieren!",
                PilotChecker.alarmOn(getContext()), null);
        JSObject r = new JSObject();
        r.put("shown", ok);
        call.resolve(r);
    }

    @PluginMethod
    public void notificationStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", PilotChecker.notificationsAllowed(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void askNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "notificationsCallback");
        } else {
            if (!PilotChecker.notificationsAllowed(getContext())) openNotificationSettings();
            notificationStatus(call);
        }
    }

    @PermissionCallback
    private void notificationsCallback(PluginCall call) {
        if (!PilotChecker.notificationsAllowed(getContext())) openNotificationSettings();
        notificationStatus(call);
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            i.putExtra("android.provider.extra.APP_PACKAGE", getContext().getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** Öffnet einen Link in Kiwi (dort bist du bei MediaMarkt angemeldet), sonst Auswahl. */
    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url");
        if (url == null) {
            call.reject("url fehlt");
            return;
        }
        Uri uri = Uri.parse(url);
        JSObject r = new JSObject();
        try {
            Intent kiwi = new Intent(Intent.ACTION_VIEW, uri);
            kiwi.setPackage(PilotChecker.KIWI);
            kiwi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(kiwi);
            r.put("browser", "kiwi");
        } catch (ActivityNotFoundException e) {
            Intent any = Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri), "Öffnen mit");
            any.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(any);
            r.put("browser", "auswahl");
        }
        call.resolve(r);
    }

    /** Öffnet MediaMarkt im App-Browser und füllt Formulare mit den gespeicherten Daten aus. */
    @PluginMethod
    public void openApply(PluginCall call) {
        Intent i = new Intent(getContext(), ApplyActivity.class);
        i.putExtra("url", call.getString("url", PilotChecker.PAGE));
        i.putExtra("name", call.getString("name", ""));
        i.putExtra("register", Boolean.TRUE.equals(call.getBoolean("register", false)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    /** Auto-Modus: ausgewählte Produkte nacheinander im App-Browser ausfüllen und absenden. */
    @PluginMethod
    public void openQueue(PluginCall call) {
        JSArray items = call.getArray("items");
        if (items == null || items.length() == 0) {
            call.reject("Keine Produkte ausgewählt");
            return;
        }
        Intent i = new Intent(getContext(), ApplyActivity.class);
        i.putExtra("queue", items.toString());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void getProfile(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(new JSONObject(PilotChecker.prefs(getContext()).getString(PilotChecker.profileKey(getContext()), "{}"))));
        } catch (Exception e) {
            call.resolve(new JSObject());
        }
    }

    @PluginMethod
    public void setProfile(PluginCall call) {
        JSObject p = call.getObject("profile", new JSObject());
        PilotChecker.prefs(getContext()).edit().putString(PilotChecker.profileKey(getContext()), p.toString()).apply();
        call.resolve();
    }

    /** Beim Personenwechsel abmelden: Cookies und Seitendaten des App-Browsers löschen. */
    private void logoutBrowser() {
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.CookieManager.getInstance().flush();
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (Exception ignored) {
        }
    }

    private void savePersons(org.json.JSONArray a, String active) {
        PilotChecker.prefs(getContext()).edit().putString("persons", a.toString()).putString("activePerson", active).commit();
    }

    @PluginMethod
    public void addPerson(PluginCall call) {
        try {
            org.json.JSONArray a = PilotChecker.persons(getContext());
            if (a.length() >= 6) {
                call.reject("Höchstens 6 Personen");
                return;
            }
            String id = "p" + System.currentTimeMillis();
            String label = call.getString("label", "").trim();
            a.put(new JSONObject().put("id", id).put("label", label.isEmpty() ? "Person " + (a.length() + 1) : label));
            savePersons(a, id);
            logoutBrowser();
            getState(call);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void selectPerson(PluginCall call) {
        String id = call.getString("id", "p1");
        if (!id.equals(PilotChecker.activePerson(getContext()))) {
            savePersons(PilotChecker.persons(getContext()), id);
            logoutBrowser();
        }
        getState(call);
    }

    @PluginMethod
    public void renamePerson(PluginCall call) {
        try {
            String label = call.getString("label", "").trim();
            org.json.JSONArray a = PilotChecker.persons(getContext());
            String active = PilotChecker.activePerson(getContext());
            for (int i = 0; i < a.length(); i++) {
                if (active.equals(a.getJSONObject(i).optString("id")) && !label.isEmpty()) a.getJSONObject(i).put("label", label);
            }
            savePersons(a, active);
            getState(call);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /** Löscht die gewählte Person samt Daten, Konten und Bewerbungsliste (die letzte bleibt). */
    @PluginMethod
    public void deletePerson(PluginCall call) {
        try {
            org.json.JSONArray a = PilotChecker.persons(getContext());
            if (a.length() < 2) {
                call.reject("Die letzte Person kann nicht gelöscht werden");
                return;
            }
            String active = PilotChecker.activePerson(getContext());
            org.json.JSONArray rest = new org.json.JSONArray();
            for (int i = 0; i < a.length(); i++) if (!active.equals(a.getJSONObject(i).optString("id"))) rest.put(a.getJSONObject(i));
            Accounts.clearPerson(getContext(), active);
            PilotChecker.prefs(getContext()).edit().remove("profile" + PilotChecker.sfx(active)).remove("applied" + PilotChecker.sfx(active)).commit();
            savePersons(rest, rest.getJSONObject(0).optString("id"));
            logoutBrowser();
            getState(call);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void getAccounts(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(Accounts.overview(getContext())));
        } catch (Exception e) {
            call.reject("Konten konnten nicht gelesen werden: " + e.getMessage());
        }
    }

    @PluginMethod
    public void setAccount(PluginCall call) {
        try {
            String shop = call.getString("shop", "");
            if (!shop.equals("mm") && !shop.equals("saturn")) {
                call.reject("Unbekannter Shop");
                return;
            }
            String email = call.getString("email", "").trim();
            String pw = call.getString("password", "");
            Accounts.save(getContext(), shop, email, pw, email.isEmpty() && pw.isEmpty());
            call.resolve(JSObject.fromJSONObject(Accounts.overview(getContext())));
        } catch (Exception e) {
            call.reject("Speichern fehlgeschlagen: " + e.getMessage());
        }
    }

    // ---- Diagnose, Anmelde-Status, Ergebnisse, Kalender, Sicherung ----

    @PluginMethod
    public void diagnose(PluginCall call) {
        new Thread(() -> {
            JSObject r = new JSObject();
            r.put("text", PilotChecker.diagnose(getContext()));
            call.resolve(r);
        }).start();
    }

    @PluginMethod
    public void copyText(PluginCall call) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Fox Testpilot", call.getString("text", "")));
            call.resolve();
        } catch (Exception e) {
            call.reject("Kopieren fehlgeschlagen");
        }
    }

    @PluginMethod
    public void shareText(PluginCall call) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, call.getString("title", "Fox Testpilot"));
            send.putExtra(Intent.EXTRA_TEXT, call.getString("text", ""));
            Intent ch = Intent.createChooser(send, call.getString("title", "Teilen"));
            ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(ch);
            call.resolve();
        } catch (Exception e) {
            call.reject("Teilen fehlgeschlagen");
        }
    }

    /** Prüft im Hintergrund-Browser, ob die gewählte Person bei den Shops angemeldet ist. */
    @PluginMethod
    public void loginStatus(PluginCall call) {
        new Thread(() -> {
            JSObject r = new JSObject();
            for (String[] s : Accounts.SHOPS) r.put(s[0], PilotChecker.loginState(getContext(), s[1]));
            call.resolve(r);
        }).start();
    }

    @PluginMethod
    public void setResult(PluginCall call) {
        try {
            if (Boolean.TRUE.equals(call.getBoolean("remove", false))) PilotChecker.removeInfo(getContext(), call.getString("aid", ""));
            else PilotChecker.setResult(getContext(), call.getString("aid", ""), call.getString("result", ""), call.getString("due", ""));
            getState(call);
        } catch (Exception e) {
            call.reject("Speichern fehlgeschlagen: " + e.getMessage());
        }
    }

    @PluginMethod
    public void addCalendar(PluginCall call) {
        try {
            long begin = (long) (double) call.getDouble("begin", 0.0);
            Intent i = new Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI);
            i.putExtra(android.provider.CalendarContract.Events.TITLE, call.getString("title", "Testpiloten"));
            i.putExtra(android.provider.CalendarContract.Events.DESCRIPTION, call.getString("url", ""));
            i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin);
            i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + 30 * 60000L);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Keine Kalender-App gefunden");
        }
    }

    /** Sicherung: Personen, Daten, Häkchen, Konto-E-Mails und Einstellungen – bewusst OHNE Passwörter. */
    @PluginMethod
    public void exportData(PluginCall call) {
        try {
            android.content.SharedPreferences sp = PilotChecker.prefs(getContext());
            JSONObject out = new JSONObject();
            out.put("app", "fox-testpilot");
            out.put("v", 1);
            org.json.JSONArray persons = PilotChecker.persons(getContext()), list = new org.json.JSONArray();
            for (int i = 0; i < persons.length(); i++) {
                JSONObject p = new JSONObject(persons.getJSONObject(i).toString());
                String id = p.optString("id"), sfx = PilotChecker.sfx(id);
                p.put("profile", new JSONObject(sp.getString("profile" + sfx, "{}")));
                p.put("applied", new JSONObject(sp.getString("applied" + sfx, "{}")));
                p.put("info", new JSONObject(sp.getString("ainfo" + sfx, "{}")));
                JSONObject mails = new JSONObject();
                for (String[] s : Accounts.SHOPS) mails.put(s[0], Accounts.emailOf(getContext(), id, s[0]));
                p.put("emails", mails);
                list.put(p);
            }
            out.put("persons", list);
            out.put("active", PilotChecker.activePerson(getContext()));
            JSONObject set = new JSONObject();
            set.put("background", sp.getBoolean("background", true)).put("interval", sp.getInt("interval", 30)).put("alarm", sp.getBoolean("alarm", false))
                    .put("quiet", sp.getBoolean("quiet", false)).put("quietFrom", sp.getInt("quietFrom", 22)).put("quietTo", sp.getInt("quietTo", 7));
            out.put("settings", set);
            out.put("custom", PilotChecker.customPages(getContext()));
            JSObject r = new JSObject();
            r.put("json", out.toString());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Sicherung fehlgeschlagen: " + e.getMessage());
        }
    }

    @PluginMethod
    public void importData(PluginCall call) {
        try {
            JSONObject in = new JSONObject(call.getString("json", "").trim());
            org.json.JSONArray list = in.optJSONArray("persons");
            if (!"fox-testpilot".equals(in.optString("app")) || list == null || list.length() == 0) {
                call.reject("Das ist keine Fox-Testpilot-Sicherung");
                return;
            }
            android.content.SharedPreferences.Editor e = PilotChecker.prefs(getContext()).edit();
            org.json.JSONArray persons = new org.json.JSONArray();
            for (int i = 0; i < list.length() && i < 6; i++) {
                JSONObject p = list.getJSONObject(i);
                String id = p.optString("id").replaceAll("[^A-Za-z0-9]", "");
                if (id.isEmpty()) continue;
                String sfx = PilotChecker.sfx(id);
                persons.put(new JSONObject().put("id", id).put("label", p.optString("label", "Person")));
                e.putString("profile" + sfx, p.optJSONObject("profile") == null ? "{}" : p.optJSONObject("profile").toString());
                e.putString("applied" + sfx, p.optJSONObject("applied") == null ? "{}" : p.optJSONObject("applied").toString());
                e.putString("ainfo" + sfx, p.optJSONObject("info") == null ? "{}" : p.optJSONObject("info").toString());
                JSONObject mails = p.optJSONObject("emails");
                if (mails != null) for (String[] s : Accounts.SHOPS) {
                    if (!mails.optString(s[0]).isEmpty()) Accounts.setEmailOf(getContext(), id, s[0], mails.optString(s[0]));
                }
            }
            if (persons.length() == 0) {
                call.reject("Keine Personen in der Sicherung");
                return;
            }
            e.putString("persons", persons.toString());
            e.putString("activePerson", persons.getJSONObject(0).optString("id"));
            JSONObject set = in.optJSONObject("settings");
            if (set != null) {
                e.putBoolean("background", set.optBoolean("background", true)).putInt("interval", Math.max(15, set.optInt("interval", 30)))
                        .putBoolean("alarm", set.optBoolean("alarm", false)).putBoolean("quiet", set.optBoolean("quiet", false))
                        .putInt("quietFrom", set.optInt("quietFrom", 22)).putInt("quietTo", set.optInt("quietTo", 7));
            }
            if (in.optJSONArray("custom") != null) e.putString("custom", in.optJSONArray("custom").toString());
            e.commit();
            logoutBrowser();
            applyBackground(true);
            getState(call);
        } catch (org.json.JSONException ex) {
            call.reject("Der Text ist keine gültige Sicherung");
        } catch (Exception ex) {
            call.reject("Import fehlgeschlagen: " + ex.getMessage());
        }
    }

    /** Lädt die neue APK herunter und öffnet den Android-Installationsdialog. */
    @PluginMethod
    public void installUpdate(PluginCall call) {
        String url = call.getString("url");
        if (url == null) {
            call.reject("url fehlt");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getContext().getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception ignored) {
            }
            JSObject r = new JSObject();
            r.put("needsPermission", true);
            call.resolve(r);
            return;
        }
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                File dir = new File(getContext().getCacheDir(), "updates");
                dir.mkdirs();
                File apk = new File(dir, "Fox-Testpilot.apk");
                String next = url;
                for (int hop = 0; hop < 5; hop++) {
                    con = (HttpURLConnection) new URL(next).openConnection();
                    con.setInstanceFollowRedirects(false);
                    con.setConnectTimeout(20000);
                    con.setReadTimeout(60000);
                    int code = con.getResponseCode();
                    if (code >= 300 && code < 400 && con.getHeaderField("Location") != null) {
                        next = new URL(new URL(next), con.getHeaderField("Location")).toString();
                        con.disconnect();
                        continue;
                    }
                    if (code != 200) throw new Exception("HTTP " + code);
                    break;
                }
                try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", apk);
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(uri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(install);
                JSObject r = new JSObject();
                r.put("started", true);
                call.resolve(r);
            } catch (Exception e) {
                call.reject("Update fehlgeschlagen: " + e.getMessage());
            } finally {
                if (con != null) con.disconnect();
            }
        }).start();
    }
}
