/* Fox Testpilot: füllt Bewerbungsformulare mit den gespeicherten Daten aus.
   Läuft in jeder Seite und jedem eingebetteten Formular (iframe) des App-Browsers.
   Füllt nur leere Felder, setzt KEINE Häkchen und schickt NICHTS ab. */
(function () {
  if (window.__foxFillInit) return;
  window.__foxFillInit = true;
  var D = window.__foxData || {};
  var count = 0, foundForm = false, sending = false, tries = 0, filled = [];
  var FL = { email: "E-Mail", first: "Vorname", last: "Nachname", name: "Name", streetfull: "Straße + Nr.", street: "Straße", nr: "Hausnummer",
    zip: "PLZ", city: "Ort", phone: "Telefon", birth: "Geburtsdatum", text: "Bewerbungstext" };
  var TOP = window.top === window;

  // Auto-Modus: Stand an die oberste Seite melden (das Formular kann in einem eingebetteten Rahmen stecken)
  function tell(state) {
    if (TOP) { if (window.__foxState !== "success") window.__foxState = state; }
    else { try { window.top.postMessage("fox-" + state, "*"); } catch (x) {} }
  }
  if (TOP) window.addEventListener("message", function (ev) {
    if (ev.data === "fox-submitted" || ev.data === "fox-success") tell(String(ev.data).slice(4));
    if (ev.data && ev.data.foxProbe) { window.__foxProbe = ev.data.foxProbe; tell("probe"); }
  });
  /** Probelauf: nicht abschicken, sondern berichten, was passiert wäre. */
  function probeReport(b, checks) {
    var rep = { frame: location.host + location.pathname.slice(0, 50), filled: filled, checks: checks, button: b ? String(b.textContent || b.value || "").replace(/\s+/g, " ").trim().slice(0, 60) || "(ohne Text)" : "" };
    if (b) { try { b.style.outline = "4px solid #e53935"; b.style.outlineOffset = "2px"; b.scrollIntoView({ block: "center" }); } catch (x) {} }
    if (TOP) { window.__foxProbe = rep; tell("probe"); }
    else { try { window.top.postMessage({ foxProbe: rep }, "*"); } catch (x) {} }
  }

  /** Nur im Auto-Modus: Pflicht-Häkchen setzen und das Formular absenden – genau einmal je Produkt. */
  function autoSend(g, root) {
    if (!D.auto || sending || tries >= 5 || !g.area) return;
    var ta = g.items.filter(function (x) { return x.f === "text"; })[0];
    if (!ta || !ta.e.value) return;
    try { if (sessionStorage.getItem("foxSent" + D.aid)) return; } catch (x) {}
    sending = true; tries++;
    setTimeout(function () {
      var checks = [];
      [].slice.call(root.querySelectorAll("input[type=checkbox]")).forEach(function (c) {
        if (c.checked || c.disabled) return;
        var k = keyOf(c);
        if (/newsletter|werb|marketing|angebot/.test(k)) return;          // keine Werbe-Einwilligung
        if (c.required || /teilnahme|datenschutz|einverstanden|akzeptier|bestätig|agb|bedingung/.test(k)) { c.click(); checks.push(k.trim().slice(0, 80)); }
      });
      setTimeout(function () {
        var btns = [].slice.call(root.querySelectorAll("button,input[type=submit],[role=button]")).filter(visible);
        var b = btns.filter(function (x) { return /bewerb|teilnehm|absenden|abschicken|senden|mitmachen/.test(low(x.textContent || x.value)); })[0] ||
          (root !== document ? btns.filter(function (x) { return low(x.type) === "submit"; })[0] : null);
        if (D.probe) {
          if (b || tries >= 3) probeReport(b, checks); else sending = false;
          return;
        }
        if (!b || b.disabled) { sending = false; return; }
        try { sessionStorage.setItem("foxSent" + D.aid, "1"); } catch (x) {}
        b.click();
        tell("submitted");
        var n = 0, w = setInterval(function () {
          if (++n > 20) return clearInterval(w);
          if (/vielen dank|erfolgreich|eingegangen|bewerbung erhalten|danke für/.test(low(document.body.innerText))) { tell("success"); clearInterval(w); }
        }, 1000);
      }, 700);
    }, 1500);
  }

  function low(s) { return String(s || "").toLowerCase().replace(/\s+/g, " "); }

  function keyOf(e) {
    var s = [e.name, e.id, e.placeholder, e.getAttribute("aria-label"), e.getAttribute("autocomplete"), e.title].join(" ");
    try { if (e.labels) for (var i = 0; i < e.labels.length; i++) s += " " + e.labels[i].textContent; } catch (x) {}
    var lb = e.getAttribute("aria-labelledby");
    if (lb) lb.split(/\s+/).forEach(function (id) { var x = document.getElementById(id); if (x) s += " " + x.textContent; });
    // Nur wenn sonst nichts da ist: Text direkt um das Feld (und nur, wenn dort kein zweites Feld steht)
    if (!low(s).replace(/[^a-zäöüß]/g, "")) {
      for (var p = e.parentElement, d = 0; p && d < 3; p = p.parentElement, d++) {
        if (p.querySelectorAll("input,select,textarea").length > 1) break;
        var t = (p.textContent || "").trim();
        if (t.length > 80) break;
        if (t) { s += " " + t; break; }
      }
    }
    return low(s);
  }

  var RX = {
    skip: /such|search|passwor|password|gutschein|coupon|captcha|newsletter|login/,
    email: /e-?mail/,
    nr: /hausn|haus-n|house|\bnr\b|street.?number/,
    street: /stra(ss|ß)e|street|adresse|address/,
    zip: /plz|postleitzahl|zip|postal|postcode/,
    first: /vorname|first.?name|given/,
    last: /nachname|last.?name|family|surname/,
    city: /\bort\b|wohnort|stadt|city|town/,
    phone: /telefon|phone|mobil|handy|\btel\b/,
    birth: /geburt|birth/,
    name: /\bname\b/
  };

  function fieldOf(e) {
    var t = low(e.type), k = keyOf(e);
    if (RX.skip.test(k)) return null;
    if (t === "email" || RX.email.test(k)) return "email";
    var st = RX.street.test(k), nr = RX.nr.test(k);
    if (st && nr) return "streetfull";
    if (nr) return "nr";
    if (st) return "street";
    if (RX.zip.test(k)) return "zip";
    if (RX.first.test(k)) return "first";
    if (RX.last.test(k)) return "last";
    if (RX.city.test(k)) return "city";
    if (t === "tel" || RX.phone.test(k)) return "phone";
    if (RX.birth.test(k)) return "birth";
    if (RX.name.test(k)) return "name";
    return null;
  }

  function birthFor(e) {
    var b = String(D.birth || "").trim();
    var m = b.match(/^(\d{1,2})\.(\d{1,2})\.(\d{4})$/);
    if (low(e.type) === "date") return m ? m[3] + "-" + ("0" + m[2]).slice(-2) + "-" + ("0" + m[1]).slice(-2) : "";
    return b;
  }

  function valueFor(f, e, hasNr) {
    var full = [D.street, D.nr].filter(Boolean).join(" ");
    switch (f) {
      case "email": return D.email;
      case "first": return D.first;
      case "last": return D.last;
      case "name": return [D.first, D.last].filter(Boolean).join(" ");
      case "streetfull": return full;
      case "street": return hasNr ? D.street : full;
      case "nr": return D.nr;
      case "zip": return D.zip;
      case "city": return D.city;
      case "phone": return D.phone;
      case "birth": return birthFor(e);
    }
    return "";
  }

  function setVal(e, v, f) {
    filled.push([FL[f] || f || "Feld", String(v).length > 60 ? String(v).slice(0, 57) + "…" : String(v)]);
    var proto = e.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var d = Object.getOwnPropertyDescriptor(proto, "value");
    if (d && d.set) d.set.call(e, v); else e.value = v;
    ["input", "change", "blur"].forEach(function (n) { e.dispatchEvent(new Event(n, { bubbles: true })); });
    try { e.style.outline = "2px solid #f28c28"; } catch (x) {}
    count++;
  }

  function visible(e) {
    if (e.disabled || e.readOnly) return false;
    var r = e.getBoundingClientRect ? e.getBoundingClientRect() : { width: 1, height: 1 };
    return r.width > 0 && r.height > 0;
  }

  function fill() {
    var groups = [], roots = [];
    function group(e) {
      var root = e.form || document, i = roots.indexOf(root);
      if (i < 0) { roots.push(root); groups.push({ items: [], area: false }); i = roots.length - 1; }
      return groups[i];
    }
    var bad = { hidden: 1, password: 1, checkbox: 1, radio: 1, file: 1, submit: 1, button: 1, search: 1, image: 1, reset: 1 };
    [].slice.call(document.querySelectorAll("input")).forEach(function (e) {
      if (bad[low(e.type)] || !visible(e)) return;
      var f = fieldOf(e);
      if (f) group(e).items.push({ e: e, f: f });
    });
    [].slice.call(document.querySelectorAll("textarea")).forEach(function (e) {
      if (!visible(e) || RX.skip.test(keyOf(e)) || /recaptcha/.test(low(e.name + e.id))) return;
      var g = group(e); g.area = true; g.items.push({ e: e, f: "text" });
    });
    groups.forEach(function (g) {
      // Nur echte Formulare: mit Textfeld für die Bewerbung oder mindestens 2 erkannten Feldern
      if (!g.area && g.items.length < 2) return;
      var hasNr = g.items.some(function (x) { return x.f === "nr"; });
      g.items.forEach(function (x) {
        if (x.e.value) return;
        var v = x.f === "text" ? D.text : valueFor(x.f, x.e, hasNr);
        if (v) setVal(x.e, String(v), x.f);
      });
      foundForm = true;
      autoSend(g, roots[groups.indexOf(g)]);
    });
    if (!groups.some(function (g) { return g.area || g.items.length >= 2; })) return show();

    // Auswahlfelder: Anrede und Land
    [].slice.call(document.querySelectorAll("select")).forEach(function (s) {
      if (!visible(s) || s.selectedIndex > 0) return;
      var k = keyOf(s), want = null;
      if (/anrede|salutation|geschlecht|gender/.test(k)) want = low(D.salutation);
      else if (/\bland\b|country/.test(k)) want = "deutschland";
      if (!want) return;
      for (var i = 0; i < s.options.length; i++) {
        var t = low(s.options[i].textContent).trim(), v = low(s.options[i].value);
        if (t === want || (want === "deutschland" && (v === "de" || t === "germany"))) {
          s.selectedIndex = i;
          s.dispatchEvent(new Event("change", { bubbles: true }));
          count++;
          break;
        }
      }
    });
    if (D.salutation) {
      [].slice.call(document.querySelectorAll("input[type=radio]")).forEach(function (r) {
        if (r.checked || !visible(r)) return;
        var t = low(r.value).trim(), lt = "";
        try { if (r.labels && r.labels[0]) lt = low(r.labels[0].textContent).trim(); } catch (x) {}
        var w = low(D.salutation);
        if (t === w || lt === w) {
          var others = r.name ? document.querySelectorAll('input[type=radio][name="' + r.name + '"]:checked') : [];
          if (!others.length) { r.click(); count++; }
        }
      });
    }
    show();
  }

  function show() {
    if (!count || !document.body) return;
    var b = document.getElementById("__foxBanner");
    if (!b) {
      b = document.createElement("div");
      b.id = "__foxBanner";
      b.style.cssText = "position:fixed;left:8px;right:8px;bottom:8px;z-index:2147483647;background:#1f9d55;color:#fff;font:600 14px system-ui,sans-serif;padding:10px 14px;border-radius:12px;box-shadow:0 4px 14px rgba(0,0,0,.3);text-align:center";
      b.onclick = function () { b.remove(); };
      document.body.appendChild(b);
    }
    b.textContent = D.auto ? "🤖 " + count + " Felder ausgefüllt – wird automatisch abgeschickt" : "🦊 " + count + " Felder ausgefüllt – bitte prüfen, Häkchen setzen und selbst absenden";
  }

  function relay() {
    [].slice.call(document.querySelectorAll("iframe")).forEach(function (f) {
      try { f.contentWindow.postMessage("fox-fill", "*"); } catch (x) {}
    });
  }

  // Formular-Bericht für die Fehlersuche: nur Feld-Beschreibungen, keine eingegebenen Inhalte
  function diagFields() {
    return [].slice.call(document.querySelectorAll("input,select,textarea,button")).filter(function (e) { return low(e.type) !== "hidden"; }).slice(0, 60).map(function (e) {
      var tag = e.tagName.toLowerCase();
      return tag + (e.type ? ":" + e.type : "") + " | " + (tag === "button" ? low(e.textContent).trim().slice(0, 40) : keyOf(e).trim().slice(0, 70)) +
        (tag === "button" ? "" : " => " + (tag === "textarea" ? "text" : fieldOf(e) || "-")) + (e.required ? " *" : "") + (visible(e) ? "" : " (unsichtbar)");
    });
  }
  function diagSend() {
    var rep = { frame: location.host + location.pathname.slice(0, 60), top: TOP, fields: diagFields() };
    if (TOP) window.__foxDiagAll.push(rep);
    else { try { window.top.postMessage({ foxDiag: rep }, "*"); } catch (x) {} }
    [].slice.call(document.querySelectorAll("iframe")).forEach(function (f) {
      try { f.contentWindow.postMessage("fox-diag", "*"); } catch (x) {}
    });
  }
  if (TOP) {
    window.__foxDiagAll = [];
    window.__foxDiag = function () { window.__foxDiagAll = []; diagSend(); return 1; };
    window.addEventListener("message", function (ev) { if (ev.data && ev.data.foxDiag) window.__foxDiagAll.push(ev.data.foxDiag); });
  }
  window.addEventListener("message", function (ev) { if (ev.data === "fox-diag" && !TOP) diagSend(); });

  window.__foxFill = function () { try { fill(); } catch (x) {} relay(); return count; };
  window.addEventListener("message", function (ev) { if (ev.data === "fox-fill") window.__foxFill(); });

  var runs = 0;
  var timer = setInterval(function () {
    if (++runs > 60) return clearInterval(timer);
    try { fill(); } catch (x) {}
    // Auto-Modus: nach unten blättern, damit das Formular geladen wird; notfalls einmal den Teilnahme-Knopf antippen
    if (D.auto && TOP && !foundForm && !window.__foxState) {
      try {
        window.scrollBy(0, 900);
        if (runs === 12) {
          var cta = [].slice.call(document.querySelectorAll("a,button")).filter(function (e) {
            return visible(e) && /^(zur teilnahme|jetzt bewerben|jetzt teilnehmen|testpilot werden|hier bewerben|zum teilnahmeformular)/.test(low(e.textContent).trim());
          })[0];
          if (cta) cta.click();
        }
      } catch (x) {}
    }
  }, 1500);
})();
