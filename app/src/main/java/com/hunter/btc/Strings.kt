package com.hunter.btc

/**
 * Los textos de la pantalla principal.
 *
 * Había ocho tablas —ES, EN, JA, KO, DE, FR, RU, PT— y un selector de idioma
 * que dejó de existir: `s` se fija a EN y fromSystem() no lo llamaba nadie,
 * así que siete tablas eran inalcanzables. Y ni siquiera traducían la app:
 * cubren unos cincuenta textos de la pantalla principal, y el resto de la
 * interfaz —cartera, baúl, cluster, diálogos— está escrito en inglés en su
 * sitio. Se queda la inglesa, que es la única que se usa.
 */
object Strings {
    data class Lang(
        val title: String, val subtitle: String,
        val csvSection: String, val csvBtn: String, val noFile: String,
        val configSection: String, val threads: String, val cpuLimit: String,
        val silent: String, val balanced: String, val performance: String,
        val start: String, val stop: String, val statsSection: String,
        val liveSection: String, val waitingStart: String,
        val matchSection: String, val noMatch: String, val logSection: String,
        val loadFirst: String, val copying: String,
        val permTitle: String, val permMsg: String,
        val notifMatchTitle: String, val notifStatusTitle: String,
        val speed: String, val seeds: String, val time: String,
        val matches: String, val ram: String, val temp: String,
        val language: String, val langBtn: String,
        val modeSection: String, val modeBip39: String, val modePuzzle: String,
        val puzzleSelect: String, val rangeStart: String, val rangeEnd: String,
        val targetAddr: String, val enterRange: String, val enterTarget: String,
        val loading: String, val errorPrefix: String, val footer: String,
        val scanned: String, val elapsed: String
    )

    val EN = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | arm64",
        csvSection="DATASET", csvBtn="Load Dataset", noFile="No file loaded",
        configSection="PERFORMANCE", threads="Threads", cpuLimit="CPU Limit",
        silent="silent", balanced="balanced", performance="performance",
        start="Start", stop="Stop", statsSection="STATISTICS",
        liveSection="LIVE SCAN", waitingStart="Waiting to start...",
        matchSection="MATCHES", noMatch="None yet...", logSection="SYSTEM",
        loadFirst="Load dataset first", copying="Copying dataset...",
        permTitle="Permission needed", permMsg="You need all files access.",
        notifMatchTitle="WALLET FOUND", notifStatusTitle="BTC Hunter",
        speed="Speed", seeds="Seeds", time="Time", matches="Matches",
        ram="RAM", temp="Temp", language="Language", langBtn="Language",
        modeSection="MODE", modeBip39="Seed Scan", modePuzzle="Puzzle (hex range)",
        puzzleSelect="Select Puzzle:", rangeStart="Range Start (hex):", rangeEnd="Range End (hex):",
        targetAddr="Target Address:", enterRange="Enter hex range", enterTarget="Enter target address",
        loading="Loading", errorPrefix="Error", footer="Property of Dax2201 | Optimized by Claude",
        scanned="Scanned", elapsed="Elapsed"
    )
}
