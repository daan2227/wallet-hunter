package com.hunter.btc

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

/**
 * Pantalla de ayuda: explica el flujo de la auditoría de claves débiles.
 *
 * Se entra desde More → Help. Es solo texto (sin motor ni permisos), así que
 * no hace falta más que pintar secciones sobre un ScrollView.
 */
class HelpActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        AppTheme.init(this)
        AppLock.init(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.BG_DEEP)
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Help"
            textSize = 24f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.title(context)
        })
        root.addView(TextView(this).apply {
            text = "What each part of the app does"
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.medium(context)
            setPadding(0, dp(4), 0, dp(4))
        })

        seccion(root, "Scanner")
        parrafo(root, "Brute-forces keys against a loaded address dataset. Two modes: " +
            "BIP39 (generates seed phrases and derives their addresses) or Direct key " +
            "(raw private keys, ~1,400× faster). It derives each candidate's addresses and " +
            "checks them against the dataset index (Dataset → Load a .bin file). Threads and " +
            "CPU limit cap how hard it pushes; Derivation paths picks which BIP paths to try. " +
            "Shows keys/s and an ETA.")

        seccion(root, "Puzzle")
        parrafo(root, "Solves the Bitcoin puzzle addresses, whose private key sits in a known " +
            "range. Pick a puzzle #N and it scans that range (progress in blocks, jump to a " +
            "random point, reset). If the puzzle's public key is published it switches to " +
            "Kangaroo over the From–To range, finding it in √ of the range. It also checks " +
            "the balance and whether the public key is published.")

        seccion(root, "Wallet")
        parrafo(root, "Your wallets: create or import them, see balances (checked when the " +
            "screen opens, or with Check balances), and keep the key safe under Safekeeping. " +
            "Deleting a wallet warns you with its name and last known balance. Finds you " +
            "decide to keep move here.")

        seccion(root, "Recovery")
        parrafo(root, "Recovers a seed with missing words. Type the words you remember and mark " +
            "each gap with ?; it tries the combinations for the blanks until a derived address " +
            "matches, then lets you add it to your wallets. It shows how many combinations " +
            "there are before you start, so you know if it's feasible.")

        seccion(root, "Weak-key audit")

        // La idea de fondo, en la tarjeta de aviso (naranja) porque es el
        // matiz que más se malinterpreta: esto NO rompe claves normales.
        tarjeta(root, AppTheme.WARN,
            "Kangaroo needs the public key AND a range where the private key lives. " +
            "For a normal address the range is all of [1, 2^256] — impossible. The only " +
            "thing you can audit is a badly generated key: one whose private value came " +
            "from too little entropy and landed in a small range. A properly generated " +
            "key will never show up here. Use it on your own addresses, or a system you audit.")

        seccion(root, "1 · Input")
        parrafo(root, "Paste public keys (02…/03…/04…) or spent addresses (1…/3…/bc1…), " +
            "one per line — or tap Load CSV / text file. The file is read line by line and " +
            "every key/address in any column is pulled out (headers, quotes and extra " +
            "columns are ignored), without duplicates.")
        parrafo(root, "• Up to 2,000 entries go into the editor.\n" +
            "• More than that switch to large-list mode: kept in memory with just a summary " +
            "(“file · N keys loaded”), so the editor stays responsive.")

        seccion(root, "2 · Settings")
        parrafo(root, "• Search range (20–80 bits, suggested 50): how far it looks. It only " +
            "finds keys whose private value is below 2^bits.\n" +
            "• Budget × √ (2–4): margin over √(2^bits). Higher = surer but slower.\n" +
            "• Max seconds per key (0 = no limit): cuts a key that drags on and moves to the next.")
        parrafo(root, "The orange line shows, live: operations per key, time per key at your " +
            "device’s measured speed, and the batch ETA (number of keys × time per key).")

        seccion(root, "3 · Run")
        parrafo(root, "Start audit builds the queue (editor + large list, deduped) and works " +
            "one key at a time:")
        parrafo(root, "• A public key (02/03/04) is used directly.\n" +
            "• An address is resolved to its public key over the network — which only exists " +
            "if the address has spent; if it can’t be found, it’s skipped.\n" +
            "• Kangaroo then runs over [1, 2^bits) with budget c·√(2^bits) on all cores.")
        parrafo(root, "Every second it checks whether the key surfaced, the budget ran out, " +
            "or the per-key time cap was hit — then moves on.")

        seccion(root, "4 · Performance card")
        parrafo(root, "While it runs: live speed with its peak, a trend chart, and four figures " +
            "(Operations · Time · Key budget % · Keys · found). It also measures and remembers " +
            "your device’s speed to make future ETAs accurate.")

        seccion(root, "5 · Results")
        parrafo(root, "Each hit is saved to the finds vault (with its WIF and address) and a " +
            "notification pops up. At the end: “Done: N checked · M found → in the finds vault.” " +
            "Review or delete finds from the vault.")

        // Resumen de una línea, en tarjeta de acento.
        tarjeta(root, AppTheme.ACCENT,
            "In short: paste or load keys → set range, budget and cap → Kangaroo runs key by " +
            "key over a small range → whatever it finds (badly generated keys) goes to the vault.")

        seccion(root, "Tools")
        parrafo(root, "More → Tools is a grouped screen of standalone utilities. It opens as " +
            "a tidy index: six collapsible categories (collapsed by default, tap a header to " +
            "open it) and a search box on top that filters tools by name. The categories are " +
            "Keys & addresses, Sign/encrypt/backup (BIP38, Shamir, PSBT…), Transactions, " +
            "Monitor & generate (Vanity for 1…/3…/bc1q…), Audit & analysis, and Utility.")

        seccion(root, "Audit & analysis")
        parrafo(root, "• Solve key in range (BSGS): resolves a public key whose private key " +
            "is in a small known range, deterministically.\n" +
            "• Nonce-reuse audit: scans a spent address; if it ever signed with the same r " +
            "(reused nonce) the private key falls out — and if not, it tries the lattice attack.\n" +
            "• Nonce-leak recovery (lattice): recovers a key when several signatures from the " +
            "same key used short or biased nonces (Hidden Number Problem + LLL).\n" +
            "• RFC6979 nonce check: tells whether a signature used the safe deterministic nonce " +
            "or a random one (a weak RNG is how keys leak).\n" +
            "• Signature low-S / malleability: flags high-S (malleable) and non-strict DER.\n" +
            "• Brainwallet check: tests passphrases (and ~60× variations) against targets, or " +
            "lists their addresses.\n" +
            "• Mnemonic strength: low-entropy red flags in a BIP39 phrase.")
        tarjeta(root, AppTheme.WARN,
            "The audits recover keys only from genuinely weak signing or weak generation " +
            "(reused/short nonces, bad RNG, guessable phrases). They cannot break a properly " +
            "generated key. Use them on your own addresses, or a system you audit.")

        seccion(root, "Finds vault")
        parrafo(root, "Every hit — from the scanner, a puzzle or the weak-key audit — lands " +
            "here with its WIF and address. From a find you can check its balance, move it to " +
            "your wallets, or delete it. It's encrypted on the device.")

        seccion(root, "Security and the PIN")
        parrafo(root, "You choose a 6-digit PIN. The app locks when the screen turns off or " +
            "when you leave it for more than 30 seconds, and asks for the PIN again.")
        parrafo(root, "Seeds are sealed twice: by the phone's secure hardware and by a key " +
            "derived from your PIN. That key lives only in memory while you are in the app and " +
            "is wiped when it locks, so after a lock or a restart you type the PIN again before " +
            "any seed can be read. The fingerprint is not used: it can't provide the PIN.")
        parrafo(root, "Too many wrong PINs lock the whole app behind a countdown (30 s, " +
            "doubling with each extra failure, up to 1 h). A correct PIN is not checked during " +
            "the wait, and changing the phone's date doesn't skip it.")
        parrafo(root, "Screenshots are blocked by default (More › Privacy): the app shows black " +
            "in recent apps. A private key copied from the vault or the key tools is marked " +
            "sensitive and erased from the clipboard after 60 s. Backups use their own password " +
            "of at least 12 characters, and a strength meter rejects guessable ones.")
        parrafo(root, "A send of 0.01 BTC or more asks for the PIN again just before signing; " +
            "change that amount in More › Profile (empty = never ask, 0 = always ask).")
        parrafo(root, "Change the PIN in More › Profile. It re-seals every seed with the new " +
            "code in one step: both change or neither does. The PIN can't be recovered, so keep " +
            "your seed phrase written down. WIF keys, watch-only wallets and the finds vault are " +
            "protected by the secure hardware only, because the scanner saves finds while the " +
            "app is locked.")

        seccion(root, "More")
        parrafo(root, "Notifications toggles the find alert, vibration and the coin sound " +
            "on a find, each on its own. Appearance switches light/dark. Debug shows the " +
            "engine log and files. And this Help screen. (Scanner, Puzzle, Wallet, Recovery " +
            "and Weak-key each have their own tab in the bottom bar.)")

        setContentView(scroll)
    }

    private fun seccion(root: LinearLayout, t: String) {
        root.addView(TextView(this).apply {
            text = t; textSize = AppTheme.SP_TITLE; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.bold(context)
            setPadding(0, dp(22), 0, dp(8))
        })
    }

    private fun parrafo(root: LinearLayout, t: String) {
        root.addView(TextView(this).apply {
            text = t; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(8))
        })
    }

    /** Bloque destacado con una barra de color a la izquierda. */
    private fun tarjeta(root: LinearLayout, color: Int, t: String) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        fila.addView(android.view.View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginEnd = dp(14) }
        })
        fila.addView(TextView(this).apply {
            text = t; textSize = AppTheme.SP_CAPTION + 1f; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.body(context); setLineSpacing(0f, 1.4f)
            gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(fila)
    }
}
