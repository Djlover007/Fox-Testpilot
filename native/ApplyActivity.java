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
    private TextView titleView;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private void loadStep() {
        if (isFinishing()) return;
        if (qi >= queue.length()) {
            Toast.makeText(this, "🤖 Fertig: " + sentCount + " von " + queue.length() + " Bewerbungen abgeschickt", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        JSONObject it = queue.optJSONObject(qi);
        script = buildScript(this, it.optString("name"), it.optString("url"), it.optString("aid"));
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
        titleView.setText("🤖 " + (qi + 1) + "/" + queue.length() + " " + it.optString("name"));
        web.loadUrl(it.optString("url"));
    }

    private void stepFinished() {
        if (stepDone) return;
        stepDone = true;
        PilotChecker.setApplied(this, queue.optJSONObject(qi).optString("aid"), true);
        sentCount++;
        Toast.makeText(this, "✅ Abgeschickt: " + queue.optJSONObject(qi).optString("name"), Toast.LENGTH_SHORT).show();
        qi++;
        ui.postDelayed(this::loadStep, 2500);
    }

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || queue == null) return;
            if (!stepDone) {
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
            p.put("text", p.optString("text").replace("{produkt}", name));
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
            if (q != null) queue = new org.json.JSONArray(q);
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
        // Anmelde-Skripte: jeweils nur auf der eigenen Shop-Domain, nie in fremden Rahmen
        boolean loginScripts = false;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                for (String[] shop : Accounts.SHOPS) {
                    String js = Accounts.loginScript(this, shop[0]);
                    if (js.isEmpty()) continue;
                    java.util.Set<String> origins = new java.util.HashSet<>();
                    origins.add("https://" + shop[1]);
                    origins.add("https://*." + shop[1]);
                    WebViewCompat.addDocumentStartJavaScript(web, js, origins);
                }
                loginScripts = true;
            }
        } catch (Exception ignored) {
        }
        final boolean loginViaStart = loginScripts;
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
