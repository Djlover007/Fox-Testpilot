package de.fox.testpilot;

import android.content.Context;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Schickt Meldungen zusätzlich als Nachricht in einen Discord-Kanal (über einen Webhook). */
public class Discord {

    static final Pattern VALID = Pattern.compile("^https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[A-Za-z0-9_\\-]+/?$");

    public static boolean valid(String url) {
        return url != null && VALID.matcher(url.trim()).matches();
    }

    static String webhook(Context c) {
        return PilotChecker.prefs(c).getString("discordUrl", "").trim();
    }

    public static boolean on(Context c) {
        return PilotChecker.prefs(c).getBoolean("discordOn", false) && valid(webhook(c));
    }

    /** Nur Discord, unabhängig von den Handy-Benachrichtigungen. Gibt den HTTP-Code zurück (-1 = Fehler). */
    public static void sendAsync(Context c, String title, String body, String url, boolean ping) {
        if (!on(c)) return;
        Runnable r = () -> send(c, webhook(c), title, body, url, ping);
        // Im Hintergrund-Worker direkt senden (sonst wird der Thread evtl. beendet), auf dem Haupt-Thread nie
        if (Looper.myLooper() == Looper.getMainLooper()) new Thread(r).start();
        else r.run();
    }

    public static int send(Context c, String hook, String title, String body, String url, boolean ping) {
        HttpURLConnection con = null;
        try {
            JSONObject embed = new JSONObject();
            embed.put("title", cut(title, 250));
            embed.put("description", cut(body, 3800));
            embed.put("color", title != null && title.contains("Saturn") ? 0xF08300 : title != null && title.contains("MediaMarkt") ? 0xE3000F : 0xF28C28);
            if (url != null && url.startsWith("https://")) embed.put("url", url);
            embed.put("footer", new JSONObject().put("text", "🦊 Fox Testpilot"));
            embed.put("timestamp", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT).format(new java.util.Date()));
            JSONObject msg = new JSONObject();
            msg.put("username", "Fox Testpilot");
            msg.put("embeds", new JSONArray().put(embed));
            if (ping) {
                msg.put("content", "@everyone");
                msg.put("allowed_mentions", new JSONObject().put("parse", new JSONArray().put("everyone")));
            } else {
                msg.put("allowed_mentions", new JSONObject().put("parse", new JSONArray()));
            }
            byte[] data = msg.toString().getBytes(StandardCharsets.UTF_8);
            con = (HttpURLConnection) new URL(hook.trim()).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("User-Agent", "FoxTestpilot (Android)");
            try (OutputStream out = con.getOutputStream()) {
                out.write(data);
            }
            int code = con.getResponseCode();
            if (code >= 300) PilotChecker.history(c, "⚠️ Discord: Senden fehlgeschlagen (HTTP " + code + ")");
            return code;
        } catch (Exception e) {
            PilotChecker.history(c, "⚠️ Discord: " + e.getClass().getSimpleName());
            return -1;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }
}
