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
    // Konto anlegen: Registrierungsformular mit den Daten der Person füllen (nie automatisch absenden)
    private boolean register = false;
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
    private android.widget.ProgressBar progress;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Runder Knopf im Fuchs-Stil für die obere Leiste. */
    private void pill(Button b, boolean accent) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(accent ? Color.parseColor("#f28c28") : Color.parseColor("#38FFFFFF"));
        b.setBackground(bg);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(accent ? 14 : 0), 0, dp(accent ? 14 : 0), 0);
        b.setStateListAnimator(null);
    }
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private void loadStep() {
        if (isFinishing()) return;
        if (qi >= queue.length()) {
            if (probeMode) {
                showProbeReport();
                return;
            }
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
        script = login ? "" : buildScript(this, it.optString("name"), it.optString("url"), it.optString("aid"), it.optBoolean("probe"));
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
        if (progress != null) progress.setProgress(qi);
        allFrames = handler != null;
        String who = it.optString("label");
        titleView.setText((login ? "🔑 " : "🤖 ") + (qi + 1) + "/" + queue.length() + " " + (who.isEmpty() ? "" : who + ": ") + it.optString("name"));
        web.loadUrl(it.optString("url"));
    }

    // ---- Probelauf: ausfüllen, aber nicht abschicken, und am Ende zeigen, was passiert wäre ----
    private boolean probeMode = false;
    private final StringBuilder probeText = new StringBuilder();
    private int probeOk = 0;

    private void probeFinished(boolean found) {
        if (stepDone) return;
        stepDone = true;
        JSONObject it = queue.optJSONObject(qi);
        String who = it.optString("label");
        probeText.append("\n━━ ").append(who.isEmpty() ? "" : who + ": ").append(it.optString("name")).append(" ━━\n");
        if (!found) {
            probeText.append("❌ Kein Bewerbungsformular erkannt (nach 60 Sek.)\n");
            web.evaluateJavascript("(window.__foxDiag?window.__foxDiag():0)", x -> ui.postDelayed(() ->
                web.evaluateJavascript("JSON.stringify(window.__foxDiagAll||[])", raw -> {
                    try {
                        org.json.JSONArray all = new org.json.JSONArray(new org.json.JSONTokener(raw).nextValue().toString());
                        for (int i = 0; i < all.length(); i++) {
                            JSONObject f = all.getJSONObject(i);
                            probeText.append("Rahmen ").append(f.optString("frame")).append(":\n");
                            org.json.JSONArray fl = f.optJSONArray("fields");
                            for (int k = 0; fl != null && k < fl.length() && k < 25; k++) probeText.append("  · ").append(fl.optString(k)).append("\n");
                        }
                    } catch (Exception ignored) {
                    }
                    nextProbe();
                }), 1500));
            return;
        }
        web.evaluateJavascript("JSON.stringify(window.__foxProbe||{})", raw -> {
            try {
                JSONObject r = new JSONObject(new org.json.JSONTokener(raw).nextValue().toString());
                org.json.JSONArray fl = r.optJSONArray("filled");
                probeText.append("✍️ Ausgefüllt:\n");
                for (int i = 0; fl != null && i < fl.length(); i++) {
                    org.json.JSONArray x = fl.optJSONArray(i);
                    probeText.append("  · ").append(x.optString(0)).append(": ").append(x.optString(1)).append("\n");
                }
                org.json.JSONArray ch = r.optJSONArray("checks");
                if (ch != null && ch.length() > 0) {
                    probeText.append("☑️ Häkchen gesetzt:\n");
                    for (int i = 0; i < ch.length(); i++) probeText.append("  · ").append(ch.optString(i)).append("\n");
                }
                String b = r.optString("button");
                if (b.isEmpty()) probeText.append("❌ Absende-Knopf NICHT gefunden\n");
                else {
                    probeText.append("🔴 Hätte geklickt: „").append(b).append("“ (rot markiert)\n");
                    probeOk++;
                }
            } catch (Exception e) {
                probeText.append("⚠️ Bericht konnte nicht gelesen werden\n");
            }
            Toast.makeText(this, "🧪 Probelauf: nichts abgeschickt – sieh dir die rote Markierung an", Toast.LENGTH_LONG).show();
            ui.postDelayed(this::nextProbe, 6000);
        });
    }

    private void nextProbe() {
        qi++;
        loadStep();
    }

    private void showProbeReport() {
        final String text = "🧪 Probelauf – NICHTS wurde abgeschickt\n" + probeOk + " von " + total + " Formularen vollständig erkannt\n" + probeText;
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(13);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
            .setTitle(probeOk == total ? "✅ Probelauf erfolgreich" : "⚠️ Probelauf: bitte prüfen")
            .setView(sv)
            .setPositiveButton("Kopieren", (d, w) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Fox Testpilot", text));
                Toast.makeText(this, "📋 Bericht kopiert", Toast.LENGTH_SHORT).show();
                finish();
            })
            .setNegativeButton("Schließen", (d, w) -> finish())
            .setOnCancelListener(d -> finish())
            .show();
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
                    if (v.contains("probe")) probeFinished(true);
                    else if (v.contains("success")) stepFinished();
                    else if (v.contains("submitted")) {
                        if (submittedAt == 0) submittedAt = now;
                        else if (now - submittedAt > 10000) stepFinished();
                    } else if (probeMode && now - stepStart > 60000) {
                        probeFinished(false);
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
        return buildScript(c, product, url, autoAid, false);
    }

    static String buildScript(android.content.Context c, String product, String url, String autoAid, boolean probe) {
        return buildScript(c, product, url, autoAid, probe, false);
    }

    /** Erlaubte Seiten für das Registrier-Skript mit Passwort: nur MediaMarkt/Saturn (gemeinsames Konto). */
    static final String[] REG_HOSTS = { "mediamarkt.de", "saturn.de", "mediamarktsaturn.com" };

    static boolean regHost(String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            if (host == null) return false;
            for (String h : REG_HOSTS) if (host.equals(h) || host.endsWith("." + h)) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    static String buildScript(android.content.Context c, String product, String url, String autoAid, boolean probe, boolean reg) {
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
            if (reg) {
                p.put("reg", true);
                // MediaMarkt und Saturn haben ein gemeinsames Konto: E-Mail/Passwort von dem Shop nehmen, wo etwas gespeichert ist
                if (regHost(url)) {
                    for (String[] sh : Accounts.SHOPS) {
                        String pw = Accounts.password(c, sh[0]), em = Accounts.email(c, sh[0]);
                        if (!pw.isEmpty() && !p.has("pw")) p.put("pw", pw);
                        if (!em.isEmpty() && mail.isEmpty()) { p.put("email", em); mail = em; }
                    }
                }
            }
            if (autoAid != null) {
                p.put("auto", true);
                p.put("aid", autoAid);
                p.put("probe", probe);
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
        bar.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { Color.parseColor("#2b6fde"), Color.parseColor("#7b2fd1") }));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        Button close = new Button(this);
        close.setText("✕");
        close.setOnClickListener(v -> finish());
        try {
            String q = getIntent().getStringExtra("queue");
            if (q != null) {
                queue = new org.json.JSONArray(q);
                for (int i = 0; i < queue.length(); i++) {
                    if (!queue.optJSONObject(i).optBoolean("login")) total++;
                    if (queue.optJSONObject(i).optBoolean("probe")) probeMode = true;
                }
            }
        } catch (Exception ignored) {
        }
        TextView title = new TextView(this);
        titleView = title;
        title.setText("🦊 Fox Testpilot");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(10), 0, dp(8), 0);
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
        pill(close, false);
        pill(fill, true);
        pill(ext, false);
        bar.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(fill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        LinearLayout.LayoutParams extLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        extLp.setMarginStart(dp(6));
        bar.addView(ext, extLp);

        // Fortschritt: im Auto-Modus die Schritte, sonst das Laden der Seite
        progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#f28c28")));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#33000000")));
        if (queue != null) progress.setMax(Math.max(1, queue.length()));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(WebLoader.UA);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        register = queue == null && getIntent().getBooleanExtra("register", false);
        if (register) title.setText("🆕 Konto anlegen");
        if (queue == null) script = buildScript(this, getIntent().getStringExtra("name"), getIntent().getStringExtra("url"), null, false, register);
        // Skript in ALLE Rahmen der Seite laden – das Formular ist oft von einem anderen Anbieter eingebettet
        try {
            if (queue == null && !script.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                java.util.Set<String> origins = new java.util.HashSet<>();
                if (register && script.contains("\"pw\":")) {
                    // Das Passwort nur in die Seiten von MediaMarkt/Saturn geben, nie in fremde Rahmen
                    for (String h : REG_HOSTS) { origins.add("https://" + h); origins.add("https://*." + h); }
                } else origins.add("*");
                WebViewCompat.addDocumentStartJavaScript(web, script, origins);
                allFrames = true;
            }
        } catch (Exception ignored) {
        }
        if (!register) addLoginScripts();
        else loginViaStart = true;   // beim Registrieren NICHT automatisch anmelden
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                if (!allFrames && !script.isEmpty() && (!register || regHost(u) || !script.contains("\"pw\":"))) view.evaluateJavascript(script, null);
                if (!loginViaStart) {
                    String shop = Accounts.shopForUrl(u);
                    String js = shop == null ? "" : Accounts.loginScript(ApplyActivity.this, shop);
                    if (!js.isEmpty()) view.evaluateJavascript(js, null);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                if (queue != null) return;
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            }
        });

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
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
        boolean inject = !allFrames && (!register || regHost(web.getUrl()) || !script.contains("\"pw\":"));
        web.evaluateJavascript((inject ? script + ";" : "") + "(window.__foxFill?window.__foxFill():0)", v -> {
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
