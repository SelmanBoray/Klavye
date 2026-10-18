"""Unicode emoji-test.txt (15.1) -> EmojiData.kt uretici.

Calistirma:  py -3.12 -I tools/gen_emoji.py
Ham veri: tools/_indirilen/emoji-test-15.1.txt (yoksa indirilir; yalniz veri olarak okunur).
"""
import os
import sys
import urllib.request

URL = "https://unicode.org/Public/emoji/15.1/emoji-test.txt"
HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "_indirilen", "emoji-test-15.1.txt")
OUT = os.path.join(HERE, "..", "app", "src", "main", "java", "com",
                   "selman", "klavye", "emoji", "EmojiData.kt")

# (kategori id, ikon, Unicode gruplari)
CATS = [
    ("yuzler", "\U0001F600", ["Smileys & Emotion", "People & Body"]),
    ("doga", "\U0001F436", ["Animals & Nature"]),
    ("yiyecek", "\U0001F354", ["Food & Drink"]),
    ("seyahat", "\U0001F697", ["Travel & Places"]),
    ("etkinlik", "⚽", ["Activities"]),
    ("nesneler", "\U0001F4A1", ["Objects"]),
    ("semboller", "❤️", ["Symbols"]),
    ("bayraklar", "\U0001F3F3️", ["Flags"]),
]
CHUNK_LIMIT = 20000  # bayt; 65535 sinirinin cok altinda


def load():
    if not os.path.exists(RAW):
        os.makedirs(os.path.dirname(RAW), exist_ok=True)
        with urllib.request.urlopen(URL, timeout=60) as r:
            data = r.read()
        with open(RAW, "wb") as f:
            f.write(data)
    with open(RAW, encoding="utf-8") as f:
        return f.read().splitlines()


def parse(lines):
    groups = {}
    group = None
    for ln in lines:
        if ln.startswith("# group:"):
            group = ln.split(":", 1)[1].strip()
            continue
        if not ln or ln.startswith("#") or ";" not in ln:
            continue
        cps, rest = ln.split(";", 1)
        status = rest.split("#", 1)[0].strip()
        if status != "fully-qualified" or group in (None, "Component"):
            continue
        cp = [int(x, 16) for x in cps.split()]
        if any(0x1F3FB <= c <= 0x1F3FF for c in cp):
            continue
        groups.setdefault(group, []).append("".join(chr(c) for c in cp))
    return groups


def kt_escape(s):
    out = []
    for ch in s:
        c = ord(ch)
        if ch == " ":
            out.append(" ")
        elif c > 0xFFFF:
            c -= 0x10000
            out.append("\\u%04X\\u%04X" % (0xD800 + (c >> 10), 0xDC00 + (c & 0x3FF)))
        else:
            out.append("\\u%04X" % c)
    return "".join(out)


def mutf8(s):
    """Class dosyasindaki Modified UTF-8 boyutu (astral = 2 vekil x 3 bayt)."""
    n = 0
    for ch in s:
        c = ord(ch)
        n += 1 if 0 < c < 0x80 else (2 if c < 0x800 else (3 if c < 0x10000 else 6))
    return n


def chunks(emojis):
    res, cur, size = [], [], 0
    for e in emojis:
        b = len(e.encode("utf-8")) + 1
        if cur and size + b > CHUNK_LIMIT:
            res.append(" ".join(cur) + " ")
            cur, size = [], 0
        cur.append(e)
        size += b
    if cur:
        res.append(" ".join(cur))
    return res


def main():
    groups = parse(load())
    lines = [
        "// tools/gen_emoji.py ile üretildi, elle düzenleme. Kaynak: Unicode emoji-test.txt 15.1",
        "package com.selman.klavye.emoji",
        "",
        "data class EmojiCategory(val id: String, val icon: String, val emojis: List<String>)",
        "",
        "object EmojiData {",
    ]
    counts, maxlit = [], 0
    for i, (cid, icon, gs) in enumerate(CATS):
        em = [e for g in gs for e in groups.get(g, [])]
        counts.append((cid, len(em)))
        parts = chunks(em)
        for p in parts:
            maxlit = max(maxlit, mutf8(p))
        lines.append("    private val E%d: String =" % i)
        for j, p in enumerate(parts):
            lines.append("        %s\"%s\"" % ("" if j == 0 else "+ ", kt_escape(p)))
    lines.append("")
    lines.append("    val categories: List<EmojiCategory> by lazy {")
    lines.append("        listOf(")
    for i, (cid, icon, gs) in enumerate(CATS):
        lines.append("            EmojiCategory(\"%s\", \"%s\", E%d.split(' ').filter { it.isNotEmpty() })%s"
                     % (cid, kt_escape(icon), i, "," if i < len(CATS) - 1 else ""))
    lines.append("        )")
    lines.append("    }")
    lines.append("}")
    text = "\n".join(lines) + "\n"
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    # Dogrulama: kaynaktaki her string literal'in UTF-8 bayti (kaçışlar çözülmüş hali)
    worst = maxlit
    print("en uzun literal (Modified UTF-8 bayt):", worst, "(sinir 65535)")
    for cid, n in counts:
        print(cid, n)
    if worst > 65535:
        sys.exit("HATA: literal cok uzun")


main()
