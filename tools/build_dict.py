"""Turkce kelime sikligi sozlugu uretici.
Kullanim: py -3.12 -I tools\build_dict.py
Kaynak: Hermit Dave FrequencyWords (OpenSubtitles 2018), CC BY-SA 4.0.
Ham veri tools/_indirilen/ altina indirilir (yalniz veri olarak okunur).
"""
import os, re, sys, urllib.request, datetime

BASE = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/tr/"
HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "_indirilen")
OUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "assets", "dict")
MAX_LINES = 60000
ALLOWED = set("abcçdefgğhıijklmnoöprsştuüvyzâîû")  # q w x yok
ONE_LETTER_OK = {"a", "e", "o"}
REPEAT = re.compile(r"(.)\1\1")  # uc ayni harf ust uste: gurultu (aaa, hmmm)


def tr_lower(s):
    return s.replace("I", "ı").replace("İ", "i").lower()


def tr_upper(s):
    return s.replace("i", "İ").replace("ı", "I").upper()


# Kaynak (FrequencyWords tr) buyuk/kucuk bilgisini guvenilir tasimiyor: neredeyse tum
# kelimeler kucuk harfli; yalniz "İ" ile baslayanlar buyuk kalmis (Türkiye/Ankara/Ahmet
# kucuk, ama "İyileşeceksin", "İik" gibi cumle basi artefaktlari buyuk). Esik kurali
# uygulanirsa tutarsiz ve hatali sonuc cikar, bu yuzden varsayilan kapali.
USE_SOURCE_CASE = False
CASE_THRESHOLD = 0.60  # baskin bicim payi bu esigi gecerse o yazim tercih edilir


def preferred_form(low, forms):
    """forms: {yuzey_bicim: sayi}. Baskin Baslik/BUYUK bicim >= %%60 ise onu dondurur."""
    if not USE_SOURCE_CASE:
        return low
    total = sum(forms.values())
    title = tr_upper(low[0]) + low[1:]
    if len(low) >= 2:
        if forms.get(tr_upper(low), 0) >= CASE_THRESHOLD * total:
            return tr_upper(low)
        if forms.get(title, 0) >= CASE_THRESHOLD * total:
            return title
    return low


def fetch(name):
    os.makedirs(RAW, exist_ok=True)
    path = os.path.join(RAW, name)
    if not os.path.exists(path):
        print("indiriliyor:", name)
        urllib.request.urlretrieve(BASE + name, path)
    return path


def valid(w):
    if not w or any(c not in ALLOWED for c in w):
        return False
    if len(w) == 1 and w not in ONE_LETTER_OK:
        return False
    if REPEAT.search(w):
        return False
    return True


def load(path, counts):
    with open(path, encoding="utf-8", errors="strict") as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2 or not parts[1].isdigit():
                continue
            n = int(parts[1])
            w = tr_lower(parts[0])
            if n > 0 and valid(w):
                d = counts.setdefault(w, {})
                d[parts[0]] = d.get(parts[0], 0) + n


def main():
    counts = {}
    load(fetch("tr_50k.txt"), counts)
    if len(counts) < MAX_LINES:
        counts = {}
        load(fetch("tr_full.txt"), counts)
    totals = [(low, sum(f.values())) for low, f in counts.items()]
    totals.sort(key=lambda kv: (-kv[1], kv[0]))
    items = [(preferred_form(low, counts[low]), n) for low, n in totals[:MAX_LINES]]
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "tr.txt"), "w", encoding="utf-8", newline="\n") as f:
        for w, n in items:
            f.write(f"{w}\t{n}\n")
    print("yazildi:", len(items), "satir")


if __name__ == "__main__":
    main()
