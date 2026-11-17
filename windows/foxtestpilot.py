#!/usr/bin/env python3
"""Fox Testpilot für Windows: überwacht die Testpiloten von MediaMarkt und Saturn, meldet sich (Windows + Discord)
und hilft beim Bewerben (Ausfüllen, Auto-Modus mit Probelauf)."""
import copy
import io
import json
import os
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import urllib.request
import webbrowser
from datetime import date, datetime
from tkinter import messagebox, simpledialog, ttk

import engine
from engine import SHOPS, encrypt_pw, load_config, person, save_config, shop_line

try:
    from version import BUILD
except Exception:  # noqa: BLE001
    BUILD = 0

try:
    from winotify import Notification, audio
except Exception:  # noqa: BLE001
    Notification = None

try:
    from PIL import Image, ImageDraw, ImageFont, ImageTk
except Exception:  # noqa: BLE001
    Image = None

APP_NAME = "Fox Testpilot"
REPO = "Djlover007/Fox-Testpilot"
ICON = engine.resource("fox.ico")
THUMBS = engine.APP_DIR / "bilder"
INTERVALS = {"15 Minuten": 15, "30 Minuten": 30, "1 Stunde": 60, "3 Stunden": 180}
THEMES = {"Automatisch": "auto", "Hell": "light", "Dunkel": "dark"}
FONT = "Segoe UI"

BLUE, FOX, GREEN, PURPLE = "#2b6fde", "#f28c28", "#1f9d55", "#7b2fd1"
SHOP_COLOR = {"mm": "#e3000f", "saturn": "#f08300"}
AVATAR_COLORS = ["#2b6fde", "#e0457b", "#1f9d55", "#f28c28", "#7b2fd1", "#0aa5a5"]
HERO = {"open": "#1f9d55", "soon": "#e0761c", "none": "#3b4fc4", "err": "#a8650b"}

LIGHT = dict(bg="#eef4fd", card="#ffffff", text="#16213f", muted="#5d6b88", field="#f4f7fc", border="#d6e0f0",
             sel="#dbe7fb", head="#f4f7fc", done="#e4f6ec", win="#fff3c4", lost="#f1f2f5")
DARK = dict(bg="#0f1526", card="#1a2338", text="#eef4fd", muted="#a7b5d1", field="#232e4a", border="#2c3a5c",
            sel="#2b3f6b", head="#232e4a", done="#173a2a", win="#4a3d10", lost="#262d3d")
P = dict(LIGHT)

EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿⬀-⯿️‍⏰-⏿⌚⌛]+ ?")


def plain(s):
    """Emojis entfernen – Windows zeigt sie in Programmfenstern nur als graue Umrisse."""
    return EMOJI.sub("", s or "").strip()


