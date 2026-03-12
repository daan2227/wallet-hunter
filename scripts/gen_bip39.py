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
        # Split into chunks of 256 to avoid method too large
        # Use a function that builds the list lazily
        kt += f"\n    private fun words{lang}(): Array<String> {{\n"
        kt += f"        val w = ArrayList<String>({len(words)})\n"
        # Add in batches of 50 per statement
        for i in range(0, len(words), 50):
            batch = words[i:i+50]
            kt += "        w.addAll(listOf(" + ", ".join(f'"{w}"' for w in batch) + "))\n"
        kt += "        return w.toTypedArray()\n"
        kt += "    }\n"
        kt += f"    val {lang}: Array<String> by lazy {{ words{lang}() }}\n"
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
print(f"Written {len(ok_langs)} languages to {out}")
