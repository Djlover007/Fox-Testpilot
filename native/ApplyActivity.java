package de.fox.testpilot;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;

/**
 * MediaMarkt/Saturn im App-Browser: hier meldest du dich einmal an (bleibt gespeichert),
 * und Bewerbungsformulare werden mit deinen gespeicherten Daten ausgefüllt.
 * Absenden musst du selbst.
 */
public class ApplyActivity extends Activity {

    private WebView web;
    private boolean allFrames = false;
    private String script = "";
    // Auto-Modus: ausgewählte Produkte nacheinander ausfüllen und absenden
    private org.json.JSONArray queue;
    private int qi = 0, sentCount = 0;
    private long stepStart = 0, submittedAt = 0;
    private boolean warned = false, stepDone = false;
    private androidx.webkit.ScriptHandler handler;
    private final java.util.List<androidx.webkit.ScriptHandler> loginHandlers = new java.util.ArrayList<>();
    private boolean loginViaStart = false;
    private int total = 0, loginOk = 0;
    private TextView titleView;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private void loadStep() {
        if (isFinishing()) return;
        if (qi >= queue.length()) {
            Toast.makeText(this, "🤖 Fertig: " + sentCount + " von " + total + " Bewerbungen abgeschickt", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        JSONObject it = queue.optJSONObject(qi);
        // Andere Person an der Reihe: Profil umschalten, abmelden, Anmelde-Skripte der neuen Person laden
        String person = it.optString("person");
        if (!person.isEmpty() && !person.equals(PilotChecker.activePerson(this))) {
            PilotChecker.prefs(this).edit().putString("activePerson", person).commit();
            try {
                android.webkit.WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) {
            }
            CookieManager.getInstance().removeAllCookies(ok -> {
                CookieManager.getInstance().flush();
                addLoginScripts();
                loadStep();
            });
            return;
        }
        boolean login = it.optBoolean("login");
        script = login ? "" : buildScript(this, it.optString("name"), it.optString("url"), it.optString("aid"));
        try {
            if (handler != null) handler.remove();
            handler = null;
            if (!script.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                handler = WebViewCompat.addDocumentStartJavaScript(web, script, Collections.singleton("*"));
                allFrames = true;
            }
        } catch (Exception ignored) {
        }
        stepStart = System.currentTimeMillis();
        submittedAt = 0;
        warned = false;
        stepDone = false;
        loginOk = 0;
        allFrames = handler != null;
        String who = it.optString("label");
        titleView.setText((login ? "🔑 " : "🤖 ") + (qi + 1) + "/" + queue.length() + " " + (who.isEmpty() ? "" : who + ": ") + it.optString("name"));
        web.loadUrl(it.optString("url"));
    }

    private void stepFinished() {
        if (stepDone) return;
        stepDone = true;
        PilotChecker.setApplied(this, queue.optJSONObject(qi).optString("aid"), true, queue.optJSONObject(qi).optString("name"), queue.optJSONObject(qi).optString("shop"));
        sentCount++;
        Toast.makeText(this, "✅ Abgeschickt: " + queue.optJSONObject(qi).optString("name"), Toast.LENGTH_SHORT).show();
        qi++;
        ui.postDelayed(this::loadStep, 2500);
    }

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || queue == null) return;
            JSONObject cur = qi < queue.length() ? queue.optJSONObject(qi) : null;
            if (cur != null && cur.optBoolean("login")) {
                // Anmelde-Schritt: weiter, sobald die Seite nicht mehr die Anmeldung zeigt
                long el = System.currentTimeMillis() - stepStart;
                String u = web.getUrl();
                if (el > 12000 && u != null && !PilotChecker.isLoginUrl(u)) loginOk++;
                else loginOk = 0;
                if (loginOk >= 2) {
                    qi++;
                    loadStep();
                } else if (!warned && el > 50000) {
                    warned = true;
                    Toast.makeText(ApplyActivity.this, "Anmeldung hat nicht automatisch geklappt – bitte selbst anmelden, dann „Weiter ▶“ tippen", Toast.LENGTH_LONG).show();
                }
            } else if (!stepDone) {
                web.evaluateJavascript("window.__foxState||''", v -> {
                    if (stepDone || v == null) return;
                    long now = System.currentTimeMillis();
                    if (v.contains("success")) stepFinished();
                    else if (v.contains("submitted")) {
                        if (submittedAt == 0) submittedAt = now;
                        else if (now - submittedAt > 10000) stepFinished();
                    } else if (!warned && now - stepStart > 75000) {
                        warned = true;
                        Toast.makeText(ApplyActivity.this, "Hier ging es nicht automatisch – bitte selbst ausfüllen/absenden oder „Weiter ▶“ tippen", Toast.LENGTH_LONG).show();
                    }
                });
            }
            ui.postDelayed(this, 2000);
        }
    };

    /** Baut das Ausfüll-Skript: gespeicherte Daten + www/autofill.js. */
    static String buildScript(android.content.Context c, String product, String url, String autoAid) {
        try {
            JSONObject p = new JSONObject(PilotChecker.prefs(c).getString(PilotChecker.profileKey(c), "{}"));
            String name = product == null || product.trim().isEmpty() ? "das Testprodukt" : product.trim();
            // Eigener Text je Kategorie: passt ein Stichwort zum Produktnamen, wird dieser Text genommen
            String text = p.optString("text"), lowName = name.toLowerCase(java.util.Locale.GERMANY);
            pick:
            for (int i = 1; i <= 3; i++) {
                if (p.optString("t" + i).trim().isEmpty()) continue;
                for (String k : p.optString("k" + i).toLowerCase(java.util.Locale.GERMANY).split("[,;]")) {
                    if (!k.trim().isEmpty() && lowName.contains(k.trim())) {
                        text = p.optString("t" + i);
                        break pick;
                    }
                }
            }
            p.put("text", text.replace("{produkt}", name));
            for (int i = 1; i <= 3; i++) {
                p.remove("t" + i);
                p.remove("k" + i);
            }
            // Im Formular die E-Mail des Kontos verwenden, mit dem man bei diesem Shop angemeldet ist
            String shop = url == null ? null : Accounts.shopForUrl(url);
            String mail = shop == null ? "" : Accounts.email(c, shop);
            if (!mail.isEmpty()) p.put("email", mail);
            if (autoAid != null) {
                p.put("auto", true);
                p.put("aid", autoAid);
            }
            InputStream in = c.getAssets().open("public/autofill.js");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return "window.__foxData=" + p.toString() + ";\n" + out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Anmelde-Skripte der gewählten Person: jeweils nur auf der eigenen Shop-Domain, nie in fremden Rahmen. */
    private void addLoginScripts() {
        try {
            for (androidx.webkit.ScriptHandler h : loginHandlers) h.remove();
        } catch (Exception ignored) {
        }
        loginHandlers.clear();
        loginViaStart = false;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                for (String[] shop : Accounts.SHOPS) {
                    String js = Accounts.loginScript(this, shop[0]);
                    if (js.isEmpty()) continue;
                    java.util.Set<String> origins = new java.util.HashSet<>();
                    origins.add("https://" + shop[1]);
                    origins.add("https://*." + shop[1]);
                    loginHandlers.add(WebViewCompat.addDocumentStartJavaScript(web, js, origins));
                }
                loginViaStart = true;
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Color.parseColor("#2b6fde"));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(16, 8, 16, 8);
        Button close = new Button(this);
        close.setText("✕");
        close.setOnClickListener(v -> finish());
        try {
            String q = getIntent().getStringExtra("queue");
            if (q != null) {
                queue = new org.json.JSONArray(q);
                for (int i = 0; i < queue.length(); i++) if (!queue.optJSONObject(i).optBoolean("login")) total++;
            }
        } catch (Exception ignored) {
        }
        TextView title = new TextView(this);
        titleView = title;
        title.setText("🦊 Fox Testpilot");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setPadding(20, 0, 0, 0);
        Button fill = new Button(this);
        fill.setText("🦊 Ausfüllen");
        fill.setOnClickListener(v -> runFill(true));
        // Lange drücken: Formular-Bericht (nur Feldnamen, keine Inhalte) in die Zwischenablage
        fill.setOnLongClickListener(v -> {
            web.evaluateJavascript("(window.__foxDiag?window.__foxDiag():0)", x -> ui.postDelayed(() ->
                web.evaluateJavascript("JSON.stringify(window.__foxDiagAll||[])", raw -> {
                    try {
                        String json = new org.json.JSONTokener(raw).nextValue().toString();
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Fox Testpilot", "Formular-Bericht\nURL: " + web.getUrl() + "\n" + json));
                        Toast.makeText(this, "📋 Formular-Bericht kopiert", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Bericht fehlgeschlagen", Toast.LENGTH_SHORT).show();
                    }
                }), 1500));
            return true;
        });
        if (queue != null) {
            fill.setText("Weiter ▶");
            fill.setOnClickListener(v -> {
                if (stepDone) return;
                qi++;
                loadStep();
            });
        }
        Button ext = new Button(this);
        ext.setText("↗");
        ext.setOnClickListener(v -> {
            try {
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(web.getUrl())), "Öffnen mit"));
            } catch (Exception ignored) {
            }
        });
        bar.addView(close, new LinearLayout.LayoutParams(140, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(fill);
        bar.addView(ext, new LinearLayout.LayoutParams(140, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(WebLoader.UA);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        if (queue == null) script = buildScript(this, getIntent().getStringExtra("name"), getIntent().getStringExtra("url"), null);
        // Skript in ALLE Rahmen der Seite laden – das Formular ist oft von einem anderen Anbieter eingebettet
        try {
            if (queue == null && !script.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, script, Collections.singleton("*"));
                allFrames = true;
            }
        } catch (Exception ignored) {
        }
        addLoginScripts();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                if (!allFrames && !script.isEmpty()) view.evaluateJavascript(script, null);
                if (!loginViaStart) {
                    String shop = Accounts.shopForUrl(u);
                    String js = shop == null ? "" : Accounts.loginScript(ApplyActivity.this, shop);
                    if (!js.isEmpty()) view.evaluateJavascript(js, null);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (queue != null) {
            loadStep();
            ui.postDelayed(watch, 3000);
            return;
        }
        String url = getIntent().getStringExtra("url");
        web.loadUrl(url != null ? url : PilotChecker.PAGE);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void runFill(boolean toast) {
        if (script.isEmpty()) {
            Toast.makeText(this, "Bitte zuerst deine Daten in den Einstellungen speichern", Toast.LENGTH_LONG).show();
            return;
        }
        web.evaluateJavascript((allFrames ? "" : script + ";") + "(window.__foxFill?window.__foxFill():0)", v -> {
            if (toast && ("0".equals(v) || "null".equals(v))) {
                Toast.makeText(this, "Falls nichts ausgefüllt wurde: erst zum Teilnahmeformular scrollen, dort ggf. Cookies zulassen und nochmal tippen", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
}
