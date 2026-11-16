package de.fox.testpilot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lädt die Testpiloten-Seite in einem unsichtbaren Browser (WebView), wie Chrome/Kiwi.
 * MediaMarkt baut die Seite per JavaScript auf, ein einfacher Abruf reicht deshalb nicht.
 * Scrollt dabei nach unten, damit nachgeladene Produktkacheln erscheinen.
 * Darf NICHT auf dem Haupt-Thread aufgerufen werden.
 */
public class WebLoader {

    public static class Result {
        public String text = "";
        public String error = null;
        public String finalUrl = "";
        public JSONArray items = new JSONArray();
    }

    static final String UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    /** Sammelt Text und alle Produkt-Links der Seite ein und scrollt ein Stück weiter. */
    static final String POLL_JS =
        "(function(){try{\n" +
        "  window.scrollBy(0, 1600);\n" +
        "  var items = [], seen = {};\n" +
        "  function clean(s){ return String(s || '').replace(/\\s+/g, ' ').trim(); }\n" +
        "  function imgOf(a){\n" +
        "    var im = a.querySelector('img'); if (!im) return '';\n" +
        "    var s = im.currentSrc || im.getAttribute('src') || im.getAttribute('data-src') || '';\n" +
        "    if (!/^https?:/.test(s)) { var ss = im.getAttribute('srcset') || ''; s = ss.split(' ')[0] || ''; }\n" +
        "    return /^https?:/.test(s) ? s : '';\n" +
        "  }\n" +
        "  function nameOf(a){\n" +
        "    var im = a.querySelector('img');\n" +
        "    var n = clean(a.getAttribute('aria-label') || a.getAttribute('title') || '');\n" +
        "    if (!n) { var lines = String(a.innerText || '').split('\\n').map(clean).filter(function(s){ return s.length > 3 && !/^[\\d.,€\\s*%-]+$/.test(s); }); n = lines[0] || ''; }\n" +
        "    if (!n && im) n = clean(im.getAttribute('alt'));\n" +
        "    return n.slice(0, 140);\n" +
        "  }\n" +
        "  function isTest(a){\n" +
        "    var ctx = '';\n" +
        "    for (var p = a, d = 0; p && d < 7; p = p.parentElement, d++) { ctx = p.innerText || ''; if (ctx.length > 500) break; }\n" +
        "    return /testger|testpilot|zu testen|testprodukt|bewerb|teilnahme/i.test(ctx);\n" +
        "  }\n" +
        "  [].slice.call(document.querySelectorAll('a[href]')).forEach(function(a){\n" +
        "    var h = a.href || '';\n" +
        "    if (h.indexOf('/product/') < 0) return;\n" +
        "    var m = h.match(/-(\\d{5,10})\\.html/); if (!m) return;\n" +
        "    var id = m[1], name = nameOf(a), img = imgOf(a), test = isTest(a);\n" +
        "    if (seen[id] !== undefined) { var o = items[seen[id]]; if (!o.name && name) o.name = name; if (!o.img && img) o.img = img; o.test = o.test || test; return; }\n" +
        "    seen[id] = items.length;\n" +
        "    items.push({ id: id, url: h.split('?')[0].split('#')[0], name: name, img: img, test: test });\n" +
        "  });\n" +
        "  var b = document.body;\n" +
        "  var bottom = b ? (window.scrollY + window.innerHeight >= b.scrollHeight - 60) : false;\n" +
        "  return JSON.stringify({ t: b ? b.innerText : '', u: location.href, i: items, b: bottom });\n" +
        "}catch(e){ return ''; }})()";

    static boolean looksReady(String text) {
        String t = text.toLowerCase(Locale.GERMANY);
        return t.contains("bewerbungsphase") || t.contains("testpilot");
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static Result load(Context ctx, String url, long timeoutMs) {
        final Result res = new Result();
        final CountDownLatch done = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final WebView[] holder = new WebView[1];
        final long start = System.currentTimeMillis();

        main.post(() -> {
            try {
                WebView wv = new WebView(ctx.getApplicationContext());
                holder[0] = wv;
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setBlockNetworkImage(true);
                s.setUserAgentString(UA);
                CookieManager.getInstance().setAcceptCookie(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
                wv.layout(0, 0, 1080, 2400);

                final boolean[] polling = new boolean[1];
                final int[] bottomHits = new int[1];
                final Runnable[] poll = new Runnable[1];
                poll[0] = () -> {
                    if (done.getCount() == 0) return;
                    wv.evaluateJavascript(POLL_JS, value -> {
                        boolean bottom = false;
                        try {
                            String json = new org.json.JSONTokener(value).nextValue().toString();
                            JSONObject o = new JSONObject(json);
                            res.text = o.optString("t");
                            res.finalUrl = o.optString("u");
                            JSONArray items = o.optJSONArray("i");
                            if (items != null && items.length() >= res.items.length()) res.items = items;
                            bottom = o.optBoolean("b");
                        } catch (Exception ignored) {
                        }
                        if (bottom) bottomHits[0]++;
                        else bottomHits[0] = 0;
                        long elapsed = System.currentTimeMillis() - start;
                        // Fertig: Seite geladen, ganz unten angekommen (2x hintereinander, damit Nachgeladenes da ist)
                        boolean ready = (elapsed > 7000 && looksReady(res.text) && bottomHits[0] >= 2) || elapsed > timeoutMs;
                        if (ready) done.countDown();
                        else main.postDelayed(poll[0], 1500);
                    });
                };

                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String u) {
                        if (!polling[0]) {
                            polling[0] = true;
                            main.postDelayed(poll[0], 2500);
                        }
                    }
                });
                wv.loadUrl(url);
                // Falls onPageFinished nie kommt: trotzdem nachsehen
                main.postDelayed(() -> {
                    if (!polling[0]) {
                        polling[0] = true;
                        poll[0].run();
                    }
                }, 12000);
            } catch (Exception e) {
                res.error = "Browser-Fehler: " + e.getClass().getSimpleName();
                done.countDown();
            }
        });

        try {
            if (!done.await(timeoutMs + 10000, TimeUnit.MILLISECONDS) && res.text.isEmpty()) {
                res.error = "Zeitüberschreitung";
            }
        } catch (InterruptedException e) {
            res.error = "Abgebrochen";
        }
        main.post(() -> {
            if (holder[0] != null) {
                try {
                    holder[0].stopLoading();
                    holder[0].destroy();
                } catch (Exception ignored) {
                }
            }
            CookieManager.getInstance().flush();
        });
        if (res.error == null && res.text.trim().isEmpty()) res.error = "Seite blieb leer";
        return res;
    }
}
