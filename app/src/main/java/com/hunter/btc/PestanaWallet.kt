package com.hunter.btc

import android.app.*
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import java.io.File
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import java.io.*
import com.hunter.btc.recovery.RecoveryEngine
import com.hunter.btc.recovery.RecoveryParser
import com.hunter.btc.recovery.ParseResult
import com.hunter.btc.MainActivity.PuzzleInfo

/*
 * La pestana Wallet: saldo de hallazgos y acceso a las carteras.
 *
 * Es una funcion de extension de MainActivity: estaba dentro de ese fichero,
 * que pasaba de 6.000 lineas. El cuerpo no cambia.
 */

internal fun MainActivity.buildWalletTab(): ScrollView {
    val ACCENT  = AppTheme.ACCENT
    val ACCENT2 = AppTheme.BLUE

    val scroll = ScrollView(this).apply {
        setBackgroundColor(AppTheme.BG_DEEP)
        visibility = android.view.View.GONE
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(AppTheme.BG_DEEP)
        setPadding(dp(AppTheme.PAD_SIDE), 0, dp(AppTheme.PAD_SIDE), dp(80))
    }
    // El título lo decía la cabecera, que ya no existe.
    page.addView(Ui.pageTitle(this, "Wallet", lados = false))

    // ── SALDO ─────────────────────────────────────────────────────────
    //
    // Estaba metido en una tarjeta con borde y centrado. La tarjeta no
    // separaba nada de nada —era lo único en su zona— y el centrado
    // rompía la columna de lectura con todo lo de abajo alineado a la
    // izquierda. Aquí la cifra va suelta sobre el fondo, como en el resto
    // del sistema.
    val heroCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(26) }
    }

    // El total real, para destaparlo con el ojo sin volver a leer el baúl.
    var totalReal = "0,00000000"
    val filaRotulo = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    filaRotulo.addView(TextView(this).apply {
        text = "Balance in finds"
        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })
    heroCard.addView(filaRotulo)
    // El verde estaba fijo, así que un saldo de cero se pintaba igual que
    // uno con fondos. Ahora el acento significa "hay algo"; lo pone
    // refreshWallet según el total.
    val tvTotalBtc = TextView(this).apply {
        text = "0,00000000"
        textSize = AppTheme.SP_DISPLAY; setTextColor(AppTheme.TXT_PRI)
        typeface = AppTheme.display(context)
        letterSpacing = -0.04f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    val tvTotalUsd = TextView(this).apply {
        text = "No key found yet"
        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }
    heroCard.addView(tvTotalBtc)
    heroCard.addView(tvTotalUsd)
    page.addView(heroCard)
    // Ocultar saldos: el mismo ajuste que en la cartera. Dentro del baúl
    // no se aplica —ver Privacidad—.
    filaRotulo.addView(Privacidad.ojo(this) {
        tvTotalBtc.text = Privacidad.monto(this, totalReal)
    })
    tvTotalBtc.text = Privacidad.monto(this, totalReal)

    // Leer los hallazgos del baúl y calcular total.
    //
    // Antes esto leía coincidencias.txt filtrando por líneas que empezaran
    // con "MATCH|", pero save_match() nunca escribe ese prefijo en el
    // fichero —lo usa sólo para la lista en memoria—: las líneas empiezan
    // por SEED:, PRIV: o RAW:. Así que el total salía siempre en 0 aunque
    // hubiera aciertos guardados.
    // Corre siempre en segundo plano (refreshWallet la llama desde un Thread).
    //
    // consultarRed sólo va a true cuando el usuario pulsa "Actualizar
    // Balance". Al construir la pestaña iba a true sin más, así que abrir
    // Wallet mandaba todas las direcciones encontradas a mempool.space sin
    // que nadie lo hubiera pedido: preguntar por una dirección se la revela
    // a quien responde, y eso delata que este dispositivo tiene la clave.
    fun loadCoincidencias(consultarRed: Boolean): Pair<Double, List<Triple<String,Double,String>>> {
        MatchVault.recoger(this@buildWalletTab)
        // Los hallazgos de Kangaroo de antes del arreglo se guardaron sólo
        // con la clave, y el baúl lista por dirección: salían en blanco.
        // Se completan al abrirlo, que es cuando importa verlos.
        try { MatchVault.completarClaves(this@buildWalletTab) } catch (e: Exception) {}
        if (consultarRed) {
            try { MatchVault.resolvePendingBalances(this@buildWalletTab) } catch (e: Exception) {}
        }
        val entries = MatchVault.list(this@buildWalletTab)
        return Pair(entries.sumOf { it.btc },
                    entries.map { Triple(it.addr, it.btc, it.wif) })
    }

    fun refreshWallet(consultarRed: Boolean = false) {
        Thread {
            val (total, matches) = loadCoincidencias(consultarRed)
            val pendientes = MatchVault.pendingBalance(this@buildWalletTab)
            // Si no queda ninguno por consultar, es que la consulta llegó.
            // Si quedan Y se había pedido red, es que no hubo respuesta:
            // decirlo es la diferencia entre "está vacío" y "no lo sé".
            val sinRed = consultarRed && pendientes > 0
            runOnUiThread {
                totalReal = "%.8f".format(total).replace('.', ',')
                tvTotalBtc.text = Privacidad.monto(this@buildWalletTab, totalReal)
                // El acento sólo cuando de verdad hay saldo. Pintar de verde
                // un cero es lo mismo que no pintar nada.
                tvTotalBtc.setTextColor(
                    if (total > 0.0) AppTheme.ACCENT else AppTheme.TXT_PRI)
                tvTotalUsd.text = when {
                    matches.isEmpty()  -> "No key found yet"
                    sinRed             -> "${matches.size} find(s) · offline, " +
                                          "$pendientes not checked"
                    // Un total que suma ceros sin consultar no es un saldo:
                    // decir "0,00000000" a secas afirma que están vacías.
                    pendientes > 0     -> "${matches.size} find(s) · $pendientes not checked"
                    else               -> "${matches.size} find(s)"
                }
            }
        }.start()
    }
    // Consulta automática al abrir la pestaña.
    //
    // Ojo con lo que implica, porque antes era justo al revés a propósito:
    // preguntar por un saldo revela esa dirección al servidor que responde,
    // y en un hallazgo eso delata que este dispositivo tiene la clave. Se
    // hace automático porque lo has pedido; el aviso de abajo se ha
    // reescrito para que diga la verdad de lo que pasa ahora.
    refreshWallet(consultarRed = true)

    // ── DOS ACCIONES PRIMARIAS, GRANDES ───────────────────────────────
    //
    // Eran seis filas idénticas en fila india: nada decía cuáles son las
    // dos que vas a usar siempre y cuáles el resguardo que miras una vez al
    // mes. Las dos primeras pasan a tarjetas grandes, y "View wallet" va
    // rellena con el acento porque es la que abres el 90 % de las veces.
    fun bigCard(icon: Int, label: String, sub: String, primary: Boolean,
                last: Boolean, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.cardBg(AppTheme.R_CARD,
            if (primary) AppTheme.ACCENT else AppTheme.BG_CARD, context)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        isClickable = true; isFocusable = true
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { if (!last) marginEnd = dp(AppTheme.GAP) }
        addView(android.widget.ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(if (primary) AppTheme.BG_DEEP else AppTheme.ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        })
        addView(TextView(context).apply {
            text = label
            textSize = AppTheme.SP_BODY
            setTextColor(if (primary) AppTheme.BG_DEEP else AppTheme.TXT_PRI)
            typeface = AppTheme.bold(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        })
        addView(TextView(context).apply {
            text = sub
            textSize = AppTheme.SP_MICRO
            setTextColor(if (primary) 0xA8000000.toInt() else AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, dp(2), 0, 0)
        })
    }

    // Va ANTES que abrirCartera porque ésta la llama: en Kotlin una función
    // local sólo ve las que se han declarado por encima.
    fun anadirCartera() {
        // "Create" primero: es lo que busca quien no tiene ninguna. Antes
        // sólo se podía importar una seed que ya existiera.
        val opciones = arrayOf(
            "Create a new wallet",
            "Import a seed phrase (BIP39)",
            "Import a WIF key",
            "Watch an address (read-only)")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add a wallet")
            .setItems(opciones) { _, which ->
                startActivity(Intent(this, WalletActivity::class.java).apply {
                    putExtra("MODE", when (which) {
                        0 -> "create"; 2 -> "wif_import"; 3 -> "watch_import"; else -> "setup"
                    })
                })
            }
            .show()
    }

    fun abrirCartera() {
        // Cualquier cartera cuenta. Antes sólo miraba la seed principal y
        // los WIF, así que quien sólo tuviera otra seed o una dirección
        // vigilada recibía "No wallet yet" teniéndolas.
        val hayAlguna = WalletManager.hasSeed(this) ||
                        WalletManager.listWallets(this).isNotEmpty() ||
                        WalletManager.listWifs(this).isNotEmpty() ||
                        WalletManager.listWatchers(this).isNotEmpty()
        if (hayAlguna) {
            // SIN "MODE". Con MODE="seed" se abría siempre la cartera
            // principal y el selector no llegaba a salir: no había forma
            // de elegir otra desde aquí. Sin modo, WalletActivity enseña
            // la lista, y si sólo hay una entra directo a ella.
            val ir = {
                startActivity(Intent(this, WalletActivity::class.java))
            }
            if (!PinAuthHelper.isSessionValid())
                PinAuthHelper.show(this) { ok -> if (ok) ir() }
            else ir()
        } else {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("No wallet yet")
                .setMessage("Want to add one?")
                // "Add" iba directo a escribir una seed. Ahora abre la misma
                // elección que la tarjeta "Add": crear, seed, WIF o vigilar.
                .setPositiveButton("Add") { _, _ -> anadirCartera() }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    page.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) }
        addView(bigCard(R.drawable.ic_wallet, "View wallet", "Balances and addresses",
            primary = true, last = false) { abrirCartera() })
        addView(bigCard(R.drawable.ic_add, "Add", "Seed, WIF or address",
            primary = false, last = true) { anadirCartera() })
    })

    // ── RESGUARDO ─────────────────────────────────────────────────────
    page.addView(TextView(this).apply {
        text = "Safekeeping"
        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.medium(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    })

    val guardCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.cardBg(ctx = this@buildWalletTab)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(22) }
    }
    fun guardRow(icon: Int, label: String, sub: String, primero: Boolean,
                 click: () -> Unit): Pair<TextView, TextView> {
        if (!primero) guardCard.addView(android.view.View(this).apply {
            setBackgroundColor(AppTheme.BORDER_C)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { marginStart = dp(18); marginEnd = dp(18) }
        })
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            isClickable = true; isFocusable = true
            setOnClickListener { click() }
        }
        r.addView(Ui.icon(this, icon, 20).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = label; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.body(context)
        })
        val subTv = TextView(this).apply {
            text = sub; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setPadding(0, dp(2), 0, 0)
        }
        col.addView(subTv)
        r.addView(col)
        // El estado a la derecha: cuántos hay, de cuándo es la última. Sin
        // esto hay que entrar en cada uno para saber si tienes algo.
        val estado = TextView(this).apply {
            text = ""
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
        }
        r.addView(estado)
        r.addView(Ui.icon(this, R.drawable.ic_chevron, 16, AppTheme.TXT_MUTED).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        })
        guardCard.addView(r)
        return estado to subTv
    }

    val (estVault, _) = guardRow(R.drawable.ic_vault, "Finds vault",
                            "Encrypted, separate from your wallets", primero = true) {
        // El baúl pide el PIN él mismo, siempre: ver VaultActivity.
        startActivity(Intent(this, VaultActivity::class.java))
    }
    val (estBackup, subBackup) = guardRow(R.drawable.ic_lock, "Backups",
                             "Create, view, share or restore", primero = false) {
        if (!PinAuthHelper.isSessionValid()) PinAuthHelper.show(this) { ok -> if (ok) exportEncryptedBackup() }
        else exportEncryptedBackup()
    }
    guardRow(R.drawable.ic_export, "Export summary",
             "No private keys", primero = false) { exportLog() }
    page.addView(guardCard)

    // El estado de las dos filas, en segundo plano: leer el baúl y listar
    // los ficheros de copia es I/O, y esto corre al construir la pestaña.
    Thread {
        val hallazgos = try { MatchVault.list(this@buildWalletTab).size } catch (e: Exception) { 0 }
        val copias = try { BackupStore.list(this@buildWalletTab) } catch (e: Exception) { emptyList() }
        val ultima = copias.firstOrNull()?.createdAt ?: 0L
        runOnUiThread {
            estVault.text = if (hallazgos == 0) "Empty" else "$hallazgos"
            estBackup.text = if (copias.isEmpty()) "None" else "${copias.size}"
            if (ultima > 0) {
                val dias = ((System.currentTimeMillis() - ultima) / 86_400_000L).toInt()
                subBackup.text = when (dias) {
                    0    -> "Last today"
                    1    -> "Last yesterday"
                    else -> "Last $dias days ago"
                }
            }
        }
    }.start()

    // ── POR QUÉ NO SE CONSULTAN SOLOS ─────────────────────────────────
    page.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x12FF6B35); cornerRadius = dp(AppTheme.R_CARD).toFloat()
        }
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addView(android.widget.ImageView(this@buildWalletTab).apply {
            setImageResource(R.drawable.ic_warning)
            setColorFilter(AppTheme.WARN)
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                marginEnd = dp(11)
            }
        })
        addView(TextView(this@buildWalletTab).apply {
            text = "Balances are checked automatically when this screen opens. " +
                   "That reveals your addresses to whichever server answers: it is the " +
                   "price of seeing them without asking."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.body(context)
            setLineSpacing(0f, 1.55f)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    })

    // ── CONSULTAR SALDOS ──────────────────────────────────────────────
    //
    // Era una fila más entre las seis, indistinguible de "Exportar
    // resumen". Es la única acción de la pantalla que sale a la red, y va
    // sola abajo, después del aviso que explica por qué no es automática.
    page.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_KEY, context)
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(18) }
        setOnClickListener {
            refreshWallet(consultarRed = true)
            android.widget.Toast.makeText(this@buildWalletTab, "Querying the chain…",
                android.widget.Toast.LENGTH_SHORT).show()
        }
        addView(Ui.icon(this@buildWalletTab, R.drawable.ic_refresh, 16).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
        })
        addView(TextView(this@buildWalletTab).apply {
            text = "Check balances"
            textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI)
            typeface = AppTheme.medium(context)
        })
    })

    scroll.addView(page)
    return scroll
}
