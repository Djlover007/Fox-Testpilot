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

    /** Baut das Ausfüll-Skript: gespeicherte Daten + www/autofill.js. */
    static String buildScript(android.content.Context c, String product, String url) {
        try {
            JSONObject p = new JSONObject(PilotChecker.prefs(c).getString(PilotChecker.profileKey(c), "{}"));
            String name = product == null || product.trim().isEmpty() ? "das Testprodukt" : product.trim();
            p.put("text", p.optString("text").replace("{produkt}", name));
            // Im Formular die E-Mail des Kontos verwenden, mit dem man bei diesem Shop angemeldet ist
            String shop = url == null ? null : Accounts.shopForUrl(url);
            String mail = shop == null ? "" : Accounts.email(c, shop);
            if (!mail.isEmpty()) p.put("email", mail);
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
        TextView title = new TextView(this);
        title.setText("🦊 Fox Testpilot");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setPadding(20, 0, 0, 0);
        Button fill = new Button(this);
        fill.setText("🦊 Ausfüllen");
        fill.setOnClickListener(v -> runFill(true));
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

        script = buildScript(this, getIntent().getStringExtra("name"), getIntent().getStringExtra("url"));
        // Skript in ALLE Rahmen der Seite laden – das Formular ist oft von einem anderen Anbieter eingebettet
        try {
            if (!script.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
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

        String url = getIntent().getStringExtra("url");
        web.loadUrl(url != null ? url : PilotChecker.PAGE);
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
