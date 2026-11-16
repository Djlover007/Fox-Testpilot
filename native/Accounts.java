package de.fox.testpilot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONObject;

/** Zugangsdaten für myMediaMarkt und mySaturn – verschlüsselt auf dem Gerät gespeichert (Android Keystore). */
public class Accounts {

    /** Schlüssel und Domain je Shop. */
    static final String[][] SHOPS = { { "mm", "mediamarkt.de" }, { "saturn", "saturn.de" } };

    static SharedPreferences store(Context c) throws Exception {
        String key = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        return EncryptedSharedPreferences.create("fox_testpilot_konten", key, c.getApplicationContext(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /** Schlüssel-Vorsatz der gerade gewählten Person (erste Person: keiner). */
    static String pre(Context c) {
        return pre(PilotChecker.activePerson(c));
    }

    static String pre(String personId) {
        return "p1".equals(personId) ? "" : personId + "_";
    }

    static void clearPerson(Context c, String personId) throws Exception {
        SharedPreferences.Editor e = store(c).edit();
        for (String[] s : SHOPS) e.remove(pre(personId) + s[0] + "_email").remove(pre(personId) + s[0] + "_pw");
        e.apply();
    }

    /** Für Sicherung/Import: nur die E-Mail einer bestimmten Person (Passwörter werden nie exportiert). */
    static String emailOf(Context c, String personId, String shop) {
        try {
            return store(c).getString(pre(personId) + shop + "_email", "");
        } catch (Exception e) {
            return "";
        }
    }

    static void setEmailOf(Context c, String personId, String shop, String email) throws Exception {
        store(c).edit().putString(pre(personId) + shop + "_email", email).apply();
    }

    static String email(Context c, String shop) {
        try {
            return store(c).getString(pre(c) + shop + "_email", "");
        } catch (Exception e) {
            return "";
        }
    }

    static String password(Context c, String shop) {
        try {
            return store(c).getString(pre(c) + shop + "_pw", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Leeres Passwort = bisheriges behalten. E-Mail und Passwort leer = Konto löschen. */
    static void save(Context c, String shop, String email, String password, boolean clear) throws Exception {
        SharedPreferences.Editor e = store(c).edit();
        shop = pre(c) + shop;
        if (clear) {
            e.remove(shop + "_email").remove(shop + "_pw");
        } else {
            e.putString(shop + "_email", email);
            if (password != null && !password.isEmpty()) e.putString(shop + "_pw", password);
        }
        e.apply();
    }

    /** Für die Oberfläche: E-Mail und ob ein Passwort gespeichert ist (das Passwort selbst geht nie zurück). */
    static JSONObject overview(Context c) throws Exception {
        JSONObject o = new JSONObject();
        for (String[] s : SHOPS) {
            JSONObject a = new JSONObject();
            a.put("email", email(c, s[0]));
            a.put("hasPassword", !password(c, s[0]).isEmpty());
            o.put(s[0], a);
        }
        return o;
    }

    static String shopForUrl(String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            if (host == null) return null;
            for (String[] s : SHOPS) if (host.equals(s[1]) || host.endsWith("." + s[1])) return s[0];
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Anmelde-Skript für einen Shop: trägt E-Mail und Passwort ins Login-Formular ein und meldet einmal an.
     * Die Zugangsdaten stecken nur in lokalen Variablen und das Skript läuft nur auf der Shop-Domain.
     */
    static String loginScript(Context c, String shop) {
        String email = email(c, shop), pw = password(c, shop);
        if (email.isEmpty() || pw.isEmpty()) return "";
        return "(function(){var E=" + JSONObject.quote(email) + ",P=" + JSONObject.quote(pw) + ";\n" + LOGIN_JS + "})();";
    }

    static final String LOGIN_JS =
        "if(window.top!==window||window.__foxLogin)return;window.__foxLogin=true;\n" +
        "function vis(e){if(e.disabled||e.readOnly)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}\n" +
        "function setVal(e,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(e,v);\n" +
        "  ['input','change','blur'].forEach(function(n){e.dispatchEvent(new Event(n,{bubbles:true}));});}\n" +
        "function mailField(root){var l=[].slice.call(root.querySelectorAll('input')).filter(function(e){\n" +
        "  var k=(e.type+' '+e.name+' '+e.id+' '+(e.getAttribute('autocomplete')||'')).toLowerCase();\n" +
        "  return vis(e)&&/email|e-mail|user|login/.test(k)&&e.type!=='password'&&!/search|newsletter/.test(k);});return l[0]||null;}\n" +
        "var sent=false,runs=0;\n" +
        "var t=setInterval(function(){try{\n" +
        "  if(++runs>45||sent)return clearInterval(t);\n" +
        "  var pw=[].slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];\n" +
        "  var onLogin=/login|auth|signin|anmeld/i.test(location.href);\n" +
        "  if(!pw){if(onLogin){var m0=mailField(document);if(m0&&!m0.value)setVal(m0,E);}return;}\n" +
        "  if(/new-password/.test(pw.getAttribute('autocomplete')||'')||document.querySelectorAll('input[type=password]').length>1)return clearInterval(t);\n" +
        "  var root=pw.form||document,m=mailField(root);\n" +
        "  if(m&&!m.value)setVal(m,E);\n" +
        "  if(!pw.value)setVal(pw,P);\n" +
        "  sent=true;\n" +
        "  var tried=0;try{tried=+sessionStorage.getItem('foxLoginTried')||0;sessionStorage.setItem('foxLoginTried',tried+1);}catch(x){}\n" +
        "  if(tried>0)return;\n" +  // nur EIN automatischer Versuch – bei falschem Passwort keine Schleife
        "  setTimeout(function(){var b=root.querySelector('button[type=submit],input[type=submit]');if(b&&!b.disabled)b.click();},800);\n" +
        "}catch(x){}},1000);\n";
}
