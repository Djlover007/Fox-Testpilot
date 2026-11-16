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
            PilotChecker.setApplied(getContext(), call.getString("id", ""), Boolean.TRUE.equals(call.getBoolean("on", true)));
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
        e.commit();
        applyBackground(true);
        getState(call);
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
