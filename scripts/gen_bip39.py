import urllib.request

BASE = "https://raw.githubusercontent.com/trezor/python-mnemonic/master/src/mnemonic/wordlist"
LANGS = {"EN":"english","ES":"spanish","FR":"french","JA":"japanese","KO":"korean","PT":"portuguese"}
out = "app/src/main/java/com/hunter/btc/Bip39Words.kt"

kt = "package com.hunter.btc\n\nobject Bip39Words {\n"
ok_langs = []
for lang, fname in LANGS.items():
    try:
        data = urllib.request.urlopen(f"{BASE}/{fname}.txt", timeout=15).read().decode("utf-8")
        words = [w.strip() for w in data.split("\n") if w.strip() and not w.startswith("#")]
        chunks = [words[i:i+8] for i in range(0, len(words), 8)]
        kt += f"\n    val {lang} = arrayOf(\n"
        for chunk in chunks:
            kt += "        " + ", ".join(f'"{w}"' for w in chunk) + ",\n"
        kt += "    )\n"
        ok_langs.append(lang)
        print(f"{lang}: {len(words)} words OK")
    except Exception as e:
        print(f"{lang}: FAILED {e}")

kt += "\n    val WORDS get() = EN\n"
kt += "\n    fun forLang(lang: String) = when(lang) {\n"
for lang in ok_langs:
    kt += f'        "{lang}" -> {lang}\n'
kt += "        else -> EN\n    }\n}\n"

open(out, "w").write(kt)
print(f"Written {len(ok_langs)} languages")