def mix(c1, c2, t):
    a = [int(c1[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(c2[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02x}" for x, y in zip(a, b))


CFG_REF = {}


def notify(title, body, alarm=False, url=None, ping=False, discord=True):
    cfg = CFG_REF.get("cfg", {})
    if discord and cfg.get("discordOn") and cfg.get("discordUrl"):
        threading.Thread(target=engine.discord_send, args=(cfg["discordUrl"], title, body, url, ping and cfg.get("discordPing", False)), daemon=True).start()
    if Notification is None:
        return
    try:
        n = Notification(app_id=APP_NAME, title=title, msg=body[:250], icon=ICON, duration="long" if alarm else "short")
        n.set_audio(audio.LoopingAlarm if alarm else audio.Default, loop=bool(alarm))
        if url:
            n.add_actions(label="Öffnen", launch=url)
        n.show()
    except Exception:  # noqa: BLE001
        pass


def windows_dark():
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize")
        v, _ = winreg.QueryValueEx(k, "AppsUseLightTheme")
        winreg.CloseKey(k)
        return v == 0
    except Exception:  # noqa: BLE001
        return False


def bold_font(size):
    for name in ("segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except Exception:  # noqa: BLE001
            continue
    return ImageFont.load_default()


class Worker(threading.Thread):
    """Eigener Thread für den Browser (Playwright darf nur aus einem Thread benutzt werden)."""

    def __init__(self, app):
        super().__init__(daemon=True)
        self.app = app
        self.jobs = queue.Queue()
        self.eng = None

    def submit(self, name, *args):
        self.jobs.put((name, args))

    def run(self):
        self.eng = engine.Engine(log=self.app.log)
        while True:
            name, args = self.jobs.get()
            if name == "quit":
                self.eng.close()
                return
            try:
                getattr(self, "job_" + name)(*args)
            except Exception as e:  # noqa: BLE001
                self.app.log(f"Fehler ({name}): {e}")
            finally:
                if name in ("check", "queue", "diagnose", "loginstate"):
                    self.app.busy(False)

    def job_check(self, manual):
        snap = self.app.snapshot()
        self.eng.check_all(snap, notify, manual)
        self.app.post(self.app.merge_check, snap)

    def job_open(self, pid, url, product):
        self.eng.open_visible(self.app.snapshot(), pid, url, product)

    def job_reset(self, pid):
        self.eng.reset(pid)

    def job_loginstate(self, pid):
        cfg = self.app.snapshot()
        res = {s["key"]: self.eng.login_state(cfg, pid, s) for s in SHOPS}
        self.app.post(self.app.show_loginstate, res)

    def job_diagnose(self):
        self.app.post(self.app.show_text, "Diagnose", self.eng.diagnose())

    def job_queue(self, items):
        cfg = self.app.snapshot()

        def progress(n, total, label):
            self.app.post(self.app.set_progress, n, total, label)

        def applied(pid, it):
            self.app.post(self.app.mark_applied, pid, it["aid"], True, it.get("name", ""), it.get("shop", ""))

        sent, total, report = self.eng.run_queue(cfg, items, progress, applied)
        if report:
            self.app.post(self.app.show_text, "Probelauf-Bericht", report)
        else:
            self.app.post(lambda: messagebox.showinfo(APP_NAME, f"Fertig: {sent} von {total} Bewerbungen abgeschickt."))
            self.app.log(f"Auto-Modus fertig: {sent} von {total} abgeschickt")


class App:
    def __init__(self):
        self.cfg = load_config()
        CFG_REF["cfg"] = self.cfg
        self.cfg_lock = threading.Lock()
        self.tray = None
        self.ui = queue.Queue()
        self.sel = set()
        self._busy = 0
        self.next_check = 0
        self.update_url = None
        self.loglines = []
        self.imgs = {}
        self.fetching = set()
        self.hero_target, self.hero_label = 0, ""
        self.root = tk.Tk()
        self.root.title(f"{APP_NAME}" + (f" – Version 1.{BUILD}" if BUILD else ""))
        self.root.geometry("1080x900")
        self.root.minsize(920, 680)
        try:
            self.root.iconbitmap(ICON)
        except Exception:  # noqa: BLE001
            pass
        self.main = None
        self.rebuild()
        self.worker = Worker(self)
        self.worker.start()
        self.root.after(200, self.pump)
        self.root.after(1500, lambda: self.check(manual=True))
        self.root.after(1000, self.tick)
        self.root.after(4000, lambda: threading.Thread(target=self.check_update, daemon=True).start())
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.start_tray()

    # ---------- Hilfen ----------
    def post(self, fn, *a):
        self.ui.put((fn, a))

    def pump(self):
        try:
            while True:
                fn, a = self.ui.get_nowait()
                fn(*a)
        except queue.Empty:
            pass
        self.root.after(200, self.pump)

    def log(self, msg):
        self.post(self._log, f"{datetime.now():%H:%M:%S}  {msg}")

    def _log(self, line):
        self.loglines.append(line)
        del self.loglines[:-300]
        self.logbox.configure(state="normal")
        self.logbox.insert("end", line + "\n")
        self.logbox.see("end")
        self.logbox.configure(state="disabled")

    def busy(self, on):
        self.post(self._set_busy, on)

    def _set_busy(self, on):
        self._busy = max(0, self._busy + (1 if on else -1))
        self.btn_check.configure(text="Läuft …" if self._busy > 0 else "Jetzt prüfen")

    def snapshot(self):
        with self.cfg_lock:
            return copy.deepcopy(self.cfg)

    def merge_check(self, snap):
        """Ergebnis der Prüfung (im Worker auf einer Kopie berechnet) übernehmen."""
        with self.cfg_lock:
            for k in ("last", "state", "history"):
                self.cfg[k] = snap.get(k)
            save_config(self.cfg)
        self.render_all()

    def me(self):
        return person(self.cfg)

    def set_cfg(self, key, val):
        with self.cfg_lock:
            self.cfg[key] = val
            save_config(self.cfg)

    # ---------- Aussehen: Farben, Bilder ----------
    def is_dark(self):
        t = self.cfg.get("theme", "auto")
        return t == "dark" or (t == "auto" and windows_dark())

    def rebuild(self):
        """Ganze Oberfläche (neu) aufbauen – z. B. nach Wechsel hell/dunkel."""
        P.clear()
        P.update(DARK if self.is_dark() else LIGHT)
        if self.main is not None:
            self.main.destroy()
        self.root.configure(bg=P["bg"])
        self.style()
        self.imgs = {}
        self.main = tk.Frame(self.root, bg=P["bg"])
        self.main.pack(fill="both", expand=True)
        self.build()
        self.render_all()
        self.logbox.configure(state="normal")
        self.logbox.insert("end", "".join(line + "\n" for line in self.loglines))
        self.logbox.configure(state="disabled")
        self.dark_titlebar()

    def dark_titlebar(self):
        try:
            import ctypes
            self.root.update_idletasks()
            hwnd = ctypes.windll.user32.GetParent(self.root.winfo_id())
            val = ctypes.c_int(1 if self.is_dark() else 0)
            ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(val), ctypes.sizeof(val))
        except Exception:  # noqa: BLE001
            pass

    def style(self):
        st = ttk.Style()
        try:
            st.theme_use("clam")
        except tk.TclError:
            pass
        bg, card, text, muted, field, border = P["bg"], P["card"], P["text"], P["muted"], P["field"], P["border"]
        st.configure(".", font=(FONT, 10), background=bg, foreground=text, bordercolor=border, lightcolor=border, darkcolor=border,
                     troughcolor=bg, fieldbackground=field, selectbackground=P["sel"], selectforeground=text, insertcolor=text)
        st.configure("TNotebook", background=bg, borderwidth=0, tabmargins=(12, 6, 12, 0))
        st.configure("TNotebook.Tab", padding=(18, 9), font=(FONT, 10, "bold"), background=bg, foreground=muted, borderwidth=0)
        st.map("TNotebook.Tab", background=[("selected", card)], foreground=[("selected", BLUE)])
        st.configure("TFrame", background=bg)
        st.configure("TLabel", background=bg, foreground=text)
        st.configure("Card.TLabel", background=card, foreground=text)
        st.configure("Muted.TLabel", background=card, foreground=muted, font=(FONT, 9))
        st.configure("H.TLabel", background=card, foreground=text, font=(FONT, 12, "bold"))
        st.configure("TCheckbutton", background=card, foreground=text, indicatorbackground=field, indicatorforeground=BLUE)
        st.map("TCheckbutton", background=[("active", card)], indicatorbackground=[("selected", field)])
        st.configure("TEntry", fieldbackground=field, foreground=text, padding=6)
        st.configure("TCombobox", fieldbackground=field, background=field, foreground=text, arrowcolor=text, padding=4)
        st.map("TCombobox", fieldbackground=[("readonly", field)], foreground=[("readonly", text)], selectbackground=[("readonly", field)],
               selectforeground=[("readonly", text)])
        self.root.option_add("*TCombobox*Listbox.background", field)
        self.root.option_add("*TCombobox*Listbox.foreground", text)
        st.configure("TButton", padding=(12, 7), background=field, foreground=text, borderwidth=0, focusthickness=0)
        st.map("TButton", background=[("active", P["sel"])])
        for name, col in (("Accent", BLUE), ("Fox", FOX), ("Purple", PURPLE)):
            st.configure(f"{name}.TButton", background=col, foreground="white", padding=(14, 8), font=(FONT, 10, "bold"))
            st.map(f"{name}.TButton", background=[("disabled", mix(col, card, 0.55)), ("active", mix(col, "#000000", 0.15))],
                   foreground=[("disabled", "#ffffff")])
        st.configure("Treeview", rowheight=56, font=(FONT, 10), background=card, fieldbackground=card, foreground=text, borderwidth=0)
        st.map("Treeview", background=[("selected", P["sel"])], foreground=[("selected", text)])
        st.configure("Apps.Treeview", rowheight=34)
        st.configure("Treeview.Heading", font=(FONT, 9, "bold"), background=P["head"], foreground=muted, borderwidth=0, padding=6)
        st.map("Treeview.Heading", background=[("active", P["head"])])
        st.configure("Vertical.TScrollbar", background=field, troughcolor=card, arrowcolor=muted, borderwidth=0)

    def logo(self, size):
        key = f"logo{size}"
        if key not in self.imgs:
            self.imgs[key] = None
            if Image is not None:
                try:
                    im = Image.open(ICON)
                    im.size = max(im.info.get("sizes", [(256, 256)]))
                    self.imgs[key] = ImageTk.PhotoImage(im.convert("RGBA").resize((size, size), Image.LANCZOS))
                except Exception:  # noqa: BLE001
                    pass
        return self.imgs[key]

    def avatar(self, p, size=28):
        key = f"av{p['id']}{p['label']}{size}"
        if key in self.imgs or Image is None:
            return self.imgs.get(key)
        idx = next((i for i, x in enumerate(self.cfg["persons"]) if x["id"] == p["id"]), 0)
        s = size * 3
        im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.ellipse((0, 0, s - 1, s - 1), fill=AVATAR_COLORS[idx % len(AVATAR_COLORS)])
        letter = (p["label"].strip()[:1] or "?").upper()
        d.text((s / 2, s / 2), letter, fill="white", font=bold_font(int(s * 0.5)), anchor="mm")
        self.imgs[key] = ImageTk.PhotoImage(im.resize((size, size), Image.LANCZOS))
        return self.imgs[key]

    def thumb(self, p, shop_key):
        """Vorschaubild mit farbigem Shop-Streifen links."""
        key = f"th{p['aid']}"
        if key in self.imgs or Image is None:
            return self.imgs.get(key)
        w, h = 66, 50
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0))  # durchsichtig: Zeilenfarbe scheint durch
        d = ImageDraw.Draw(im)
        d.rectangle((0, 0, 5, h), fill=SHOP_COLOR.get(shop_key, FOX))
        d.rounded_rectangle((12, 2, 58, 48), radius=8, fill="#ffffff", outline=P["border"])
        path = THUMBS / f"{p['aid']}.png"
        if path.exists():
            try:
                pic = Image.open(path).convert("RGBA")
                pic.thumbnail((40, 40))
                im.paste(pic, (12 + (46 - pic.width) // 2, 2 + (46 - pic.height) // 2), pic)
            except Exception:  # noqa: BLE001
                pass
        else:
            d.rectangle((26, 15, 44, 33), outline="#c3cbd9", width=2)
            d.line((26, 21, 44, 21), fill="#c3cbd9", width=2)
            if p.get("img") and p["aid"] not in self.fetching:
                self.fetching.add(p["aid"])
                threading.Thread(target=self.fetch_thumb, args=(p["aid"], p["img"]), daemon=True).start()
        self.imgs[key] = ImageTk.PhotoImage(im)
        return self.imgs[key]

    def fetch_thumb(self, aid, url):
        """Produktbild einmal herunterladen und zwischenspeichern."""
        try:
            THUMBS.mkdir(parents=True, exist_ok=True)
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) FoxTestpilot"})
            data = urllib.request.urlopen(req, timeout=15).read()
            pic = Image.open(io.BytesIO(data)).convert("RGBA")
            pic.thumbnail((120, 120))
            pic.save(THUMBS / f"{aid}.png")
            self.post(self._thumb_ready, aid)
        except Exception:  # noqa: BLE001
            pass

    def _thumb_ready(self, aid):
        self.imgs.pop(f"th{aid}", None)
        self.render_home()

    def card(self, parent, title=None, **pack):
        f = tk.Frame(parent, bg=P["card"], highlightthickness=1, highlightbackground=P["border"], padx=16, pady=14)
        f.pack(fill=pack.pop("fill", "x"), expand=pack.pop("expand", False), padx=12, pady=(10, 0), **pack)
        if title:
            ttk.Label(f, text=title, style="H.TLabel").pack(anchor="w", pady=(0, 8))
        return f

    def row(self, parent, **pack):
        r = tk.Frame(parent, bg=P["card"])
        r.pack(fill="x", **pack)
        return r

    def text_widget(self, parent, height, mono=False):
        return tk.Text(parent, height=height, wrap="word", font=("Consolas", 9) if mono else (FONT, 10), relief="flat", bg=P["field"], fg=P["text"],
                       insertbackground=P["text"], highlightthickness=1, highlightbackground=P["border"], highlightcolor=BLUE, padx=8, pady=6)

    def scrollable(self, parent):
        """Bereich mit Bildlaufleiste (für lange Seiten)."""
        canvas = tk.Canvas(parent, bg=P["bg"], highlightthickness=0)
        sb = ttk.Scrollbar(parent, orient="vertical", command=canvas.yview)
        inner = ttk.Frame(canvas)
        win = canvas.create_window((0, 0), window=inner, anchor="nw")
        inner.bind("<Configure>", lambda _e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(win, width=e.width))
        canvas.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        wheel = lambda e: canvas.yview_scroll(int(-e.delta / 120) or (-1 if e.delta > 0 else 1), "units")  # noqa: E731
        canvas.bind("<Enter>", lambda _e: canvas.bind_all("<MouseWheel>", wheel))
        canvas.bind("<Leave>", lambda _e: canvas.unbind_all("<MouseWheel>"))
        return inner

    @staticmethod
    def two_columns(parent, left_weight=3, right_weight=2, right_min=400):
        cols = ttk.Frame(parent)
        cols.pack(fill="both", expand=True)
        same = left_weight == right_weight
        cols.columnconfigure(0, weight=left_weight, uniform="c" if same else "")
        cols.columnconfigure(1, weight=right_weight, minsize=right_min, uniform="c" if same else "")
        left, right = ttk.Frame(cols), ttk.Frame(cols)
        left.grid(row=0, column=0, sticky="nsew")
        right.grid(row=0, column=1, sticky="nsew")
        return left, right

    # ---------- Oberfläche ----------
    def build(self):
        top = tk.Frame(self.main, bg=P["bg"])
        top.pack(fill="x", padx=16, pady=(12, 0))
        lg = self.logo(40)
        if lg:
            tk.Label(top, image=lg, bg=P["bg"]).pack(side="left")
        tk.Label(top, text="Fox Testpilot", font=(FONT, 18, "bold"), bg=P["bg"], fg=P["text"]).pack(side="left", padx=(8, 20))
        self.chips = tk.Frame(top, bg=P["bg"])
        self.chips.pack(side="left")
        self.btn_check = ttk.Button(top, text="Läuft …" if self._busy else "Jetzt prüfen", style="Accent.TButton", command=lambda: self.check(True))
        self.btn_check.pack(side="right")
        self.upd = ttk.Button(top, text="Update", style="Fox.TButton", command=self.install_update)
        if self.update_url:
            self.upd.configure(text="Update installieren")
            self.upd.pack(side="right", padx=8)

        self.nb = ttk.Notebook(self.main)
        self.nb.pack(fill="both", expand=True, padx=4, pady=(10, 8))
        self.tab_home, self.tab_apps, self.tab_people, self.tab_set, self.tab_log = (ttk.Frame(self.nb) for _ in range(5))
        for tab, name in ((self.tab_home, "Start"), (self.tab_apps, "Bewerbungen"), (self.tab_people, "Personen"),
                          (self.tab_set, "Einstellungen"), (self.tab_log, "Verlauf")):
            self.nb.add(tab, text=name)
        self.build_home()
        self.build_apps()
        self.build_people()
        self.build_settings()
        self.build_log()

    def build_home(self):
        t = self.tab_home
        col = HERO["none"]
        self.hero = tk.Frame(t, bg=col, padx=24, pady=14)
        self.hero.pack(fill="x", padx=12, pady=(10, 0))
        self.h_last = tk.Label(self.hero, text="Noch nicht geprüft", bg=col, fg="#e8ecff", font=(FONT, 9))
        self.h_last.pack(anchor="w")
        self.h_title = tk.Label(self.hero, text="Testpiloten", bg=col, fg="white", font=(FONT, 21, "bold"))
        self.h_title.pack(anchor="w")
        self.h_sub = tk.Label(self.hero, text="", bg=col, fg="white", font=(FONT, 11))
        self.h_sub.pack(anchor="w")
        self.tiles = tk.Frame(self.hero, bg=col)
        self.h_tlabel = tk.Label(self.tiles, text="", bg=col, fg="white", font=(FONT, 9, "bold"))
        self.h_tlabel.pack(anchor="w", pady=(8, 4))
        self.tile_row = tk.Frame(self.tiles, bg=col)
        self.tile_row.pack(anchor="w")
        self.tile_boxes = []
        for unit in ("Tage", "Std.", "Min.", "Sek."):
            box = tk.Frame(self.tile_row, bg=col, width=84, height=64)
            box.pack(side="left", padx=(0, 8))
            box.pack_propagate(False)
            v = tk.Label(box, text="0", bg=col, fg="white", font=(FONT, 21, "bold"))
            v.pack(pady=(4, 0))
            u = tk.Label(box, text=unit, bg=col, fg="#f4f4ff", font=(FONT, 9))
            u.pack()
            self.tile_boxes.append((box, v, u))

        c = self.card(t, None, fill="both", expand=True)
        head = self.row(c)
        ttk.Label(head, text="Testprodukte", style="H.TLabel").pack(side="left")
        for name, sc in (("Saturn", SHOP_COLOR["saturn"]), ("MediaMarkt", SHOP_COLOR["mm"])):
            tk.Label(head, text=name, fg=P["muted"], bg=P["card"], font=(FONT, 9)).pack(side="right", padx=(0, 12))
            tk.Frame(head, bg=sc, width=6, height=14).pack(side="right", padx=(0, 4))
        self.shoplines = self.row(c, pady=(4, 8))
        self.customlines = tk.Frame(c, bg=P["card"])
        self.customlines.pack(side="bottom", fill="x", pady=(8, 0))
        r2 = tk.Frame(c, bg=P["card"])
        r2.pack(side="bottom", fill="x", pady=(8, 0))
        r = tk.Frame(c, bg=P["card"])
        r.pack(side="bottom", fill="x", pady=(10, 0))
        tf = tk.Frame(c, bg=P["card"], highlightthickness=1, highlightbackground=P["border"])
        tf.pack(fill="both", expand=True)
        cols = ("auto", "name", "status", "who")
        self.tree = ttk.Treeview(tf, columns=cols, show="tree headings", height=3, selectmode="browse")
        self.tree.heading("#0", text="")
        self.tree.column("#0", width=80, stretch=False)
        for col_, txt, w, anchor in (("auto", "AUTO", 64, "center"), ("name", "PRODUKT", 430, "w"), ("status", "STATUS", 140, "w"), ("who", "BEWORBEN", 170, "w")):
            self.tree.heading(col_, text=txt, anchor=anchor)
            self.tree.column(col_, width=w, anchor=anchor, stretch=(col_ == "name"))
        self.tree.tag_configure("done", background=P["done"])
        self.tree.tag_configure("win", background=P["win"])
        self.tree.tag_configure("lost", background=P["lost"], foreground=P["muted"])
        self.tree.tag_configure("new", foreground=FOX)
        sb = ttk.Scrollbar(tf, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.tree.pack(fill="both", expand=True)
        self.tree.bind("<Button-1>", self.tree_click)
        self.tree.bind("<Double-1>", lambda _e: self.apply_selected())
        ttk.Button(r, text="Bewerben mit Ausfüll-Hilfe", style="Accent.TButton", command=self.apply_selected).pack(side="left")
        ttk.Button(r, text="Beworben an/aus", command=self.toggle_applied).pack(side="left", padx=6)
        ttk.Button(r, text="Seite öffnen", command=self.open_selected_page).pack(side="left")
        self.var_probe = tk.BooleanVar(value=self.cfg.get("probe", True))
        ttk.Checkbutton(r, text="Probelauf (nichts abschicken)", variable=self.var_probe, command=self.save_probe).pack(side="right")
        self.btn_auto = ttk.Button(r2, text="Auto", style="Fox.TButton", command=lambda: self.start_queue(False))
        self.btn_auto.pack(side="left")
        self.btn_all = ttk.Button(r2, text="Alle Personen", style="Purple.TButton", command=lambda: self.start_queue(True))
        self.btn_all.pack(side="left", padx=6)
        self.lbl_progress = ttk.Label(r2, text="Tipp: In der Spalte „Auto“ Produkte anklicken, dann einen der farbigen Knöpfe.", style="Muted.TLabel")
        self.lbl_progress.pack(side="left", padx=8)

    def build_apps(self):
        c = self.card(self.tab_apps, "Meine Bewerbungen", fill="both", expand=True)
        ttk.Label(c, text="Trag ein, ob du ausgewählt wurdest. Bei „Ausgewählt“ die Frist für die Bewertung eintragen – das Programm erinnert 3 Tage vorher und am letzten Tag.",
                  style="Muted.TLabel", wraplength=900).pack(anchor="w", pady=(0, 8))
        tf = tk.Frame(c, bg=P["card"], highlightthickness=1, highlightbackground=P["border"])
        tf.pack(fill="both", expand=True)
        cols = ("name", "shop", "date", "result", "due")
        self.atree = ttk.Treeview(tf, columns=cols, show="headings", height=10, selectmode="browse", style="Apps.Treeview")
        for col, txt, w in (("name", "PRODUKT", 420), ("shop", "SHOP", 110), ("date", "BEWORBEN AM", 110), ("result", "ERGEBNIS", 150), ("due", "BEWERTUNG BIS", 120)):
            self.atree.heading(col, text=txt, anchor="w")
            self.atree.column(col, width=w, stretch=(col == "name"))
        self.atree.tag_configure("win", background=P["win"])
        self.atree.tag_configure("lost", background=P["lost"], foreground=P["muted"])
        self.atree.pack(fill="both", expand=True)
        self.atree.bind("<<TreeviewSelect>>", lambda _e: self.app_selected())
        r = self.row(c, pady=(10, 0))
        self.var_result = tk.StringVar()
        ttk.Combobox(r, textvariable=self.var_result, state="readonly", width=18, values=["Noch offen", "Ausgewählt", "Nicht ausgewählt"]).pack(side="left")
        ttk.Label(r, text="   Bewertung bis (TT.MM.JJJJ):", style="Card.TLabel").pack(side="left")
        self.var_due = tk.StringVar()
        ttk.Entry(r, textvariable=self.var_due, width=12).pack(side="left", padx=6)
        ttk.Button(r, text="Übernehmen", style="Accent.TButton", command=self.save_result).pack(side="left")
        ttk.Button(r, text="Eintrag löschen", command=self.delete_app).pack(side="right")

    def build_people(self):
        outer = self.scrollable(self.tab_people)
        c = self.card(outer)
        ttk.Label(c, text="Jede Person im Haushalt hat eigene Daten, eigene Konten und ein eigenes Browser-Profil. Oben neben dem Logo wechselst du die Person.",
                  style="Muted.TLabel", wraplength=900).pack(anchor="w")
        br = self.row(c, pady=(10, 0))
        ttk.Button(br, text="+ Neue Person", style="Accent.TButton", command=self.add_person).pack(side="left")
        ttk.Button(br, text="Umbenennen", command=self.rename_person).pack(side="left", padx=6)
        ttk.Button(br, text="Person löschen", command=self.delete_person).pack(side="left")

        lcol, rcol = self.two_columns(outer, 3, 2, 400)
        left = self.card(lcol, "Daten zum Ausfüllen", fill="both", expand=True)
        self.pv = {}
        grid = tk.Frame(left, bg=P["card"])
        grid.pack(fill="x")
        grid.columnconfigure(1, weight=1)
        fields = [("salutation", "Anrede (Herr/Frau)"), ("first", "Vorname"), ("last", "Nachname"), ("street", "Straße"), ("nr", "Hausnummer"),
                  ("zip", "PLZ"), ("city", "Ort"), ("email", "E-Mail (falls kein Konto)"), ("phone", "Telefon"), ("birth", "Geburtsdatum TT.MM.JJJJ")]
        for i, (k, label) in enumerate(fields):
            ttk.Label(grid, text=label, style="Card.TLabel").grid(row=i, column=0, sticky="w", pady=3)
            v = tk.StringVar()
            self.pv[k] = v
            ttk.Entry(grid, textvariable=v, width=24).grid(row=i, column=1, sticky="we", pady=3, padx=(10, 0))
        ttk.Label(left, text="Bewerbungstext  ({produkt} = Produktname)", style="Card.TLabel").pack(anchor="w", pady=(10, 4))
        self.txt_main = self.text_widget(left, 4)
        self.txt_main.pack(fill="x")
        ttk.Label(left, text="Eigene Texte je Stichwort (z. B. „tv, fernseher“)", style="Card.TLabel").pack(anchor="w", pady=(10, 2))
        self.kw = []
        for _ in range(3):
            v = tk.StringVar()
            ttk.Entry(left, textvariable=v).pack(fill="x", pady=(4, 0))
            tx = self.text_widget(left, 2)
            tx.pack(fill="x", pady=(4, 4))
            self.kw.append((v, tx))
        ttk.Button(left, text="Daten speichern", style="Accent.TButton", command=self.save_profile).pack(anchor="w", pady=(8, 0))

        right = self.card(rcol, "Konten", fill="both", expand=True)
        ttk.Label(right, text="Das Programm meldet die Person damit automatisch an. Passwörter werden mit Windows verschlüsselt und nur für deinen Windows-Benutzer gespeichert.",
                  style="Muted.TLabel", wraplength=330).pack(anchor="w", pady=(0, 6))
        self.acc = {}
        for s in SHOPS:
            hdr = tk.Frame(right, bg=P["card"])
            hdr.pack(anchor="w", pady=(12, 4))
            tk.Frame(hdr, bg=SHOP_COLOR[s["key"]], width=6, height=18).pack(side="left", padx=(0, 8))
            ttk.Label(hdr, text=("myMediaMarkt" if s["key"] == "mm" else "mySaturn"), style="H.TLabel").pack(side="left")
            e, pw = tk.StringVar(), tk.StringVar()
            ttk.Label(right, text="E-Mail", style="Muted.TLabel").pack(anchor="w")
            ttk.Entry(right, textvariable=e).pack(fill="x")
            lbl = ttk.Label(right, text="Passwort", style="Muted.TLabel")
            lbl.pack(anchor="w", pady=(4, 0))
            ttk.Entry(right, textvariable=pw, show="•").pack(fill="x")
            r = self.row(right, pady=(6, 0))
            ttk.Button(r, text="Speichern", style="Accent.TButton", command=lambda k=s["key"]: self.save_account(k)).pack(side="left")
            ttk.Button(r, text="Anmelden", command=lambda u=s["account"]: self.open_browser(u)).pack(side="left", padx=6)
            st = ttk.Label(right, text="", style="Card.TLabel")
            st.pack(anchor="w")
            self.acc[s["key"]] = (e, pw, lbl, st)
        ttk.Button(right, text="Anmelde-Status prüfen", command=self.check_login).pack(anchor="w", pady=(12, 0))

    def build_settings(self):
        outer = self.scrollable(self.tab_set)
        left, right = self.two_columns(outer, 1, 1, 0)

        c = self.card(left, "Meldungen")
        r = self.row(c)
        ttk.Label(r, text="Prüfen alle", style="Card.TLabel").pack(side="left")
        self.var_interval = tk.StringVar(value=next((k for k, v in INTERVALS.items() if v == self.cfg.get("interval", 30)), "30 Minuten"))
        cb = ttk.Combobox(r, textvariable=self.var_interval, values=list(INTERVALS), state="readonly", width=12)
        cb.pack(side="left", padx=8)
        cb.bind("<<ComboboxSelected>>", lambda _e: self.set_cfg("interval", INTERVALS[self.var_interval.get()]))
        self.var_alarm = tk.BooleanVar(value=self.cfg.get("alarm", False))
        ttk.Checkbutton(c, text="Lauter Alarm, wenn die Bewerbung startet", variable=self.var_alarm,
                        command=lambda: self.set_cfg("alarm", self.var_alarm.get())).pack(anchor="w", pady=(8, 0))
        self.var_auto = tk.BooleanVar(value=self.cfg.get("autostart", False))
        ttk.Checkbutton(c, text="Mit Windows starten (läuft dann unten rechts neben der Uhr)", variable=self.var_auto, command=self.set_autostart).pack(anchor="w", pady=(2, 0))
        ttk.Button(c, text="Test-Benachrichtigung", command=lambda: notify("🦊 Fox Testpilot", "Benachrichtigungen funktionieren!", discord=False)).pack(anchor="w", pady=(10, 0))

        c = self.card(left, "Aussehen")
        r = self.row(c)
        ttk.Label(r, text="Hell oder dunkel", style="Card.TLabel").pack(side="left")
        self.var_theme = tk.StringVar(value=next((k for k, v in THEMES.items() if v == self.cfg.get("theme", "auto")), "Automatisch"))
        cb = ttk.Combobox(r, textvariable=self.var_theme, values=list(THEMES), state="readonly", width=14)
        cb.pack(side="left", padx=8)
        cb.bind("<<ComboboxSelected>>", lambda _e: self.set_theme(THEMES[self.var_theme.get()]))
        ttk.Label(c, text="„Automatisch“ folgt der Windows-Einstellung.", style="Muted.TLabel").pack(anchor="w", pady=(6, 0))

        c = self.card(left, "Discord")
        ttk.Label(c, text="Webhook-Adresse (Discord: Kanal bearbeiten → Integrationen → Webhooks → Neuer Webhook → URL kopieren):",
                  style="Muted.TLabel", wraplength=420).pack(anchor="w")
        self.var_dc = tk.StringVar()
        ttk.Entry(c, textvariable=self.var_dc, show="•").pack(fill="x", pady=(6, 0))
        r = self.row(c, pady=(8, 0))
        ttk.Button(r, text="Speichern", style="Accent.TButton", command=self.save_discord).pack(side="left")
        ttk.Button(r, text="Test senden", command=self.test_discord).pack(side="left", padx=6)
        self.lbl_dc = ttk.Label(r, text="", style="Card.TLabel")
        self.lbl_dc.pack(side="left")
        self.var_dcon = tk.BooleanVar(value=self.cfg.get("discordOn", False))
        ttk.Checkbutton(c, text="Meldungen an Discord senden", variable=self.var_dcon, command=lambda: self.set_cfg("discordOn", self.var_dcon.get())).pack(anchor="w", pady=(8, 0))
        self.var_dcping = tk.BooleanVar(value=self.cfg.get("discordPing", False))
        ttk.Checkbutton(c, text="Beim Start @everyone erwähnen", variable=self.var_dcping, command=lambda: self.set_cfg("discordPing", self.var_dcping.get())).pack(anchor="w", pady=(2, 0))

        c = self.card(right, "Eigene Seiten")
        ttk.Label(c, text="Weitere Produkttester-Seiten: Meldung, sobald eines der Stichwörter neu auf der Seite steht.", style="Muted.TLabel", wraplength=420).pack(anchor="w")
        self.lst_custom = tk.Listbox(c, height=4, font=(FONT, 10), relief="flat", bg=P["field"], fg=P["text"], selectbackground=P["sel"], selectforeground=P["text"],
                                     highlightthickness=1, highlightbackground=P["border"], activestyle="none")
        self.lst_custom.pack(fill="x", pady=(8, 6))
        g = tk.Frame(c, bg=P["card"])
        g.pack(fill="x")
        g.columnconfigure(1, weight=1)
        self.var_cn, self.var_cu, self.var_ck = tk.StringVar(), tk.StringVar(), tk.StringVar()
        for i, (lbl, v) in enumerate((("Name", self.var_cn), ("Adresse", self.var_cu), ("Stichwörter", self.var_ck))):
            ttk.Label(g, text=lbl, style="Card.TLabel").grid(row=i, column=0, sticky="w")
            ttk.Entry(g, textvariable=v, width=20).grid(row=i, column=1, sticky="we", padx=(10, 0), pady=2)
        r = self.row(c, pady=(8, 0))
        ttk.Button(r, text="+ Hinzufügen", style="Accent.TButton", command=self.add_custom).pack(side="left")
        ttk.Button(r, text="Markierte entfernen", command=self.remove_custom).pack(side="left", padx=6)

        c = self.card(right, "Sicherung")
        ttk.Label(c, text="Die Sicherung aus der Android-App (Einstellungen → Sicherung) kannst du hier einspielen – Personen, Daten, Texte und Häkchen. Passwörter musst du neu eintragen.",
                  style="Muted.TLabel", wraplength=420).pack(anchor="w")
        r = self.row(c, pady=(8, 0))
        ttk.Button(r, text="Sicherung einspielen", command=self.import_backup).pack(side="left")
        ttk.Button(r, text="Sicherung kopieren", command=self.export_backup).pack(side="left", padx=6)

        c = self.card(right, "Diagnose")
        ttk.Label(c, text="Zeigt, was das Programm auf den Testpiloten-Seiten liest. Bei Problemen den Bericht kopieren und an Claude schicken.",
                  style="Muted.TLabel", wraplength=420).pack(anchor="w")
        ttk.Button(c, text="Seiten testen", command=self.diagnose).pack(anchor="w", pady=(8, 0))

    def build_log(self):
        c = self.card(self.tab_log, "Verlauf", fill="both", expand=True)
        self.histbox = self.text_widget(c, 12)
        self.histbox.pack(fill="both", expand=True)
        ttk.Label(c, text="Technisches Protokoll", style="H.TLabel").pack(anchor="w", pady=(12, 6))
        self.logbox = self.text_widget(c, 8, mono=True)
        self.logbox.configure(state="disabled")
        self.logbox.pack(fill="both", expand=True)

    def set_theme(self, t):
        self.set_cfg("theme", t)
        tab = self.nb.index(self.nb.select())
        self.rebuild()
        self.nb.select(tab)

    # ---------- Anzeigen ----------
    def render_all(self):
        self.render_people()
        self.render_home()
        self.render_apps()
        self.render_profile()
        self.render_custom()
        self.render_history()

    def render_people(self):
        for w in self.chips.winfo_children():
            w.destroy()
        for p in self.cfg["persons"]:
            on = p["id"] == self.cfg["active"]
            chip = tk.Label(self.chips, image=self.avatar(p), text="  " + p["label"], compound="left", font=(FONT, 10, "bold" if on else "normal"),
                            bg=P["card"] if on else P["bg"], fg=P["text"], padx=8, pady=4, cursor="hand2",
                            highlightthickness=2, highlightbackground=BLUE if on else P["border"])
            chip.pack(side="left", padx=(0, 6))
            chip.bind("<Button-1>", lambda _e, pid=p["id"]: self.select_person(pid))
        if len(self.cfg["persons"]) < 6:
            add = tk.Label(self.chips, text="+", font=(FONT, 14, "bold"), bg=P["bg"], fg=P["muted"], padx=12, cursor="hand2",
                           highlightthickness=2, highlightbackground=P["border"])
            add.pack(side="left")
            add.bind("<Button-1>", lambda _e: self.add_person())

    def status_of(self, aid):
        me = self.me()
        r = (me.get("info", {}).get(aid) or {}).get("result")
        if r == "yes":
            return "Ausgewählt", "win"
        if r == "no":
            return "Nicht ausgewählt", "lost"
        return ("✓ Beworben", "done") if aid in me.get("applied", {}) else ("Offen", "")

    def shops(self):
        return (self.cfg.get("last") or {}).get("shops", [])

    def render_home(self):
        last = self.cfg.get("last") or {}
        lst = self.shops()
        for w in self.shoplines.winfo_children():
            w.destroy()
        for s in lst:
            r = self.row(self.shoplines, pady=1)
            tk.Frame(r, bg=SHOP_COLOR.get(s.get("key"), P["muted"]), width=6, height=14).pack(side="left", padx=(0, 6))
            tk.Label(r, text=s["name"], fg=P["text"], bg=P["card"], font=(FONT, 10, "bold")).pack(side="left", padx=(0, 8))
            tk.Label(r, text=plain(shop_line(s)), fg=P["muted"], bg=P["card"], font=(FONT, 10)).pack(side="left")
        if not lst:
            tk.Label(self.shoplines, text="Noch nicht geprüft", fg=P["muted"], bg=P["card"]).pack(anchor="w")
        sel = self.tree.selection()
        self.tree.delete(*self.tree.get_children())
        for s in lst:
            for p in s.get("products", []):
                who = ", ".join(x["label"] for x in self.cfg["persons"] if p["aid"] in x.get("applied", {}))
                st, tag = self.status_of(p["aid"])
                tags = tuple(t for t in (tag, "new" if p.get("isNew") else "") if t)
                self.tree.insert("", "end", iid=p["aid"], image=self.thumb(p, s["key"]) or "",
                                 values=("☑" if p["aid"] in self.sel else "☐", p["name"] + ("   · NEU" if p.get("isNew") else ""), st, who), tags=tags)
        if sel and self.tree.exists(sel[0]):
            self.tree.selection_set(sel[0])
        for w in self.customlines.winfo_children():
            w.destroy()
        res = {x["id"]: x for x in last.get("custom", [])}
        for p in self.cfg.get("custom", []):
            r = res.get(p["id"], {})
            f = r.get("found") or []
            line = ("Fehler: " + r["error"]) if r.get("error") else "noch nicht geprüft" if not r else ("gefunden: „" + "“, „".join(f) + "“") if f else "keines der Stichwörter auf der Seite"
            row = self.row(self.customlines, pady=1)
            tk.Frame(row, bg=GREEN if f else P["border"], width=6, height=14).pack(side="left", padx=(0, 6))
            tk.Label(row, text=p["name"], fg=P["text"], bg=P["card"], font=(FONT, 10, "bold")).pack(side="left", padx=(0, 8))
            tk.Label(row, text=line, fg=P["muted"], bg=P["card"]).pack(side="left")
        self.render_auto()
        self.render_hero()

    def render_hero(self):
        lst = self.shops()
        last = self.cfg.get("last") or {}
        now = time.time() * 1000
        kind, title, sub, target, lab = "none", "Testpiloten", "Klicke auf „Jetzt prüfen“", 0, ""
        if lst:
            opened = sorted([s for s in lst if s.get("status") == "open"], key=lambda s: s.get("endYmd", 0))
            soon = sorted([s for s in lst if s.get("status") == "soon"], key=lambda s: (s.get("startAt") or 9e15, s.get("startYmd") or 99999999))
            if opened:
                s = opened[0]
                kind, title = "open", "Bewerbung ist offen!"
                sub = " & ".join(x["name"] for x in opened) + ("" if s.get("endUnknown") else f"  ·  {s.get('start', '')[:6]} – {s.get('end', '')}")
                if s.get("endAt", 0) > now:
                    target, lab = s["endAt"], "ENDET IN"
            elif soon:
                s = soon[0]
                kind, title = "soon", "Nächste Runde kommt"
                if s.get("startAt", 0) > now:
                    names = " & ".join(x["name"] for x in soon if x.get("startAt") == s["startAt"])
                    sub, target, lab = f"{names}  ·  Start {plain(engine.fmt_when(s['startAt']))}", s["startAt"], "STARTET IN"
                elif s.get("startYmd"):
                    sub = f"{s['name']}: Bewerbung vom {s['start'][:6]} bis {s['end']}"
                else:
                    sub = " & ".join(x["name"] for x in soon) + ": Termin noch nicht bekannt – ich passe auf"
            else:
                title, sub = "Gerade keine Testaktion", "Ich melde mich, sobald es bei MediaMarkt oder Saturn eine neue Runde gibt."
            if all(s.get("error") for s in lst):
                kind, sub, target = "err", lst[0]["error"], 0
        self.hero_target, self.hero_label = target, lab
        col = HERO[kind]
        tile = mix(col, "#ffffff", 0.2)
        for w in (self.hero, self.h_last, self.h_title, self.h_sub, self.tiles, self.h_tlabel, self.tile_row):
            w.configure(bg=col)
        for box, v, u in self.tile_boxes:
            for w in (box, v, u):
                w.configure(bg=tile)
        t = last.get("time")
        self.h_last.configure(text=("Geprüft " + datetime.fromtimestamp(t / 1000).strftime("%d.%m. %H:%M")) if t else "Noch nicht geprüft")
        self.h_title.configure(text=title)
        self.h_sub.configure(text=sub)
        self.h_tlabel.configure(text=lab)
        self.update_countdown()

    def update_countdown(self):
        s = int((self.hero_target - time.time() * 1000) / 1000) if self.hero_target else 0
        if s <= 0:
            self.tiles.pack_forget()
            return
        if not self.tiles.winfo_manager():
            self.tiles.pack(anchor="w")
        for i, (_box, v, _u) in enumerate(self.tile_boxes):
            val = (s // 86400, s % 86400 // 3600, s % 3600 // 60, s % 60)[i]
            v.configure(text=str(val) if i == 0 else f"{val:02d}")

    def render_auto(self):
        n = self.count(self.build_queue(False))
        multi = len(self.cfg["persons"]) > 1
        a = self.count(self.build_queue(True)) if multi else 0
        probe = self.var_probe.get()
        self.btn_auto.configure(text=("Probelauf" if probe else "Automatisch bewerben") + f": {n}" + (f"  ({self.me()['label']})" if multi else ""),
                                state="normal" if n else "disabled")
        if multi:
            self.btn_all.configure(text=("Probelauf" if probe else "Auto") + f" – alle Personen: {a}", state="normal" if a else "disabled")
            self.btn_all.pack(side="left", padx=6, after=self.btn_auto)
        else:
            self.btn_all.pack_forget()

    def render_apps(self):
        me = self.me()
        self.atree.delete(*self.atree.get_children())
        ids = list(dict.fromkeys(list(me.get("info", {})) + list(me.get("applied", {}))))
        names = {p["aid"]: p["name"] for s in self.shops() for p in s.get("products", [])}
        for aid in reversed(ids):
            o = me.get("info", {}).get(aid) or {}
            res = {"yes": "Ausgewählt", "no": "Nicht ausgewählt"}.get(o.get("result"), "Noch offen")
            due = o.get("due", "")
            due = f"{due[8:10]}.{due[5:7]}.{due[:4]}" if len(due) == 10 else ""
            tag = {"yes": ("win",), "no": ("lost",)}.get(o.get("result"), ())
            self.atree.insert("", "end", iid=aid, tags=tag, values=(o.get("name") or names.get(aid) or f"Produkt {aid}", o.get("shop", ""),
                                                                   o.get("date") or me.get("applied", {}).get(aid, ""), res, due))

    def render_profile(self):
        me = self.me()
        prof = me.get("profile") or {}
        for k, v in self.pv.items():
            v.set(prof.get(k, ""))
        self.txt_main.delete("1.0", "end")
        self.txt_main.insert("1.0", prof.get("text") or engine.DEFAULT_TEXT)
        for i, (v, tx) in enumerate(self.kw, start=1):
            v.set(prof.get(f"k{i}", ""))
            tx.delete("1.0", "end")
            tx.insert("1.0", prof.get(f"t{i}", ""))
        for k, (e, pw, lbl, st) in self.acc.items():
            a = me.get("accounts", {}).get(k) or {}
            e.set(a.get("email", ""))
            pw.set("")
            lbl.configure(text="Passwort (gespeichert – leer lassen zum Behalten)" if a.get("pw") else "Passwort")
            st.configure(text="")
        self.var_dc.set("")
        self.lbl_dc.configure(text="✓ gespeichert" if engine.DISCORD_VALID.match(self.cfg.get("discordUrl", "")) else "")

    def render_custom(self):
        self.lst_custom.delete(0, "end")
        for p in self.cfg.get("custom", []):
            self.lst_custom.insert("end", f"  {p['name']}  ·  {p['url']}  ·  Stichwörter: {p['keywords']}")

    def render_history(self):
        self.histbox.configure(state="normal")
        self.histbox.delete("1.0", "end")
        self.histbox.insert("1.0", "\n".join(plain(h) for h in self.cfg.get("history", [])) or "Noch nichts passiert.")
        self.histbox.configure(state="disabled")

    # ---------- Start-Seite: Aktionen ----------
    def product(self, aid):
        for s in self.shops():
            for p in s.get("products", []):
                if p["aid"] == aid:
                    return s, p
        return None, None

    def tree_click(self, ev):
        if self.tree.identify_column(ev.x) != "#1":
            return
        aid = self.tree.identify_row(ev.y)
        if not aid:
            return
        self.sel.symmetric_difference_update({aid})
        self.tree.set(aid, "auto", "☑" if aid in self.sel else "☐")
        self.render_auto()

    def selected_product(self):
        sel = self.tree.selection()
        if not sel:
            messagebox.showinfo(APP_NAME, "Bitte zuerst ein Produkt in der Liste anklicken.")
            return None, None
        return self.product(sel[0])

    def apply_selected(self):
        s, p = self.selected_product()
        if p:
            self.worker.submit("open", self.cfg["active"], p["url"], p["name"])
            self.log(f"Öffne „{p['name']}“ mit Ausfüll-Hilfe")

    def open_selected_page(self):
        s, p = self.selected_product()
        if p:
            webbrowser.open(p["url"])

    def toggle_applied(self):
        s, p = self.selected_product()
        if p:
            self.mark_applied(self.cfg["active"], p["aid"], p["aid"] not in self.me().get("applied", {}), p["name"], s["name"])

    def mark_applied(self, pid, aid, on, name="", shop=""):
        with self.cfg_lock:
            pr = person(self.cfg, pid)
            ap, info = pr.setdefault("applied", {}), pr.setdefault("info", {})
            if on:
                ap[aid] = f"{date.today():%d.%m.%Y}"
                o = info.setdefault(aid, {})
                if name:
                    o["name"] = name
                if shop:
                    o["shop"] = shop
                o.setdefault("date", ap[aid])
            else:
                ap.pop(aid, None)
                if not (info.get(aid) or {}).get("result"):
                    info.pop(aid, None)
            save_config(self.cfg)
        self.render_home()
        self.render_apps()

    def save_probe(self):
        self.set_cfg("probe", self.var_probe.get())
        self.render_auto()

    def build_queue(self, everyone):
        prods = [(s, p) for s in self.shops() for p in s.get("products", []) if p["aid"] in self.sel]
        multi = len(self.cfg["persons"]) > 1
        items = []
        for per in self.cfg["persons"]:
            if not everyone and per["id"] != self.cfg["active"]:
                continue
            done = per.get("applied", {})
            for shop in SHOPS:
                mine = [(s, p) for s, p in prods if s["key"] == shop["key"] and p["aid"] not in done]
                if not mine:
                    continue
                label = per["label"] if multi else ""
                items.append({"login": True, "url": shop["account"], "name": "Anmeldung " + shop["name"], "person": per["id"], "label": label})
                for s, p in mine:
                    items.append({"url": p["url"], "name": p["name"], "aid": p["aid"], "shop": s["name"], "person": per["id"], "label": label,
                                  "probe": self.var_probe.get()})
        return items

    @staticmethod
    def count(items):
        return sum(1 for x in items if not x.get("login"))

    def start_queue(self, everyone):
        items = self.build_queue(everyone)
        n = self.count(items)
        if not n:
            return
        who = "für alle Personen nacheinander" if everyone else (f"als {self.me()['label']}" if len(self.cfg["persons"]) > 1 else "")
        if self.var_probe.get():
            q = f"Probelauf für {n} Produkt(e) {who}?\n\nDas Programm meldet an und füllt alles aus, schickt aber NICHTS ab. Am Ende siehst du einen Bericht."
        else:
            q = (f"{n} Bewerbung(en) automatisch abschicken {who}?\n\nDas Programm meldet an, füllt das Formular mit den gespeicherten Daten aus, "
                 "akzeptiert die Teilnahmebedingungen und den Datenschutzhinweis im Namen der jeweiligen Person und schickt ab.")
        if not messagebox.askyesno(APP_NAME, q):
            return
        self.busy(True)
        self.worker.submit("queue", items)

    def set_progress(self, n, total, label):
        self.lbl_progress.configure(text=f"{min(n + 1, total)}/{total}   {plain(label)}" if n < total else "✓ Fertig")

    # ---------- Bewerbungen ----------
    def app_selected(self):
        sel = self.atree.selection()
        if not sel:
            return
        o = self.me().get("info", {}).get(sel[0]) or {}
        self.var_result.set({"yes": "Ausgewählt", "no": "Nicht ausgewählt"}.get(o.get("result"), "Noch offen"))
        due = o.get("due", "")
        self.var_due.set(f"{due[8:10]}.{due[5:7]}.{due[:4]}" if len(due) == 10 else "")

    def save_result(self):
        sel = self.atree.selection()
        if not sel:
            return
        res = {"Ausgewählt": "yes", "Nicht ausgewählt": "no"}.get(self.var_result.get(), "")
        due = ""
        d = self.var_due.get().strip()
        if res == "yes" and d:
            try:
                due = datetime.strptime(d, "%d.%m.%Y").date().isoformat()
            except ValueError:
                messagebox.showwarning(APP_NAME, "Datum bitte als TT.MM.JJJJ eingeben, z. B. 20.12.2026")
                return
        with self.cfg_lock:
            o = self.me().setdefault("info", {}).setdefault(sel[0], {})
            o["result"], o["due"] = res, due
            save_config(self.cfg)
        self.render_apps()
        self.render_home()

    def delete_app(self):
        sel = self.atree.selection()
        if sel and messagebox.askyesno(APP_NAME, "Diesen Eintrag löschen? Das Häkchen „Beworben“ wird auch entfernt."):
            with self.cfg_lock:
                self.me().get("info", {}).pop(sel[0], None)
                self.me().get("applied", {}).pop(sel[0], None)
                save_config(self.cfg)
            self.render_apps()
            self.render_home()

    # ---------- Personen ----------
    def select_person(self, pid):
        if pid != self.cfg["active"]:
            self.set_cfg("active", pid)
            self.render_all()

    def add_person(self):
        if len(self.cfg["persons"]) >= 6:
            messagebox.showinfo(APP_NAME, "Höchstens 6 Personen.")
            return
        name = simpledialog.askstring(APP_NAME, "Name der Person (z. B. Vorname):", parent=self.root)
        if not name:
            return
        pid = f"p{int(time.time() * 1000)}"
        with self.cfg_lock:
            self.cfg["persons"].append({"id": pid, "label": name.strip(), "profile": {"text": engine.DEFAULT_TEXT}, "accounts": {}, "applied": {}, "info": {}})
            self.cfg["active"] = pid
            save_config(self.cfg)
        self.render_all()
        self.nb.select(self.tab_people)

    def rename_person(self):
        name = simpledialog.askstring(APP_NAME, "Neuer Name:", initialvalue=self.me()["label"], parent=self.root)
        if name:
            with self.cfg_lock:
                self.me()["label"] = name.strip()
                save_config(self.cfg)
            self.render_all()

    def delete_person(self):
        if len(self.cfg["persons"]) < 2:
            messagebox.showinfo(APP_NAME, "Die letzte Person kann nicht gelöscht werden.")
            return
        me = self.me()
        if not messagebox.askyesno(APP_NAME, f"„{me['label']}“ mit allen Daten, Konten und Häkchen löschen?"):
            return
        with self.cfg_lock:
            self.cfg["persons"] = [p for p in self.cfg["persons"] if p["id"] != me["id"]]
            self.cfg["active"] = self.cfg["persons"][0]["id"]
            save_config(self.cfg)
        self.worker.submit("reset", me["id"])
        self.render_all()

    def save_profile(self):
        prof = {k: v.get().strip() for k, v in self.pv.items()}
        prof["text"] = self.txt_main.get("1.0", "end").strip()
        for i, (v, tx) in enumerate(self.kw, start=1):
            prof[f"k{i}"] = v.get().strip()
            prof[f"t{i}"] = tx.get("1.0", "end").strip()
        with self.cfg_lock:
            self.me()["profile"] = prof
            save_config(self.cfg)
        messagebox.showinfo(APP_NAME, "Daten gespeichert.")

    def save_account(self, key):
        e, pw, lbl, st = self.acc[key]
        with self.cfg_lock:
            a = self.me().setdefault("accounts", {}).setdefault(key, {})
            a["email"] = e.get().strip()
            if pw.get():
                enc = encrypt_pw(pw.get())
                if not enc:
                    messagebox.showwarning(APP_NAME, "Passwort konnte nicht verschlüsselt werden.")
                    return
                a["pw"] = enc
            if not a["email"] and not pw.get():
                self.me()["accounts"].pop(key, None)
            save_config(self.cfg)
        self.worker.submit("reset", self.cfg["active"])  # Browser der Person mit neuen Zugangsdaten neu starten
        self.render_profile()
        st.configure(text="✓ gespeichert", foreground=GREEN)

    def open_browser(self, url):
        self.worker.submit("open", self.cfg["active"], url, None)

    def check_login(self):
        for _e, _p, _l, st in self.acc.values():
            st.configure(text="prüfe …", foreground=P["muted"])
        self.busy(True)
        self.worker.submit("loginstate", self.cfg["active"])

    def show_loginstate(self, res):
        for k, v in res.items():
            self.acc[k][3].configure(text={1: "✓ angemeldet", 0: "✗ nicht angemeldet"}.get(v, "? unklar"),
                                     foreground={1: GREEN, 0: "#d33"}.get(v, P["muted"]))

    # ---------- Einstellungen ----------
    def save_discord(self):
        url = self.var_dc.get().strip()
        if url and not engine.DISCORD_VALID.match(url):
            messagebox.showwarning(APP_NAME, "Das ist keine Discord-Webhook-Adresse (https://discord.com/api/webhooks/…).")
            return
        with self.cfg_lock:
            self.cfg["discordUrl"] = url
            self.cfg["discordOn"] = bool(url)
            save_config(self.cfg)
        self.var_dcon.set(bool(url))
        self.render_profile()

    def test_discord(self):
        url = self.cfg.get("discordUrl", "")
        if not engine.DISCORD_VALID.match(url):
            messagebox.showinfo(APP_NAME, "Erst eine Webhook-Adresse speichern.")
            return
        self.lbl_dc.configure(text="sende …")

        def go():
            code = engine.discord_send(url, "🦊 Fox Testpilot (Windows) ist verbunden", "Ab jetzt kommen die Meldungen auch vom PC hier an.",
                                       engine.SHOPS[0]["url"], self.cfg.get("discordPing", False))
            self.post(lambda: self.lbl_dc.configure(text="✓ gesendet – angekommen?" if 200 <= code < 300 else f"✗ Fehler {code}"))

        threading.Thread(target=go, daemon=True).start()

    def add_custom(self):
        name, url, kw = self.var_cn.get().strip(), self.var_cu.get().strip(), self.var_ck.get().strip()
        if not url.startswith(("http://", "https://")) or "." not in url:
            messagebox.showwarning(APP_NAME, "Bitte eine vollständige Adresse eingeben (https://…).")
            return
        if not kw:
            messagebox.showwarning(APP_NAME, "Bitte mindestens ein Stichwort eingeben.")
            return
        if len(self.cfg.get("custom", [])) >= 8:
            messagebox.showwarning(APP_NAME, "Höchstens 8 eigene Seiten.")
            return
        with self.cfg_lock:
            self.cfg.setdefault("custom", []).append({"id": f"c{int(time.time() * 1000)}", "name": name or url.split("/")[2], "url": url, "keywords": kw})
            save_config(self.cfg)
        for v in (self.var_cn, self.var_cu, self.var_ck):
            v.set("")
        self.render_custom()
        self.check(True)

    def remove_custom(self):
        sel = self.lst_custom.curselection()
        if not sel:
            return
        with self.cfg_lock:
            del self.cfg["custom"][sel[0]]
            save_config(self.cfg)
        self.render_custom()
        self.render_home()

    def dialog(self, title, w=640, h=440):
        win = tk.Toplevel(self.root, bg=P["bg"])
        win.title(title)
        win.geometry(f"{w}x{h}")
        try:
            win.iconbitmap(ICON)
        except Exception:  # noqa: BLE001
            pass
        return win

    def import_backup(self):
        win = self.dialog("Sicherung einspielen")
        ttk.Label(win, text="Sicherung aus der Android-App hier einfügen (Strg+V):").pack(anchor="w", padx=14, pady=(14, 6))
        tx = self.text_widget(win, 10, mono=True)
        tx.pack(fill="both", expand=True, padx=14)

        def go():
            if not messagebox.askyesno(APP_NAME, "Sicherung einspielen? Die jetzigen Personen und Häkchen werden ersetzt.", parent=win):
                return
            try:
                with self.cfg_lock:
                    n = engine.import_backup(self.cfg, tx.get("1.0", "end"))
                    save_config(self.cfg)
            except Exception as e:  # noqa: BLE001
                messagebox.showerror(APP_NAME, f"Import fehlgeschlagen: {e}", parent=win)
                return
            win.destroy()
            self.imgs = {k: v for k, v in self.imgs.items() if not k.startswith("av")}
            self.render_all()
            messagebox.showinfo(APP_NAME, f"{n} Person(en) übernommen. Bitte unter „Personen“ die Passwörter neu eintragen.")

        ttk.Button(win, text="Einspielen", style="Accent.TButton", command=go).pack(pady=12)

    def export_backup(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(engine.export_backup(self.cfg))
        messagebox.showinfo(APP_NAME, "Sicherung (ohne Passwörter) in die Zwischenablage kopiert.")

    def diagnose(self):
        self.busy(True)
        self.log("Diagnose läuft (bis zu 1,5 Minuten) …")
        self.worker.submit("diagnose")

    def show_text(self, title, text):
        win = self.dialog(title, 780, 640)
        tx = self.text_widget(win, 20, mono=True)
        tx.insert("1.0", text)
        tx.pack(fill="both", expand=True, padx=12, pady=12)

        def copy_text():
            self.root.clipboard_clear()
            self.root.clipboard_append(text)

        ttk.Button(win, text="Kopieren", style="Accent.TButton", command=copy_text).pack(pady=(0, 12))

    # ---------- Prüfen und Zeitplan ----------
    def check(self, manual=False):
        if self._busy and not manual:
            return
        self.next_check = time.time() + self.cfg.get("interval", 30) * 60
        self.busy(True)
        self.worker.submit("check", manual)

    def tick(self):
        self.update_countdown()
        if time.time() >= self.next_check and not self._busy:
            self.check()
        minute = int(time.time() // 60)
        if minute != getattr(self, "_minute", None):
            self._minute = minute
            with self.cfg_lock:
                recheck = engine.reminders(self.cfg, notify)
                save_config(self.cfg)
            if recheck:
                self.check(True)
            self.render_history()
        self.root.after(1000, self.tick)

    def set_autostart(self):
        on = self.var_auto.get()
        try:
            import winreg
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_SET_VALUE)
            if on:
                winreg.SetValueEx(key, "FoxTestpilot", 0, winreg.REG_SZ, f'"{sys.executable}"')
            else:
                try:
                    winreg.DeleteValue(key, "FoxTestpilot")
                except FileNotFoundError:
                    pass
            winreg.CloseKey(key)
        except Exception as e:  # noqa: BLE001
            self.log(f"Autostart: {e}")
        self.set_cfg("autostart", on)

    # ---------- Tray-Symbol: Fenster schließen = weiterlaufen im Hintergrund ----------
    def start_tray(self):
        try:
            import pystray
            menu = pystray.Menu(
                pystray.MenuItem("Fox Testpilot öffnen", lambda *_: self.post(self.show_window), default=True),
                pystray.MenuItem("Jetzt prüfen", lambda *_: self.post(self.check, True)),
                pystray.MenuItem("Beenden", lambda *_: self.post(self.quit)),
            )
            self.tray = pystray.Icon("FoxTestpilot", Image.open(ICON), "Fox Testpilot – läuft", menu)
            self.tray.run_detached()
        except Exception as e:  # noqa: BLE001
            self.tray = None
            self.log(f"Tray-Symbol nicht verfügbar: {e}")

    def show_window(self):
        self.root.deiconify()
        self.root.lift()

    def quit(self):
        try:
            if self.tray:
                self.tray.stop()
        except Exception:  # noqa: BLE001
            pass
        self.worker.submit("quit")
        self.root.after(1500, self.root.destroy)

    def on_close(self):
        if self.tray:
            self.root.withdraw()
            if not getattr(self, "_tray_hint", False):
                self._tray_hint = True
                notify("🦊 Fox Testpilot läuft weiter", "Das Programm prüft im Hintergrund. Beenden über das Fuchs-Symbol unten rechts neben der Uhr.", discord=False)
        else:
            self.quit()

    # ---------- Update ----------
    def check_update(self):
        if not BUILD:
            return
        try:
            req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases?per_page=30", headers={"User-Agent": "FoxTestpilot"})
            rels = json.loads(urllib.request.urlopen(req, timeout=20).read().decode())
            best = None
            for r in rels:
                tag = r.get("tag_name", "")
                if not tag.startswith("win-v"):
                    continue
                n = int(tag[5:]) if tag[5:].isdigit() else 0
                asset = next((a for a in r.get("assets", []) if a["name"].lower().endswith(".exe")), None)
                if asset and n > BUILD and (best is None or n > best[0]):
                    best = (n, asset["browser_download_url"])
            if best:
                self.update_url = best[1]
                self.post(lambda: (self.upd.configure(text=f"Version 1.{best[0]} installieren"), self.upd.pack(side="right", padx=8)))
                self.log(f"Neue Version 1.{best[0]} verfügbar")
        except Exception as e:  # noqa: BLE001
            self.log(f"Update-Prüfung: {e}")

    def install_update(self):
        if not self.update_url:
            return
        if not getattr(sys, "frozen", False):
            webbrowser.open(self.update_url)
            return
        self.upd.configure(text="Lade Update …")

        def go():
            try:
                exe = sys.executable
                new = exe + ".new"
                urllib.request.urlretrieve(self.update_url, new)
                bat = os.path.join(tempfile.gettempdir(), "foxtestpilot_update.bat")
                with open(bat, "w", encoding="utf-8") as f:
                    f.write(f'@echo off\r\nping 127.0.0.1 -n 3 >nul\r\n:loop\r\nmove /y "{new}" "{exe}" >nul 2>&1 || (ping 127.0.0.1 -n 2 >nul & goto loop)\r\nstart "" "{exe}"\r\ndel "%~f0"\r\n')
                subprocess.Popen(["cmd", "/c", bat], creationflags=0x08000000)
                self.post(self.quit)
            except Exception as e:  # noqa: BLE001
                self.log(f"Update fehlgeschlagen: {e}")

        threading.Thread(target=go, daemon=True).start()

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    App().run()
