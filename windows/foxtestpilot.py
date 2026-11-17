#!/usr/bin/env python3
"""Fox Testpilot für Windows: überwacht die Testpiloten von MediaMarkt und Saturn, meldet sich (Windows + Discord)
und hilft beim Bewerben (Ausfüllen, Auto-Modus mit Probelauf)."""
import copy
import json
import os
import queue
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

APP_NAME = "Fox Testpilot"
REPO = "Djlover007/Fox-Testpilot"
ICON = engine.resource("fox.ico")
INTERVALS = {"15 Minuten": 15, "30 Minuten": 30, "1 Stunde": 60, "3 Stunden": 180}

BG = "#eef4fd"
CARD = "#ffffff"
BLUE = "#2b6fde"
FOX = "#f28c28"
GREEN = "#1f9d55"
PURPLE = "#7b2fd1"
MUTED = "#5d6b88"
HERO = {"open": "#1f9d55", "soon": "#e0761c", "none": "#3b4fc4", "err": "#a8650b"}

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
        cfg = self.app.snapshot()
        self.eng.open_visible(cfg, pid, url, product)

    def job_reset(self, pid):
        self.eng.reset(pid)

    def job_loginstate(self, pid):
        cfg = self.app.snapshot()
        res = {s["key"]: self.eng.login_state(cfg, pid, s) for s in SHOPS}
        self.app.post(self.app.show_loginstate, res)

    def job_diagnose(self):
        text = self.eng.diagnose()
        self.app.post(self.app.show_text, "🔍 Diagnose", text)

    def job_queue(self, items):
        cfg = self.app.snapshot()

        def progress(n, total, label):
            self.app.post(self.app.set_progress, n, total, label)

        def applied(pid, it):
            self.app.post(self.app.mark_applied, pid, it["aid"], True, it.get("name", ""), it.get("shop", ""))

        sent, total, report = self.eng.run_queue(cfg, items, progress, applied)
        if report:
            self.app.post(self.app.show_text, "🧪 Probelauf-Bericht", report)
        else:
            self.app.post(lambda: messagebox.showinfo(APP_NAME, f"🤖 Fertig: {sent} von {total} Bewerbungen abgeschickt."))
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
        self.root = tk.Tk()
        self.root.title(f"🦊 {APP_NAME}" + (f" – Version 1.{BUILD}" if BUILD else ""))
        self.root.geometry("1040x800")
        self.root.minsize(900, 680)
        self.root.configure(bg=BG)
        try:
            self.root.iconbitmap(ICON)
        except Exception:  # noqa: BLE001
            pass
        self.style()
        self.build()
        self.worker = Worker(self)
        self.worker.start()
        self.render_all()
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
        self.logbox.configure(state="normal")
        self.logbox.insert("end", line + "\n")
        self.logbox.see("end")
        self.logbox.configure(state="disabled")

    def busy(self, on):
        self.post(self._set_busy, on)

    def _set_busy(self, on):
        self._busy = max(0, self._busy + (1 if on else -1))
        self.btn_check.configure(text="⏳ Läuft …" if self._busy > 0 else "🔍 Jetzt prüfen")

    def save(self):
        with self.cfg_lock:
            save_config(self.cfg)

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

    # ---------- Aussehen ----------
    def style(self):
        st = ttk.Style()
        try:
            st.theme_use("clam")
        except tk.TclError:
            pass
        st.configure(".", font=("Segoe UI", 10), background=BG)
        st.configure("TNotebook", background=BG, borderwidth=0)
        st.configure("TNotebook.Tab", padding=(16, 8), font=("Segoe UI Semibold", 10))
        st.map("TNotebook.Tab", background=[("selected", CARD)], foreground=[("selected", BLUE)])
        st.configure("TFrame", background=BG)
        st.configure("Card.TFrame", background=CARD)
        st.configure("TLabel", background=BG)
        st.configure("Card.TLabel", background=CARD)
        st.configure("Muted.TLabel", background=CARD, foreground=MUTED, font=("Segoe UI", 9))
        st.configure("H.TLabel", background=CARD, font=("Segoe UI Semibold", 12))
        st.configure("TCheckbutton", background=CARD)
        st.configure("Accent.TButton", background=BLUE, foreground="white", padding=(12, 7), font=("Segoe UI Semibold", 10))
        st.map("Accent.TButton", background=[("active", "#1f5cc0")])
        st.configure("Fox.TButton", background=FOX, foreground="white", padding=(12, 7), font=("Segoe UI Semibold", 10))
        st.map("Fox.TButton", background=[("active", "#d4700f")])
        st.configure("Purple.TButton", background=PURPLE, foreground="white", padding=(12, 7), font=("Segoe UI Semibold", 10))
        st.map("Purple.TButton", background=[("active", "#6224ad")])
        st.configure("TButton", padding=(10, 6))
        st.configure("Treeview", rowheight=30, font=("Segoe UI", 10))
        st.configure("Treeview.Heading", font=("Segoe UI Semibold", 10))

    def card(self, parent, title=None, **pack):
        f = ttk.Frame(parent, style="Card.TFrame", padding=14)
        f.pack(fill=pack.pop("fill", "x"), expand=pack.pop("expand", False), padx=12, pady=(10, 0), **pack)
        if title:
            ttk.Label(f, text=title, style="H.TLabel").pack(anchor="w", pady=(0, 6))
        return f

    def scrollable(self, parent):
        """Bereich mit Bildlaufleiste (für lange Seiten)."""
        canvas = tk.Canvas(parent, bg=BG, highlightthickness=0)
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
        cols.columnconfigure(0, weight=left_weight, uniform="c" if left_weight == right_weight else "")
        cols.columnconfigure(1, weight=right_weight, minsize=right_min, uniform="c" if left_weight == right_weight else "")
        left, right = ttk.Frame(cols), ttk.Frame(cols)
        left.grid(row=0, column=0, sticky="nsew")
        right.grid(row=0, column=1, sticky="nsew")
        return left, right

    # ---------- Oberfläche ----------
    def build(self):
        top = tk.Frame(self.root, bg=BG)
        top.pack(fill="x", padx=12, pady=(10, 0))
        tk.Label(top, text="🦊 Fox Testpilot", font=("Segoe UI Semibold", 17), bg=BG).pack(side="left")
        self.btn_check = ttk.Button(top, text="🔍 Jetzt prüfen", style="Accent.TButton", command=lambda: self.check(True))
        self.btn_check.pack(side="right")
        self.upd = ttk.Button(top, text="⬆️ Update", style="Fox.TButton", command=self.install_update)
        tk.Label(top, text="   Person:", bg=BG, font=("Segoe UI", 10)).pack(side="left")
        self.var_person = tk.StringVar()
        self.cb_person = ttk.Combobox(top, textvariable=self.var_person, state="readonly", width=16)
        self.cb_person.pack(side="left", padx=6)
        self.cb_person.bind("<<ComboboxSelected>>", lambda _e: self.select_person())

        self.nb = ttk.Notebook(self.root)
        self.nb.pack(fill="both", expand=True, padx=8, pady=8)
        self.tab_home = ttk.Frame(self.nb)
        self.tab_apps = ttk.Frame(self.nb)
        self.tab_people = ttk.Frame(self.nb)
        self.tab_set = ttk.Frame(self.nb)
        self.tab_log = ttk.Frame(self.nb)
        self.nb.add(self.tab_home, text="🏠 Start")
        self.nb.add(self.tab_apps, text="📋 Bewerbungen")
        self.nb.add(self.tab_people, text="👥 Personen")
        self.nb.add(self.tab_set, text="⚙️ Einstellungen")
        self.nb.add(self.tab_log, text="📜 Verlauf")
        self.build_home()
        self.build_apps()
        self.build_people()
        self.build_settings()
        self.build_log()

    def build_home(self):
        t = self.tab_home
        self.hero = tk.Frame(t, bg=HERO["none"], padx=20, pady=16)
        self.hero.pack(fill="x", padx=12, pady=(10, 0))
        self.h_last = tk.Label(self.hero, text="Noch nicht geprüft", bg=HERO["none"], fg="#e8ecff", font=("Segoe UI", 9))
        self.h_last.pack(anchor="w")
        self.h_title = tk.Label(self.hero, text="Testpiloten", bg=HERO["none"], fg="white", font=("Segoe UI Semibold", 20))
        self.h_title.pack(anchor="w")
        self.h_sub = tk.Label(self.hero, text="", bg=HERO["none"], fg="white", font=("Segoe UI", 11))
        self.h_sub.pack(anchor="w")
        self.h_cd = tk.Label(self.hero, text="", bg=HERO["none"], fg="white", font=("Segoe UI Semibold", 22))
        self.h_cd.pack(anchor="w", pady=(6, 0))

        c = self.card(t, "Testprodukte", fill="both", expand=True)
        self.shoplines = ttk.Label(c, text="", style="Card.TLabel", justify="left")
        self.shoplines.pack(anchor="w", pady=(0, 6))
        cols = ("auto", "shop", "name", "status", "who")
        self.tree = ttk.Treeview(c, columns=cols, show="headings", height=9, selectmode="browse")
        for col, txt, w, anchor in (("auto", "Auto", 60, "center"), ("shop", "Shop", 100, "w"), ("name", "Produkt", 420, "w"),
                                    ("status", "Status", 140, "w"), ("who", "Beworben", 180, "w")):
            self.tree.heading(col, text=txt)
            self.tree.column(col, width=w, anchor=anchor, stretch=(col == "name"))
        self.tree.tag_configure("new", foreground=FOX)
        self.tree.pack(fill="both", expand=True)
        self.tree.bind("<Button-1>", self.tree_click)
        self.tree.bind("<Double-1>", lambda _e: self.apply_selected())
        row = ttk.Frame(c, style="Card.TFrame")
        row.pack(fill="x", pady=(8, 0))
        ttk.Button(row, text="✍️ Bewerben (Browser mit Ausfüllen)", style="Accent.TButton", command=self.apply_selected).pack(side="left")
        ttk.Button(row, text="✓ Beworben an/aus", command=self.toggle_applied).pack(side="left", padx=6)
        ttk.Button(row, text="Seite öffnen", command=self.open_selected_page).pack(side="left")
        self.var_probe = tk.BooleanVar(value=self.cfg.get("probe", True))
        ttk.Checkbutton(row, text="🧪 Probelauf (nichts abschicken)", variable=self.var_probe, command=self.save_probe).pack(side="right")
        row2 = ttk.Frame(c, style="Card.TFrame")
        row2.pack(fill="x", pady=(8, 0))
        self.btn_auto = ttk.Button(row2, text="🤖 Auto", style="Fox.TButton", command=lambda: self.start_queue(False))
        self.btn_auto.pack(side="left")
        self.btn_all = ttk.Button(row2, text="👥 Alle Personen", style="Purple.TButton", command=lambda: self.start_queue(True))
        self.btn_all.pack(side="left", padx=6)
        self.lbl_progress = ttk.Label(row2, text="Tipp: In der Spalte „Auto“ Produkte anklicken, dann einen der Knöpfe.", style="Muted.TLabel")
        self.lbl_progress.pack(side="left", padx=8)
        self.customlines = ttk.Label(c, text="", style="Card.TLabel", justify="left")
        self.customlines.pack(anchor="w", pady=(8, 0))

    def build_apps(self):
        c = self.card(self.tab_apps, "Meine Bewerbungen", fill="both", expand=True)
        ttk.Label(c, text="Trag ein, ob du ausgewählt wurdest. Bei „Ausgewählt“ die Frist für die Bewertung eintragen – die App erinnert 3 Tage vorher und am letzten Tag (wenn sie läuft).",
                  style="Muted.TLabel", wraplength=900).pack(anchor="w", pady=(0, 8))
        cols = ("name", "shop", "date", "result", "due")
        self.atree = ttk.Treeview(c, columns=cols, show="headings", height=14, selectmode="browse")
        for col, txt, w in (("name", "Produkt", 420), ("shop", "Shop", 110), ("date", "Beworben am", 110), ("result", "Ergebnis", 150), ("due", "Bewertung bis", 120)):
            self.atree.heading(col, text=txt)
            self.atree.column(col, width=w, stretch=(col == "name"))
        self.atree.pack(fill="both", expand=True)
        self.atree.bind("<<TreeviewSelect>>", lambda _e: self.app_selected())
        row = ttk.Frame(c, style="Card.TFrame")
        row.pack(fill="x", pady=(8, 0))
        self.var_result = tk.StringVar()
        ttk.Combobox(row, textvariable=self.var_result, state="readonly", width=18, values=["⏳ Noch offen", "🎉 Ausgewählt", "Nicht ausgewählt"]).pack(side="left")
        ttk.Label(row, text="  Bewertung bis (TT.MM.JJJJ):", style="Card.TLabel").pack(side="left")
        self.var_due = tk.StringVar()
        ttk.Entry(row, textvariable=self.var_due, width=12).pack(side="left", padx=6)
        ttk.Button(row, text="Übernehmen", style="Accent.TButton", command=self.save_result).pack(side="left")
        ttk.Button(row, text="🗑️ Eintrag löschen", command=self.delete_app).pack(side="right")

    def build_people(self):
        outer = self.scrollable(self.tab_people)
        row = self.card(outer)
        ttk.Label(row, text="Jede Person im Haushalt hat eigene Daten, eigene Konten und ein eigenes Browser-Profil. Oben rechts wechselst du die Person.",
                  style="Muted.TLabel", wraplength=900).pack(anchor="w")
        br = ttk.Frame(row, style="Card.TFrame")
        br.pack(anchor="w", pady=(8, 0))
        ttk.Button(br, text="➕ Neue Person", command=self.add_person).pack(side="left")
        ttk.Button(br, text="✏️ Umbenennen", command=self.rename_person).pack(side="left", padx=6)
        ttk.Button(br, text="🗑️ Person löschen", command=self.delete_person).pack(side="left")

        lcol, rcol = self.two_columns(outer, 3, 2, 400)
        left = self.card(lcol, "✍️ Daten zum Ausfüllen", fill="both", expand=True)
        self.pv = {}
        grid = ttk.Frame(left, style="Card.TFrame")
        grid.pack(fill="x")
        grid.columnconfigure(1, weight=1)
        fields = [("salutation", "Anrede (Herr/Frau)"), ("first", "Vorname"), ("last", "Nachname"), ("street", "Straße"), ("nr", "Hausnummer"),
                  ("zip", "PLZ"), ("city", "Ort"), ("email", "E-Mail (falls kein Konto)"), ("phone", "Telefon"), ("birth", "Geburtsdatum TT.MM.JJJJ")]
        for i, (k, label) in enumerate(fields):
            ttk.Label(grid, text=label, style="Card.TLabel").grid(row=i, column=0, sticky="w", pady=2)
            v = tk.StringVar()
            self.pv[k] = v
            ttk.Entry(grid, textvariable=v, width=24).grid(row=i, column=1, sticky="we", pady=2, padx=(8, 0))
        ttk.Label(left, text="Bewerbungstext ({produkt} = Produktname):", style="Card.TLabel").pack(anchor="w", pady=(8, 2))
        self.txt_main = tk.Text(left, height=4, wrap="word", font=("Segoe UI", 10), relief="solid", bd=1)
        self.txt_main.pack(fill="x")
        ttk.Label(left, text="Eigene Texte je Stichwort (z. B. „tv, fernseher“):", style="Card.TLabel").pack(anchor="w", pady=(8, 2))
        self.kw = []
        for _ in range(3):
            v = tk.StringVar()
            ttk.Entry(left, textvariable=v).pack(fill="x", pady=(2, 0))
            tx = tk.Text(left, height=2, wrap="word", font=("Segoe UI", 10), relief="solid", bd=1)
            tx.pack(fill="x", pady=(2, 4))
            self.kw.append((v, tx))
        ttk.Button(left, text="💾 Daten speichern", style="Accent.TButton", command=self.save_profile).pack(anchor="w", pady=(6, 0))

        right = self.card(rcol, "🔑 Konten", fill="both", expand=True)
        ttk.Label(right, text="Die App meldet die Person damit automatisch an. Passwörter werden mit Windows verschlüsselt und nur für deinen Windows-Benutzer gespeichert.",
                  style="Muted.TLabel", wraplength=330).pack(anchor="w", pady=(0, 6))
        self.acc = {}
        for s in SHOPS:
            ttk.Label(right, text=("myMediaMarkt" if s["key"] == "mm" else "mySaturn"), style="H.TLabel").pack(anchor="w", pady=(8, 2))
            e, p = tk.StringVar(), tk.StringVar()
            ttk.Label(right, text="E-Mail", style="Card.TLabel").pack(anchor="w")
            ttk.Entry(right, textvariable=e).pack(fill="x")
            lbl = ttk.Label(right, text="Passwort", style="Card.TLabel")
            lbl.pack(anchor="w")
            ttk.Entry(right, textvariable=p, show="•").pack(fill="x")
            r = ttk.Frame(right, style="Card.TFrame")
            r.pack(fill="x", pady=(4, 0))
            ttk.Button(r, text="Speichern", style="Accent.TButton", command=lambda k=s["key"]: self.save_account(k)).pack(side="left")
            ttk.Button(r, text="Anmelden", command=lambda u=s["account"]: self.open_browser(u)).pack(side="left", padx=6)
            st = ttk.Label(right, text="", style="Card.TLabel")
            st.pack(anchor="w")
            self.acc[s["key"]] = (e, p, lbl, st)
        ttk.Button(right, text="🔎 Anmelde-Status prüfen", command=self.check_login).pack(anchor="w", pady=(12, 0))

    def build_settings(self):
        outer = self.scrollable(self.tab_set)
        left, right = self.two_columns(outer, 1, 1, 0)

        c = self.card(left, "🔔 Meldungen")
        r = ttk.Frame(c, style="Card.TFrame")
        r.pack(fill="x")
        ttk.Label(r, text="Prüfen alle", style="Card.TLabel").pack(side="left")
        self.var_interval = tk.StringVar(value=next((k for k, v in INTERVALS.items() if v == self.cfg.get("interval", 30)), "30 Minuten"))
        cb = ttk.Combobox(r, textvariable=self.var_interval, values=list(INTERVALS), state="readonly", width=12)
        cb.pack(side="left", padx=8)
        cb.bind("<<ComboboxSelected>>", lambda _e: self.set_cfg("interval", INTERVALS[self.var_interval.get()]))
        self.var_alarm = tk.BooleanVar(value=self.cfg.get("alarm", False))
        ttk.Checkbutton(c, text="🔊 Lauter Alarm, wenn die Bewerbung startet", variable=self.var_alarm,
                        command=lambda: self.set_cfg("alarm", self.var_alarm.get())).pack(anchor="w", pady=(6, 0))
        self.var_auto = tk.BooleanVar(value=self.cfg.get("autostart", False))
        ttk.Checkbutton(c, text="🚀 Mit Windows starten (läuft dann unten rechts neben der Uhr)", variable=self.var_auto, command=self.set_autostart).pack(anchor="w")
        ttk.Button(c, text="🔔 Test-Benachrichtigung", command=lambda: notify("🦊 Fox Testpilot", "Benachrichtigungen funktionieren!", discord=False)).pack(anchor="w", pady=(6, 0))

        c = self.card(left, "💬 Discord")
        ttk.Label(c, text="Webhook-Adresse (Discord: Kanal bearbeiten → Integrationen → Webhooks → Neuer Webhook → URL kopieren):",
                  style="Muted.TLabel", wraplength=420).pack(anchor="w")
        self.var_dc = tk.StringVar()
        ttk.Entry(c, textvariable=self.var_dc, show="•").pack(fill="x", pady=(4, 0))
        r = ttk.Frame(c, style="Card.TFrame")
        r.pack(fill="x", pady=(6, 0))
        ttk.Button(r, text="Speichern", style="Accent.TButton", command=self.save_discord).pack(side="left")
        ttk.Button(r, text="Test senden", command=self.test_discord).pack(side="left", padx=6)
        self.lbl_dc = ttk.Label(r, text="", style="Card.TLabel")
        self.lbl_dc.pack(side="left")
        self.var_dcon = tk.BooleanVar(value=self.cfg.get("discordOn", False))
        ttk.Checkbutton(c, text="Meldungen an Discord senden", variable=self.var_dcon, command=lambda: self.set_cfg("discordOn", self.var_dcon.get())).pack(anchor="w", pady=(6, 0))
        self.var_dcping = tk.BooleanVar(value=self.cfg.get("discordPing", False))
        ttk.Checkbutton(c, text="📣 Beim Start @everyone erwähnen", variable=self.var_dcping, command=lambda: self.set_cfg("discordPing", self.var_dcping.get())).pack(anchor="w")

        c = self.card(right, "🌐 Eigene Seiten")
        ttk.Label(c, text="Weitere Produkttester-Seiten: Meldung, sobald eines der Stichwörter neu auf der Seite steht.", style="Muted.TLabel", wraplength=420).pack(anchor="w")
        self.lst_custom = tk.Listbox(c, height=4, font=("Segoe UI", 10), relief="solid", bd=1)
        self.lst_custom.pack(fill="x", pady=(6, 4))
        g = ttk.Frame(c, style="Card.TFrame")
        g.pack(fill="x")
        g.columnconfigure(1, weight=1)
        self.var_cn, self.var_cu, self.var_ck = tk.StringVar(), tk.StringVar(), tk.StringVar()
        for i, (lbl, v) in enumerate((("Name", self.var_cn), ("Adresse", self.var_cu), ("Stichwörter", self.var_ck))):
            ttk.Label(g, text=lbl, style="Card.TLabel").grid(row=i, column=0, sticky="w")
            ttk.Entry(g, textvariable=v, width=20).grid(row=i, column=1, sticky="we", padx=(8, 0), pady=1)
        r = ttk.Frame(c, style="Card.TFrame")
        r.pack(fill="x", pady=(6, 0))
        ttk.Button(r, text="➕ Hinzufügen", style="Accent.TButton", command=self.add_custom).pack(side="left")
        ttk.Button(r, text="🗑️ Markierte entfernen", command=self.remove_custom).pack(side="left", padx=6)

        c = self.card(right, "💾 Sicherung · 🔍 Diagnose")
        ttk.Label(c, text="Die Sicherung aus der Android-App (Einstellungen → Sicherung) kannst du hier einspielen – Personen, Daten, Texte und Häkchen. Passwörter musst du neu eintragen.",
                  style="Muted.TLabel", wraplength=420).pack(anchor="w")
        r = ttk.Frame(c, style="Card.TFrame")
        r.pack(fill="x", pady=(6, 0))
        ttk.Button(r, text="📥 Sicherung einspielen", command=self.import_backup).pack(side="left")
        ttk.Button(r, text="📋 Sicherung kopieren", command=self.export_backup).pack(side="left", padx=6)
        ttk.Label(c, text="Diagnose: zeigt, was das Programm auf den Testpiloten-Seiten liest.", style="Muted.TLabel", wraplength=420).pack(anchor="w", pady=(10, 0))
        ttk.Button(c, text="🔍 Seiten testen", command=self.diagnose).pack(anchor="w", pady=(4, 0))

    def build_log(self):
        c = self.card(self.tab_log, "Verlauf", fill="both", expand=True)
        self.histbox = tk.Text(c, height=12, wrap="word", font=("Segoe UI", 10), relief="flat", bg="#f7f9fd")
        self.histbox.pack(fill="both", expand=True)
        ttk.Label(c, text="Technisches Protokoll", style="H.TLabel").pack(anchor="w", pady=(10, 4))
        self.logbox = tk.Text(c, height=8, wrap="word", font=("Consolas", 9), relief="flat", bg="#f7f9fd", state="disabled")
        self.logbox.pack(fill="both", expand=True)

    # ---------- Anzeigen ----------
    def render_all(self):
        self.render_people()
        self.render_home()
        self.render_apps()
        self.render_profile()
        self.render_custom()
        self.render_history()

    def render_people(self):
        names = [p["label"] for p in self.cfg["persons"]]
        self.cb_person.configure(values=names)
        self.var_person.set(self.me()["label"])

    def status_of(self, aid):
        me = self.me()
        r = (me.get("info", {}).get(aid) or {}).get("result")
        if r == "yes":
            return "🎉 Ausgewählt"
        if r == "no":
            return "Nicht ausgewählt"
        return "✓ Beworben" if aid in me.get("applied", {}) else "Offen"

    def shops(self):
        return (self.cfg.get("last") or {}).get("shops", [])

    def render_home(self):
        last = self.cfg.get("last") or {}
        lst = self.shops()
        self.shoplines.configure(text="\n".join(f"{s['name']}:  {shop_line(s)}" for s in lst) or "Noch nicht geprüft")
        sel = self.tree.selection()
        self.tree.delete(*self.tree.get_children())
        for s in lst:
            for p in s.get("products", []):
                who = ", ".join(x["label"] for x in self.cfg["persons"] if p["aid"] in x.get("applied", {}))
                self.tree.insert("", "end", iid=p["aid"], values=("☑" if p["aid"] in self.sel else "☐", s["name"], p["name"] + ("  · NEU" if p.get("isNew") else ""),
                                                                  self.status_of(p["aid"]), who), tags=("new",) if p.get("isNew") else ())
        if sel and self.tree.exists(sel[0]):
            self.tree.selection_set(sel[0])
        res = {x["id"]: x for x in last.get("custom", [])}
        lines = []
        for p in self.cfg.get("custom", []):
            r = res.get(p["id"], {})
            f = r.get("found") or []
            lines.append(f"🌐 {p['name']}:  " + (("⚠️ " + r["error"]) if r.get("error") else "noch nicht geprüft" if not r else
                                                ("🔔 gefunden: „" + "“, „".join(f) + "“") if f else "keines der Stichwörter"))
        self.customlines.configure(text="\n".join(lines))
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
                kind, title = "open", "Bewerbung ist offen! 🚀"
                sub = " & ".join(x["name"] for x in opened) + ("" if s.get("endUnknown") else f" · {s.get('start', '')[:6]} – {s.get('end', '')}")
                if s.get("endAt", 0) > now:
                    target, lab = s["endAt"], "Endet in"
            elif soon:
                s = soon[0]
                kind, title = "soon", "Nächste Runde kommt 📅"
                if s.get("startAt", 0) > now:
                    sub, target, lab = f"{s['name']}: Start {engine.fmt_when(s['startAt'])}", s["startAt"], "Startet in"
                elif s.get("startYmd"):
                    sub = f"{s['name']}: Bewerbung vom {s['start'][:6]} bis {s['end']}"
                else:
                    sub = " & ".join(x["name"] for x in soon) + ": Termin noch nicht bekannt – ich passe auf"
            else:
                title, sub = "Gerade keine Testaktion", "Ich melde mich, sobald es bei MediaMarkt oder Saturn eine neue Runde gibt."
            if all(s.get("error") for s in lst):
                kind, sub, target = "err", "⚠️ " + lst[0]["error"], 0
        self.hero_target, self.hero_label = target, lab
        col = HERO[kind]
        for w in (self.hero, self.h_last, self.h_title, self.h_sub, self.h_cd):
            w.configure(bg=col)
        t = last.get("time")
        self.h_last.configure(text=("Geprüft " + datetime.fromtimestamp(t / 1000).strftime("%d.%m. %H:%M")) if t else "Noch nicht geprüft")
        self.h_title.configure(text=title)
        self.h_sub.configure(text=sub)
        self.update_countdown()

    def update_countdown(self):
        target = getattr(self, "hero_target", 0)
        s = int((target - time.time() * 1000) / 1000) if target else 0
        if s <= 0:
            self.h_cd.configure(text="")
            return
        self.h_cd.configure(text=f"⏳ {self.hero_label}  {s // 86400} Tage  {s % 86400 // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}")

    def render_auto(self):
        n = self.count(self.build_queue(False))
        multi = len(self.cfg["persons"]) > 1
        a = self.count(self.build_queue(True)) if multi else 0
        ic = "🧪" if self.var_probe.get() else "🤖"
        self.btn_auto.configure(text=f"{ic} {'Probelauf' if self.var_probe.get() else 'Automatisch bewerben'}: {n}" + (f" ({self.me()['label']})" if multi else ""))
        self.btn_auto.configure(state="normal" if n else "disabled")
        if multi:
            self.btn_all.configure(text=f"{ic} Alle Personen: {a}", state="normal" if a else "disabled")
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
            res = {"yes": "🎉 Ausgewählt", "no": "Nicht ausgewählt"}.get(o.get("result"), "⏳ Noch offen")
            due = o.get("due", "")
            due = f"{due[8:10]}.{due[5:7]}.{due[:4]}" if len(due) == 10 else ""
            self.atree.insert("", "end", iid=aid, values=(o.get("name") or names.get(aid) or f"Produkt {aid}", o.get("shop", ""),
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
        for k, (e, p, lbl, st) in self.acc.items():
            a = me.get("accounts", {}).get(k) or {}
            e.set(a.get("email", ""))
            p.set("")
            lbl.configure(text="Passwort (gespeichert – leer lassen zum Behalten)" if a.get("pw") else "Passwort")
            st.configure(text="")
        self.var_dc.set("")
        self.lbl_dc.configure(text="✅ gespeichert" if engine.DISCORD_VALID.match(self.cfg.get("discordUrl", "")) else "")

    def render_custom(self):
        self.lst_custom.delete(0, "end")
        for p in self.cfg.get("custom", []):
            self.lst_custom.insert("end", f"{p['name']}  ·  {p['url']}  ·  🔑 {p['keywords']}")

    def render_history(self):
        self.histbox.configure(state="normal")
        self.histbox.delete("1.0", "end")
        self.histbox.insert("1.0", "\n".join(self.cfg.get("history", [])) or "Noch nichts passiert.")
        self.histbox.configure(state="disabled")

    # ---------- Start-Seite: Aktionen ----------
    def product(self, aid):
        for s in self.shops():
            for p in s.get("products", []):
                if p["aid"] == aid:
                    return s, p
        return None, None

    def tree_click(self, ev):
        if self.tree.identify_region(ev.x, ev.y) != "cell" or self.tree.identify_column(ev.x) != "#1":
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
            q = f"🧪 Probelauf für {n} Produkt(e) {who}?\n\nDie App meldet an und füllt alles aus, schickt aber NICHTS ab. Am Ende siehst du einen Bericht."
        else:
            q = (f"{n} Bewerbung(en) automatisch abschicken {who}?\n\nDie App meldet an, füllt das Formular mit den gespeicherten Daten aus, "
                 "akzeptiert die Teilnahmebedingungen und den Datenschutzhinweis im Namen der jeweiligen Person und schickt ab.")
        if not messagebox.askyesno(APP_NAME, q):
            return
        self.busy(True)
        self.worker.submit("queue", items)

    def set_progress(self, n, total, label):
        self.lbl_progress.configure(text=f"{min(n + 1, total)}/{total}  {label}" if n < total else "✅ Fertig")

    # ---------- Bewerbungen ----------
    def app_selected(self):
        sel = self.atree.selection()
        if not sel:
            return
        o = self.me().get("info", {}).get(sel[0]) or {}
        self.var_result.set({"yes": "🎉 Ausgewählt", "no": "Nicht ausgewählt"}.get(o.get("result"), "⏳ Noch offen"))
        due = o.get("due", "")
        self.var_due.set(f"{due[8:10]}.{due[5:7]}.{due[:4]}" if len(due) == 10 else "")

    def save_result(self):
        sel = self.atree.selection()
        if not sel:
            return
        res = {"🎉 Ausgewählt": "yes", "Nicht ausgewählt": "no"}.get(self.var_result.get(), "")
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
    def select_person(self):
        label = self.var_person.get()
        for p in self.cfg["persons"]:
            if p["label"] == label:
                self.set_cfg("active", p["id"])
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
        messagebox.showinfo(APP_NAME, "✅ Daten gespeichert.")

    def save_account(self, key):
        e, p, lbl, st = self.acc[key]
        with self.cfg_lock:
            a = self.me().setdefault("accounts", {}).setdefault(key, {})
            a["email"] = e.get().strip()
            if p.get():
                enc = encrypt_pw(p.get())
                if not enc:
                    messagebox.showwarning(APP_NAME, "Passwort konnte nicht verschlüsselt werden.")
                    return
                a["pw"] = enc
            if not a["email"] and not p.get():
                self.me()["accounts"].pop(key, None)
            save_config(self.cfg)
        self.worker.submit("reset", self.cfg["active"])  # Browser der Person mit neuen Zugangsdaten neu starten
        self.render_profile()
        st.configure(text="✅ gespeichert")

    def open_browser(self, url):
        self.worker.submit("open", self.cfg["active"], url, None)

    def check_login(self):
        for _e, _p, _l, st in self.acc.values():
            st.configure(text="⏳ prüfe …")
        self.busy(True)
        self.worker.submit("loginstate", self.cfg["active"])

    def show_loginstate(self, res):
        for k, v in res.items():
            self.acc[k][3].configure(text={1: "✅ angemeldet", 0: "❌ nicht angemeldet"}.get(v, "❔ unklar"))

    # ---------- Einstellungen ----------
    def set_cfg(self, key, val):
        with self.cfg_lock:
            self.cfg[key] = val
            save_config(self.cfg)

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
        self.lbl_dc.configure(text="⏳ sende …")

        def go():
            code = engine.discord_send(url, "🦊 Fox Testpilot (Windows) ist verbunden", "Ab jetzt kommen die Meldungen auch vom PC hier an.",
                                       engine.SHOPS[0]["url"], self.cfg.get("discordPing", False))
            self.post(lambda: self.lbl_dc.configure(text="✅ gesendet – angekommen?" if 200 <= code < 300 else f"❌ Fehler {code}"))

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
        self.var_cn.set("")
        self.var_cu.set("")
        self.var_ck.set("")
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

    def import_backup(self):
        win = tk.Toplevel(self.root)
        win.title("Sicherung einspielen")
        win.geometry("620x420")
        ttk.Label(win, text="Sicherung aus der Android-App hier einfügen (Strg+V):").pack(anchor="w", padx=12, pady=(12, 4))
        tx = tk.Text(win, wrap="word", font=("Consolas", 9))
        tx.pack(fill="both", expand=True, padx=12)

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
            self.render_all()
            messagebox.showinfo(APP_NAME, f"✅ {n} Person(en) übernommen. Bitte unter „Personen“ die Passwörter neu eintragen.")

        ttk.Button(win, text="Einspielen", style="Accent.TButton", command=go).pack(pady=10)

    def export_backup(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(engine.export_backup(self.cfg))
        messagebox.showinfo(APP_NAME, "📋 Sicherung (ohne Passwörter) in die Zwischenablage kopiert.")

    def diagnose(self):
        self.busy(True)
        self.log("Diagnose läuft (bis zu 1,5 Minuten) …")
        self.worker.submit("diagnose")

    def show_text(self, title, text):
        win = tk.Toplevel(self.root)
        win.title(title)
        win.geometry("760x620")
        tx = tk.Text(win, wrap="word", font=("Consolas", 9))
        tx.insert("1.0", text)
        tx.pack(fill="both", expand=True, padx=10, pady=10)

        def copy_text():
            self.root.clipboard_clear()
            self.root.clipboard_append(text)

        ttk.Button(win, text="📋 Kopieren", style="Accent.TButton", command=copy_text).pack(pady=(0, 10))

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
            from PIL import Image
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
                self.post(lambda: (self.upd.configure(text=f"⬆️ Version 1.{best[0]} installieren"), self.upd.pack(side="right", padx=6)))
                self.log(f"Neue Version 1.{best[0]} verfügbar")
        except Exception as e:  # noqa: BLE001
            self.log(f"Update-Prüfung: {e}")

    def install_update(self):
        if not self.update_url:
            return
        if not getattr(sys, "frozen", False):
            webbrowser.open(self.update_url)
            return
        self.upd.configure(text="⏳ Lade Update …")

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
