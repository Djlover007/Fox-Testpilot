"""Fox Testpilot für Windows – Prüf-Logik (ohne Oberfläche).

Steuert Microsoft Edge (oder Chrome) über Playwright, wie die Android-App ihren unsichtbaren Browser.
Daten liegen in %APPDATA%\\FoxTestpilot. Jede Person hat ein eigenes Browser-Profil,
dadurch bleiben die Anmeldungen bei MediaMarkt/Saturn getrennt.
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta
from pathlib import Path

APP_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "FoxTestpilot"
CONFIG_PATH = APP_DIR / "config.json"
PROFILES = APP_DIR / "profile"

SHOPS = [
    {"key": "mm", "name": "MediaMarkt", "domain": "mediamarkt.de", "url": "https://www.mediamarkt.de/de/campaign/testpiloten",
     "account": "https://www.mediamarkt.de/de/myaccount", "prefix": ""},
    {"key": "saturn", "name": "Saturn", "domain": "saturn.de", "url": "https://www.saturn.de/de/campaign/testpiloten",
     "account": "https://www.saturn.de/de/myaccount", "prefix": "s"},
]

DEFAULT_TEXT = "Ich würde {produkt} gerne ausgiebig im Alltag testen und danach eine ehrliche, ausführliche Bewertung mit Fotos schreiben."

DEFAULT_CONFIG = {
    "interval": 30,
    "alarm": False,
    "autostart": False,
    "probe": True,
    "discordUrl": "",
    "discordOn": False,
    "discordPing": False,
    "persons": [{"id": "p1", "label": "Ich", "profile": {"text": DEFAULT_TEXT}, "accounts": {}, "applied": {}, "info": {}}],
    "active": "p1",
    "custom": [],
    "last": None,
    "state": {},
    "history": [],
}

PERSON_FIELDS = ["salutation", "first", "last", "street", "nr", "zip", "city", "email", "phone", "birth", "text", "k1", "t1", "k2", "t2", "k3", "t3"]


def resource(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    path = os.path.join(base, name)
    if not os.path.exists(path) and name == "autofill.js":  # beim Start aus dem Quellcode
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "www", "autofill.js")
    return path


def load_config():
    APP_DIR.mkdir(parents=True, exist_ok=True)
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    try:
        cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8")))
    except Exception:  # noqa: BLE001
        pass
    if not cfg.get("persons"):
        cfg["persons"] = json.loads(json.dumps(DEFAULT_CONFIG["persons"]))
    return cfg


def save_config(cfg):
    APP_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG_PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(cfg, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, CONFIG_PATH)


def person(cfg, pid=None):
    pid = pid or cfg.get("active")
    for p in cfg["persons"]:
        if p["id"] == pid:
            return p
    return cfg["persons"][0]


def history(cfg, line):
    cfg.setdefault("history", []).insert(0, f"{datetime.now():%d.%m. %H:%M}  {line}")
    del cfg["history"][80:]


# ---------- Passwort mit Windows-DPAPI verschlüsseln (nur dieser Windows-Benutzer kann es lesen) ----------
def _dpapi(data: bytes, protect: bool) -> bytes:
    import ctypes
    from ctypes import wintypes

    class BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    buf = ctypes.create_string_buffer(data, len(data))
    inp = BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    out = BLOB()
    fn = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    if not fn(ctypes.byref(inp), None, None, None, None, 0, ctypes.byref(out)):
        raise OSError("DPAPI fehlgeschlagen")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


def encrypt_pw(pw: str) -> str:
    import base64
    if not pw:
        return ""
    try:
        return "dpapi:" + base64.b64encode(_dpapi(pw.encode("utf-8"), True)).decode()
    except Exception:  # noqa: BLE001  (nicht unter Windows)
        return ""


def decrypt_pw(enc: str) -> str:
    import base64
    if not enc or not enc.startswith("dpapi:"):
        return ""
    try:
        return _dpapi(base64.b64decode(enc[6:]), False).decode("utf-8")
    except Exception:  # noqa: BLE001
        return ""


# ---------- Auslesen der Testpiloten-Seite (gleiche Regeln wie in der Android-App) ----------
PHASE = re.compile(r"bewerbungs(?:phase|zeitraum)[^0-9]{0,60}?(\d{1,2})\.(\d{1,2})\.(\d{4})[^0-9]{1,25}?(\d{1,2})\.(\d{1,2})\.(\d{4})", re.I)
_N, _SEP = r"(\d{1,3})", r"[\s:.|]*"
_T, _H, _M = r"(?:Tage?n?|Tg\.?)", r"(?:Stunden?|Std\.?)", r"(?:Minuten?|Min\.?)"
COUNTDOWNS = [
    re.compile(_N + _SEP + _T + _SEP + _N + _SEP + _H + _SEP + _N + _SEP + _M, re.I),
    re.compile(r"\b" + _T + _SEP + _N + _SEP + _H + _SEP + _N + _SEP + _M + _SEP + _N, re.I),
    re.compile(_N + _SEP + _N + _SEP + _N + "(?:" + _SEP + r"\d{1,2})?" + _SEP + _T + _SEP + _H + _SEP + _M, re.I),
    re.compile(r"\b" + _T + _SEP + _H + _SEP + _M + "(?:" + _SEP + r"(?:Sekunden?|Sek\.?))?" + _SEP + _N + _SEP + _N + _SEP + _N, re.I),
]


def countdown_ms(text):
    for p in COUNTDOWNS:
        m = p.search(text)
        if not m:
            continue
        d, h, mi = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if h > 23 or mi > 59 or d > 120:
            continue
        return ((d * 24 + h) * 60 + mi) * 60000
    return -1


def countdown_raw(text):
    for p in COUNTDOWNS:
        m = p.search(text)
        if m:
            return m.group(0).replace("\n", " ")
    return ""


def snap(t):
    q = 15 * 60000
    near = round(t / q) * q
    return near if abs(near - t) <= 5 * 60000 else round(t / 60000) * 60000


def ymd(d):
    return d.year * 10000 + d.month * 100 + d.day


def ymd_of_ms(ms):
    return ymd(datetime.fromtimestamp(ms / 1000))


def fmt_ymd(v):
    return f"{v % 100:02d}.{v // 100 % 100:02d}.{v // 10000:04d}"


def fmt_when(ms):
    d = datetime.fromtimestamp(ms / 1000)
    return ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"][d.weekday()] + f"., {d:%d.%m. um %H:%M} Uhr"


def looks_blocked(text):
    t = text[:4000].lower()
    return any(x in t for x in ("access denied", "captcha", "zugriff verweigert", "pardon our interruption", "request unsuccessful"))


def headline(text):
    for line in text.split("\n"):
        l = line.strip()
        low = l.lower()
        if 8 < len(l) < 90 and "testpilot" in low and "teilnahmebeding" not in low:
            return l
    return ""


def name_from_url(url):
    s = re.sub(r".*/product/_?", "", url)
    s = re.sub(r"-\d+\.html.*$", "", s)
    return " ".join(w[:1].upper() + w[1:] for w in s.split("-") if w) or "Produkt"


def is_login_url(url):
    u = (url or "").lower()
    return any(x in u for x in ("login", "/auth", "signin", "anmeld"))


def keywords(s):
    return [k.strip() for k in re.split(r"[,;\n]", (s or "").lower()) if k.strip()]


def status_text(s):
    return {"open": "Bewerbung offen", "soon": "Nächste Runde angekündigt", "over": "Runde beendet"}.get(s, "Gerade keine Testaktion")


def shop_line(l):
    """Kurzer Status eines Shops für die Oberfläche."""
    if l.get("error") and not l.get("status"):
        return "⚠️ " + l["error"]
    now = time.time() * 1000
    st = l.get("status")
    if st == "soon" and l.get("startAt", 0) > now:
        return "📅 Start " + fmt_when(l["startAt"])
    if st == "open" and l.get("endAt", 0) > now:
        return "🚀 Bewerbung offen bis " + fmt_when(l["endAt"])
    if st == "open":
        return "🚀 Bewerbung offen" + ("" if l.get("endUnknown") or not l.get("end") else " bis " + l["end"])
    if st == "soon":
        return ("📅 Bewerbung " + l["start"] + " – " + l["end"]) if l.get("start") else "📅 Nächste Runde demnächst"
    if st == "over":
        return "Runde beendet am " + l.get("end", "")
    return "Gerade keine Testaktion" if st else "Noch nicht geprüft"


# ---------- Discord ----------
DISCORD_VALID = re.compile(r"^https://(?:(?:ptb|canary)\.)?discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_\-]+/?$")


def discord_send(hook, title, body, url=None, ping=False):
    """Gibt den HTTP-Code zurück (-1 bei Fehler)."""
    if not DISCORD_VALID.match((hook or "").strip()):
        return -1
    color = 0xF08300 if "Saturn" in title else 0xE3000F if "MediaMarkt" in title else 0xF28C28
    embed = {"title": title[:250], "description": body[:3800], "color": color, "footer": {"text": "🦊 Fox Testpilot (Windows)"},
             "timestamp": datetime.now().astimezone().isoformat(timespec="seconds")}
    if url and url.startswith("https://"):
        embed["url"] = url
    msg = {"username": "Fox Testpilot", "embeds": [embed], "allowed_mentions": {"parse": ["everyone"] if ping else []}}
    if ping:
        msg["content"] = "@everyone"
    req = urllib.request.Request(hook.strip(), data=json.dumps(msg).encode("utf-8"), method="POST",
                                 headers={"Content-Type": "application/json", "User-Agent": "FoxTestpilot (Windows)"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:  # noqa: BLE001
        return -1


# ---------- Ausfüll-Daten ----------
def fill_data(cfg, pid, product, url, auto_aid=None, probe=False):
    p = person(cfg, pid)
    prof = dict(p.get("profile") or {})
    name = (product or "").strip() or "das Testprodukt"
    text, low = prof.get("text") or DEFAULT_TEXT, name.lower()
    for i in (1, 2, 3):
        t = (prof.get(f"t{i}") or "").strip()
        if t and any(k in low for k in keywords(prof.get(f"k{i}"))):
            text = t
            break
    prof["text"] = text.replace("{produkt}", name)
    for i in (1, 2, 3):
        prof.pop(f"k{i}", None)
        prof.pop(f"t{i}", None)
    for s in SHOPS:
        if s["domain"] in (url or ""):
            mail = (p.get("accounts", {}).get(s["key"]) or {}).get("email")
            if mail:
                prof["email"] = mail
    if auto_aid:
        prof.update({"auto": True, "aid": auto_aid, "probe": bool(probe)})
    return prof


class Engine:
    """Läuft komplett in EINEM Thread (Playwright-Vorgabe)."""

    def __init__(self, log=print):
        self.log = log
        self.pw = None
        self.ctxs = {}
        self.poll_js = "\n".join(open(resource("poll.js"), encoding="utf-8").read().split("\n")[1:])
        self.login_js = open(resource("login.js"), encoding="utf-8").read()
        self.fill_js = open(resource("autofill.js"), encoding="utf-8").read()

    # ----- Browser: ein Profil zum Prüfen ("monitor") und eines je Person -----
    def browser(self, key="monitor", cfg=None):
        ctx = self.ctxs.get(key)
        try:
            if ctx is not None and (ctx.browser is None or ctx.browser.is_connected()):
                ctx.pages  # noqa: B018 – wirft, wenn geschlossen
                return ctx
        except Exception:  # noqa: BLE001
            pass
        from playwright.sync_api import sync_playwright
        if self.pw is None:
            self.pw = sync_playwright().start()
        folder = PROFILES / key
        folder.mkdir(parents=True, exist_ok=True)
        last_err = None
        for channel in ("msedge", "chrome", None):
            try:
                kw = dict(user_data_dir=str(folder), headless=False, no_viewport=True,
                          args=["--window-position=-32000,-32000", "--window-size=1200,950", "--disable-blink-features=AutomationControlled"],
                          ignore_default_args=["--enable-automation"])
                if channel:
                    kw["channel"] = channel
                ctx = self.pw.chromium.launch_persistent_context(**kw)
                ctx.on("close", lambda *_ , k=key: self.ctxs.pop(k, None))
                if key != "monitor" and cfg is not None:
                    self._add_login(ctx, cfg, key)
                self.ctxs[key] = ctx
                self.log(f"Browser gestartet ({channel or 'chromium'}, Profil {key})")
                return ctx
            except Exception as e:  # noqa: BLE001
                last_err = e
        raise RuntimeError(f"Kein Browser gefunden (Edge/Chrome): {last_err}")

    def _add_login(self, ctx, cfg, pid):
        p = person(cfg, pid)
        acc = {}
        for s in SHOPS:
            a = p.get("accounts", {}).get(s["key"]) or {}
            pw = decrypt_pw(a.get("pw", ""))
            if a.get("email") and pw:
                acc[s["domain"]] = {"e": a["email"], "p": pw}
        if acc:
            ctx.add_init_script(self.login_js.replace("__ACCOUNTS__", json.dumps(acc)))

    def reset(self, key):
        """Browser einer Person neu starten (z. B. nach geänderten Zugangsdaten)."""
        ctx = self.ctxs.pop(key, None)
        if ctx is not None:
            try:
                ctx.close()
            except Exception:  # noqa: BLE001
                pass

    def show(self, page):
        try:
            cdp = page.context.new_cdp_session(page)
            wid = cdp.send("Browser.getWindowForTarget")["windowId"]
            cdp.send("Browser.setWindowBounds", {"windowId": wid, "bounds": {"windowState": "normal"}})
            cdp.send("Browser.setWindowBounds", {"windowId": wid, "bounds": {"left": 80, "top": 40, "width": 1200, "height": 950}})
        except Exception:  # noqa: BLE001
            pass
        try:
            page.bring_to_front()
        except Exception:  # noqa: BLE001
            pass

    def close(self):
        for k in list(self.ctxs):
            self.reset(k)
        try:
            if self.pw:
                self.pw.stop()
        except Exception:  # noqa: BLE001
            pass

    def load(self, url, timeout=40, quick=0):
        """Seite unsichtbar laden und nach unten scrollen. Gibt dict(text, url, items, error) zurück."""
        res = {"text": "", "url": url, "items": [], "error": None}
        page = self.browser().new_page()
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=timeout * 1000)
            start, bottom_hits = time.time(), 0
            page.wait_for_timeout(2500)
            while True:
                try:
                    o = page.evaluate(self.poll_js) or {}
                except Exception:  # noqa: BLE001
                    o = {}
                res["text"] = o.get("t") or res["text"]
                res["url"] = o.get("u") or res["url"]
                if len(o.get("i") or []) >= len(res["items"]):
                    res["items"] = o.get("i") or []
                bottom_hits = bottom_hits + 1 if o.get("b") else 0
                el = time.time() - start
                low = res["text"].lower()
                ready = el > quick if quick else (el > 7 and ("bewerbungsphase" in low or "testpilot" in low) and bottom_hits >= 2)
                if ready or el > timeout:
                    break
                page.wait_for_timeout(1500)
        except Exception as e:  # noqa: BLE001
            res["error"] = f"Laden fehlgeschlagen: {type(e).__name__}"
        finally:
            try:
                page.close()
            except Exception:  # noqa: BLE001
                pass
        if not res["error"] and not res["text"].strip():
            res["error"] = "Seite blieb leer"
        if not res["error"] and looks_blocked(res["text"]):
            res["error"] = "Seite hat den Abruf blockiert – später nochmal"
        return res

    # ----- Prüfen -----
    def check_all(self, cfg, notify, manual=False):
        prev = cfg.get("last") or {}
        out = {"time": int(time.time() * 1000), "shops": []}
        for i, shop in enumerate(SHOPS):
            before = next((x for x in prev.get("shops", []) if x.get("key") == shop["key"]), None)
            out["shops"].append(self.check_shop(cfg, i, shop, before, notify, manual))
        out["custom"] = self.check_custom(cfg, prev.get("custom") or [], notify, manual)
        cfg["last"] = out
        return out

    def check_shop(self, cfg, idx, shop, prev, notify, manual):
        name, page, key = shop["name"], shop["url"], shop["key"]
        st = {"key": key, "name": name, "url": page}
        w = self.load(page)
        if w["error"]:
            if prev:
                st = dict(prev)
            st["error"] = w["error"]
            if manual:
                history(cfg, f"⚠️ {name}: {w['error']}")
            return st
        text = w["text"]
        start = end = 0
        m = PHASE.search(text)
        if m:
            g = [int(x) for x in m.groups()]
            start, end = g[2] * 10000 + g[1] * 100 + g[0], g[5] * 10000 + g[4] * 100 + g[3]
        now_ms, today = int(time.time() * 1000), ymd(date.today())
        if start and end >= start:
            status = "soon" if today < start else "open" if today <= end else "over"
        else:
            status = "soon" if "demnächst" in text.lower() else "none"
        start_at = end_at = 0
        cd = countdown_ms(text)
        if cd >= 0:
            target = snap(now_ms + cd)
            if prev:
                old = max(prev.get("startAt", 0), prev.get("endAt", 0))
                if old and abs(old - target) <= 10 * 60000:
                    target = old
            tday = ymd_of_ms(target)
            if status == "open" and tday > start:
                end_at = target
            else:
                start_at = target
                status = "soon" if target > now_ms else "open"
                if not start:
                    start = end = tday
                    st["endUnknown"] = True
        phase_key = f"{start}-{end}" if start else ""

        items = w["items"]
        any_test = any(o.get("test") for o in items)
        products = []
        for o in items:
            if any_test and not o.get("test"):
                continue
            o = dict(o)
            n = name_from_url(o.get("url", ""))
            if " " in n or not o.get("name"):
                o["name"] = n
            o["aid"] = shop["prefix"] + o["id"]
            products.append(o)
            if len(products) >= 40:
                break

        state = cfg.setdefault("state", {}).setdefault(key, {})
        first_run = "seen" not in state
        seen = set(state.get("seen", []))
        phase_changed = phase_key and phase_key != state.get("phaseKey", "")
        new_ids = set() if phase_changed else set(state.get("newIds", []))
        fresh = []
        for o in products:
            if o["id"] not in seen:
                if not first_run:
                    new_ids.add(o["id"])
                    fresh.append(o["name"])
                seen.add(o["id"])
            o["isNew"] = o["id"] in new_ids
        state["seen"] = list(seen)[-400:]
        state["newIds"] = list(new_ids)[-100:]
        if phase_key:
            state["phaseKey"] = phase_key

        st.update({"status": status, "start": fmt_ymd(start) if start else "", "end": fmt_ymd(end) if end else "",
                   "startYmd": start, "endYmd": end, "startAt": start_at, "endAt": end_at, "headline": headline(text), "products": products})

        notified = state.setdefault("notified", [])
        applied = person(cfg).get("applied", {})
        names = ", ".join(p["name"] for p in products[:4]) + (" …" if len(products) > 4 else "")
        told = False
        if phase_key:
            if status == "open" and "open:" + phase_key not in notified:
                notified.append("open:" + phase_key)
                notify(f"🚀 {name} Testpiloten: Bewerbung ist offen!",
                       ("Jetzt bewerben" if st.get("endUnknown") else f"Bewerben bis {fmt_ymd(end)[:6]}") + (f" – {len(products)} Testprodukte: {names}" if products else ""),
                       alarm=cfg.get("alarm", False), url=page, ping=True)
                history(cfg, f"🚀 {name}: Bewerbungsphase offen")
                told = True
            elif status == "soon" and "soon:" + phase_key not in notified:
                notified.append("soon:" + phase_key)
                when = ("Start am " + fmt_when(start_at)) if start_at else f"Bewerbung vom {fmt_ymd(start)[:6]} bis {fmt_ymd(end)[:6]}"
                notify(f"📅 {name}: neue Testpiloten-Runde angekündigt", when, url=page)
                history(cfg, f"📅 {name}: neue Runde angekündigt – {when}")
                told = True
            if status == "open" and today == end and not st.get("endUnknown") and "last:" + phase_key not in notified:
                notified.append("last:" + phase_key)
                done = sum(1 for p in products if p["aid"] in applied)
                notify(f"⏰ {name}: heute letzter Tag für die Testpiloten",
                       f"Du hast dich für {done} von {len(products)} Produkten beworben." if products else "Heute endet die Bewerbungsphase.", url=page)
        if fresh and not told:
            notify(f"🆕 {name}: {len(fresh)} neue" + ("s Testprodukt" if len(fresh) == 1 else " Testprodukte"), ", ".join(fresh[:4]), url=page)
        if fresh:
            history(cfg, f"🆕 {name} neu: " + ", ".join(fresh))
        del notified[:-60]
        if not prev or prev.get("status") != status:
            history(cfg, f"{name}: {status_text(status)}" + (f" ({fmt_ymd(start)} – {fmt_ymd(end)})" if start else ""))
        return st

    def check_custom(self, cfg, prev, notify, manual):
        out = []
        for p in cfg.get("custom", []):
            r = {"id": p["id"], "name": p["name"], "url": p["url"], "time": int(time.time() * 1000)}
            before = next((x for x in prev if x.get("id") == p["id"]), None)
            w = self.load(p["url"], timeout=30, quick=9)
            if w["error"]:
                r["found"] = (before or {}).get("found", [])
                r["error"] = w["error"]
                if manual:
                    history(cfg, f"⚠️ {p['name']}: {w['error']}")
                out.append(r)
                continue
            text = w["text"].lower()
            found = [k for k in keywords(p.get("keywords")) if k in text]
            old = set((before or {}).get("found", []))
            fresh = [k for k in found if k not in old]
            r["found"] = found
            if before is not None and fresh:
                body = "Neu auf der Seite: „" + "“, „".join(fresh) + "“"
                notify("🌐 " + p["name"], body, url=p["url"])
                history(cfg, f"🌐 {p['name']}: {body}")
            out.append(r)
        return out

    def diagnose(self):
        lines = [f"Fox Testpilot (Windows) Diagnose {datetime.now():%d.%m.%Y %H:%M}"]
        for shop in SHOPS:
            lines.append(f"\n== {shop['name']} ==")
            w = self.load(shop["url"])
            lines.append("URL: " + w["url"])
            if w["error"]:
                lines.append("Fehler: " + w["error"])
            text = w["text"]
            lines.append(f"Textlänge: {len(text)}")
            lines.append("Überschrift: " + headline(text))
            m = PHASE.search(text)
            lines.append("Zeitraum: " + (m.group(0).replace("\n", " ") if m else "nicht gefunden"))
            ms = countdown_ms(text)
            lines.append("Countdown: " + (countdown_raw(text) or "nicht gefunden") +
                         (f" -> {datetime.fromtimestamp(snap(time.time() * 1000 + ms) / 1000):%a %d.%m. %H:%M}" if ms >= 0 else ""))
            test = sum(1 for o in w["items"] if o.get("test"))
            lines.append(f"Produkt-Links: {len(w['items'])}, davon im Testbereich: {test}")
            for o in w["items"][:8]:
                lines.append(f" {'T' if o.get('test') else '-'} {name_from_url(o.get('url', ''))} ({o.get('id')})")
            lines.append("Wichtige Zeilen:")
            n = 0
            for line in text.split("\n"):
                l = line.strip()
                if 3 <= len(l) <= 160 and any(k in l.lower() for k in ("testpilot", "bewerb", "stunden", "teilnahme", "demnächst")):
                    lines.append(" > " + l)
                    n += 1
                    if n >= 14:
                        break
        return "\n".join(lines)

    def login_state(self, cfg, pid, shop):
        """1 = angemeldet, 0 = nicht angemeldet, -1 = unklar."""
        try:
            page = self.browser(pid, cfg).new_page()
            try:
                page.goto(shop["account"], wait_until="domcontentloaded", timeout=30000)
                page.wait_for_timeout(9000)
                url, text = page.url, page.evaluate("document.body ? document.body.innerText : ''").lower()
            finally:
                page.close()
            if looks_blocked(text):
                return -1
            if is_login_url(url):
                return 0
            if "passwort" in text and ("anmelden" in text or "einloggen" in text) and "abmelden" not in text:
                return 0
            return 1
        except Exception:  # noqa: BLE001
            return -1

    # ----- Sichtbarer Browser: Anmelden / Bewerben mit Ausfüll-Hilfe -----
    def open_visible(self, cfg, pid, url, product=None):
        page = self.browser(pid, cfg).new_page()
        if product is not None:
            page.add_init_script("window.__foxData=" + json.dumps(fill_data(cfg, pid, product, url), ensure_ascii=False) + ";\n" + self.fill_js)
        page.goto(url, wait_until="domcontentloaded", timeout=60000)
        self.show(page)
        return page

    def run_queue(self, cfg, items, progress, on_applied):
        """Auto-Modus: Einträge nacheinander abarbeiten. Gibt (abgeschickt, gesamt, probe_bericht) zurück."""
        total = sum(1 for x in items if not x.get("login"))
        probe = any(x.get("probe") for x in items)
        sent, ok_probe, report = 0, 0, []
        for n, it in enumerate(items):
            pid = it["person"]
            who = it.get("label", "")
            progress(n, len(items), ("🔑 " if it.get("login") else "🧪 " if probe else "🤖 ") + (who + ": " if who else "") + it["name"])
            if it.get("login"):
                page = self.open_visible(cfg, pid, it["url"])
                start, okc = time.time(), 0
                while time.time() - start < 60:
                    page.wait_for_timeout(2000)
                    try:
                        okc = okc + 1 if (time.time() - start > 12 and not is_login_url(page.url)) else 0
                    except Exception:  # noqa: BLE001
                        break
                    if okc >= 2:
                        break
                if okc < 2:
                    self.log(f"Anmeldung bei {it['name']} hat nicht automatisch geklappt – bitte im Browserfenster selbst anmelden")
                    page.wait_for_timeout(45000)
                try:
                    page.close()
                except Exception:  # noqa: BLE001
                    pass
                continue
            page = self.browser(pid, cfg).new_page()
            page.add_init_script("window.__foxData=" + json.dumps(fill_data(cfg, pid, it["name"], it["url"], it["aid"], probe), ensure_ascii=False) + ";\n" + self.fill_js)
            try:
                page.goto(it["url"], wait_until="domcontentloaded", timeout=60000)
            except Exception as e:  # noqa: BLE001
                report.append(f"\n━━ {it['name']} ━━\n❌ Seite lädt nicht ({type(e).__name__})")
                continue
            self.show(page)
            start, submitted, state, done = time.time(), 0, "", False
            while time.time() - start < (60 if probe else 90):
                page.wait_for_timeout(2000)
                try:
                    state = page.evaluate("window.__foxState||''")
                except Exception:  # noqa: BLE001
                    state = "submitted" if submitted else ""
                if "probe" in state:
                    rep = page.evaluate("window.__foxProbe||{}")
                    lines = [f"\n━━ {(who + ': ') if who else ''}{it['name']} ━━", "✍️ Ausgefüllt:"]
                    lines += [f"  · {a}: {b}" for a, b in rep.get("filled", [])]
                    if rep.get("checks"):
                        lines += ["☑️ Häkchen gesetzt:"] + [f"  · {c}" for c in rep["checks"]]
                    if rep.get("button"):
                        lines.append(f"🔴 Hätte geklickt: „{rep['button']}“ (rot markiert)")
                        ok_probe += 1
                    else:
                        lines.append("❌ Absende-Knopf NICHT gefunden")
                    report += lines
                    page.wait_for_timeout(5000)
                    done = True
                    break
                if "success" in state or (submitted and time.time() - submitted > 10):
                    on_applied(pid, it)
                    sent += 1
                    done = True
                    break
                if "submitted" in state and not submitted:
                    submitted = time.time()
            if not done:
                if probe:
                    report.append(f"\n━━ {(who + ': ') if who else ''}{it['name']} ━━\n❌ Kein Bewerbungsformular erkannt (nach 60 Sek.)")
                else:
                    self.log(f"„{it['name']}“: ging nicht automatisch – Fenster bleibt offen, bitte selbst prüfen")
                    continue  # Seite offen lassen
            try:
                page.close()
            except Exception:  # noqa: BLE001
                pass
        progress(len(items), len(items), "Fertig")
        text = ""
        if probe:
            text = f"🧪 Probelauf – NICHTS wurde abgeschickt\n{ok_probe} von {total} Formularen vollständig erkannt\n" + "\n".join(report)
        return sent, total, text


# ---------- Erinnerungen (laufen jede Minute in der Oberfläche) ----------
def reminders(cfg, notify):
    """10 Minuten vor dem Start und Bewertungs-Fristen. Gibt True zurück, wenn kurz nach dem Start geprüft werden soll."""
    now = time.time() * 1000
    rem = cfg.setdefault("reminded", [])
    recheck = False
    for s in (cfg.get("last") or {}).get("shops", []):
        sa = s.get("startAt", 0)
        if not sa:
            continue
        if sa - 10 * 60000 <= now < sa and f"pre:{s['key']}:{sa}" not in rem:
            rem.append(f"pre:{s['key']}:{sa}")
            notify(f"⏰ In 10 Minuten: {s['name']} Testpiloten", f"Die Bewerbung startet um {datetime.fromtimestamp(sa / 1000):%H:%M} Uhr.", url=s.get("url"), ping=True)
        if sa + 60000 <= now < sa + 30 * 60000 and f"start:{s['key']}:{sa}" not in rem:
            rem.append(f"start:{s['key']}:{sa}")
            recheck = True
    today = date.today()
    multi = len(cfg["persons"]) > 1
    for p in cfg["persons"]:
        for aid, o in (p.get("info") or {}).items():
            if o.get("result") != "yes" or not o.get("due"):
                continue
            try:
                due = date.fromisoformat(o["due"])
            except ValueError:
                continue
            who = f" ({p['label']})" if multi else ""
            for days, tag, title in ((3, "d3", "✍️ Bewertung in 3 Tagen fällig"), (0, "d0", "⏰ Bewertung heute fällig")):
                k = f"due:{p['id']}:{aid}:{o['due']}:{tag}"
                if today == due - timedelta(days=days) and datetime.now().hour >= 9 and k not in rem:
                    rem.append(k)
                    notify(title + who, f"{o.get('name', 'Testprodukt')} – Frist {due:%d.%m.}")
    del rem[:-200]
    return recheck


def import_backup(cfg, text):
    """Sicherung aus der Android-App übernehmen (ohne Passwörter)."""
    data = json.loads(text.strip())
    if data.get("app") != "fox-testpilot" or not data.get("persons"):
        raise ValueError("Das ist keine Fox-Testpilot-Sicherung")
    persons = []
    for p in data["persons"][:6]:
        pid = re.sub(r"[^A-Za-z0-9]", "", str(p.get("id", ""))) or f"p{len(persons) + 1}"
        old = next((x for x in cfg["persons"] if x["id"] == pid), {})
        accounts = {}
        for s in SHOPS:
            mail = (p.get("emails") or {}).get(s["key"], "")
            if mail:
                accounts[s["key"]] = {"email": mail, "pw": (old.get("accounts", {}).get(s["key"]) or {}).get("pw", "")}
        persons.append({"id": pid, "label": p.get("label") or "Person", "profile": p.get("profile") or {},
                        "accounts": accounts, "applied": p.get("applied") or {}, "info": p.get("info") or {}})
    cfg["persons"] = persons
    cfg["active"] = persons[0]["id"]
    if isinstance(data.get("custom"), list):
        cfg["custom"] = data["custom"][:8]
    st = data.get("settings") or {}
    if "alarm" in st:
        cfg["alarm"] = bool(st["alarm"])
    return len(persons)


def export_backup(cfg):
    persons = []
    for p in cfg["persons"]:
        persons.append({"id": p["id"], "label": p["label"], "profile": p.get("profile", {}), "applied": p.get("applied", {}),
                        "info": p.get("info", {}), "emails": {k: (v or {}).get("email", "") for k, v in (p.get("accounts") or {}).items()}})
    return json.dumps({"app": "fox-testpilot", "v": 1, "persons": persons, "active": cfg.get("active"), "custom": cfg.get("custom", []),
                       "settings": {"interval": cfg.get("interval", 30), "alarm": cfg.get("alarm", False)}}, ensure_ascii=False)
