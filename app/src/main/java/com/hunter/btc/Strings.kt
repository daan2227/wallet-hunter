package com.hunter.btc

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

    val ES = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | arm64",
        csvSection="DATASET", csvBtn="Cargar Dataset", noFile="Sin archivo",
        configSection="RENDIMIENTO", threads="Threads", cpuLimit="Limite CPU",
        silent="silencioso", balanced="balanceado", performance="rendimiento",
        start="Iniciar", stop="Detener", statsSection="ESTADISTICAS",
        liveSection="EN VIVO", waitingStart="Esperando inicio...",
        matchSection="COINCIDENCIAS", noMatch="Ninguna aun...", logSection="SISTEMA",
        loadFirst="Carga el dataset primero", copying="Copiando dataset...",
        permTitle="Permiso necesario", permMsg="Necesitas acceso a todos los archivos.",
        notifMatchTitle="WALLET ENCONTRADA", notifStatusTitle="BTC Hunter",
        speed="Velocidad", seeds="Seeds", time="Tiempo", matches="Matches",
        ram="RAM", temp="Temp", language="Idioma", langBtn="Idioma",
        modeSection="MODO", modeBip39="Seed Scan", modePuzzle="Puzzle (rango hex)",
        puzzleSelect="Seleccionar Puzzle:", rangeStart="Inicio (hex):", rangeEnd="Fin (hex):",
        targetAddr="Direccion Objetivo:", enterRange="Ingresa el rango hex", enterTarget="Ingresa la direccion objetivo",
        loading="Cargando", errorPrefix="Error", footer="Propiedad de Dax2201 | Optimizado por Claude",
        scanned="Escaneadas", elapsed="Tiempo"
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
    val JA = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | arm64",
        csvSection="データセット", csvBtn="データ読込", noFile="ファイルなし",
        configSection="設定", threads="スレッド", cpuLimit="CPU制限",
        silent="静音", balanced="バランス", performance="高性能",
        start="開始", stop="停止", statsSection="統計",
        liveSection="ライブ", waitingStart="開始を待機中...",
        matchSection="一致", noMatch="まだなし...", logSection="ログ",
        loadFirst="先にデータを読み込んでください", copying="コピー中...",
        permTitle="権限が必要", permMsg="全ファイルアクセスが必要です。",
        notifMatchTitle="ウォレット発見", notifStatusTitle="BTC Hunter",
        speed="速度", seeds="シード", time="時間", matches="一致数",
        ram="RAM", temp="温度", language="言語", langBtn="言語",
        modeSection="モード", modeBip39="Seed Scan", modePuzzle="パズル (16進)",
        puzzleSelect="パズル選択:", rangeStart="開始 (hex):", rangeEnd="終了 (hex):",
        targetAddr="ターゲット:", enterRange="16進範囲を入力", enterTarget="ターゲットを入力",
        loading="読込中", errorPrefix="エラー", footer="Dax2201 所有 | Claude 最適化",
        scanned="スキャン", elapsed="経過"
    )
    val KO = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | arm64",
        csvSection="데이터셋", csvBtn="데이터 로드", noFile="파일 없음",
        configSection="설정", threads="스레드", cpuLimit="CPU 제한",
        silent="조용", balanced="균형", performance="성능",
        start="시작", stop="중지", statsSection="통계",
        liveSection="실시간", waitingStart="시작 대기중...",
        matchSection="일치", noMatch="아직 없음...", logSection="로그",
        loadFirst="먼저 데이터를 로드하세요", copying="복사중...",
        permTitle="권한 필요", permMsg="전체 파일 접근이 필요합니다.",
        notifMatchTitle="지갑 발견", notifStatusTitle="BTC Hunter",
        speed="속도", seeds="시드", time="시간", matches="일치수",
        ram="RAM", temp="온도", language="언어", langBtn="언어",
        modeSection="모드", modeBip39="Seed Scan", modePuzzle="퍼즐 (16진)",
        puzzleSelect="퍼즐 선택:", rangeStart="시작 (hex):", rangeEnd="끝 (hex):",
        targetAddr="대상 주소:", enterRange="16진 범위 입력", enterTarget="대상 주소 입력",
        loading="로딩중", errorPrefix="오류", footer="Dax2201 소유 | Claude 최적화",
        scanned="스캔", elapsed="경과"
    )
    val DE = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | arm64",
        csvSection="DATENSATZ", csvBtn="Datensatz laden", noFile="Keine Datei",
        configSection="EINSTELLUNGEN", threads="Threads", cpuLimit="CPU-Limit",
        silent="leise", balanced="ausgewogen", performance="leistung",
        start="STARTEN", stop="STOPPEN", statsSection="STATISTIKEN",
        liveSection="LIVE", waitingStart="Warte auf Start...",
        matchSection="TREFFER", noMatch="Noch keine...", logSection="LOG",
        loadFirst="Datensatz zuerst laden", copying="Wird kopiert...",
        permTitle="Berechtigung erforderlich", permMsg="Zugriff auf alle Dateien benoetigt.",
        notifMatchTitle="WALLET GEFUNDEN", notifStatusTitle="BTC Hunter",
        speed="Geschwindigkeit", seeds="Seeds", time="Zeit", matches="Treffer",
        ram="RAM", temp="Temp", language="Sprache", langBtn="Sprache",
        modeSection="MODUS", modeBip39="Seed Scan", modePuzzle="Puzzle (Hex-Bereich)",
        puzzleSelect="Puzzle auswaehlen:", rangeStart="Start (hex):", rangeEnd="Ende (hex):",
        targetAddr="Zieladresse:", enterRange="Hex-Bereich eingeben", enterTarget="Zieladresse eingeben",
        loading="Laden", errorPrefix="Fehler", footer="Eigentum von Dax2201 | Optimiert von Claude",
        scanned="Gescannt", elapsed="Vergangen"
    )
    val FR = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | arm64",
        csvSection="ENSEMBLE DE DONNEES", csvBtn="Charger donnees", noFile="Aucun fichier",
        configSection="PARAMETRES", threads="Threads", cpuLimit="Limite CPU",
        silent="silencieux", balanced="equilibre", performance="performance",
        start="DEMARRER", stop="ARRETER", statsSection="STATISTIQUES",
        liveSection="EN DIRECT", waitingStart="En attente...",
        matchSection="CORRESPONDANCES", noMatch="Aucune encore...", logSection="LOG",
        loadFirst="Charger les donnees d'abord", copying="Copie en cours...",
        permTitle="Permission requise", permMsg="Acces a tous les fichiers requis.",
        notifMatchTitle="PORTEFEUILLE TROUVE", notifStatusTitle="BTC Hunter",
        speed="Vitesse", seeds="Seeds", time="Temps", matches="Corresp.",
        ram="RAM", temp="Temp", language="Langue", langBtn="Langue",
        modeSection="MODE", modeBip39="Seed Scan", modePuzzle="Puzzle (plage hex)",
        puzzleSelect="Choisir Puzzle:", rangeStart="Debut (hex):", rangeEnd="Fin (hex):",
        targetAddr="Adresse cible:", enterRange="Entrer la plage hex", enterTarget="Entrer l'adresse cible",
        loading="Chargement", errorPrefix="Erreur", footer="Propriete de Dax2201 | Optimise par Claude",
        scanned="Scannees", elapsed="Ecoule"
    )
    val RU = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | arm64",
        csvSection="ДАТАСЕТ", csvBtn="Загрузить датасет", noFile="Нет файла",
        configSection="НАСТРОЙКИ", threads="Потоки", cpuLimit="Лимит ЦП",
        silent="тихий", balanced="баланс", performance="производ.",
        start="СТАРТ", stop="СТОП", statsSection="СТАТИСТИКА",
        liveSection="В ПРЯМОМ ЭФИРЕ", waitingStart="Ожидание...",
        matchSection="СОВПАДЕНИЯ", noMatch="Пока нет...", logSection="ЛОГ",
        loadFirst="Сначала загрузите датасет", copying="Копирование...",
        permTitle="Нужно разрешение", permMsg="Нужен доступ ко всем файлам.",
        notifMatchTitle="КОШЕЛЕК НАЙДЕН", notifStatusTitle="BTC Hunter",
        speed="Скорость", seeds="Сидов", time="Время", matches="Совпад.",
        ram="ОЗУ", temp="Темп", language="Язык", langBtn="Язык",
        modeSection="РЕЖИМ", modeBip39="Seed Scan", modePuzzle="Паззл (hex)",
        puzzleSelect="Выбрать паззл:", rangeStart="Начало (hex):", rangeEnd="Конец (hex):",
        targetAddr="Целевой адрес:", enterRange="Введите hex диапазон", enterTarget="Введите целевой адрес",
        loading="Загрузка", errorPrefix="Ошибка", footer="Собственность Dax2201 | Оптимизировано Claude",
        scanned="Просканировано", elapsed="Прошло"
    )
    val PT = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | arm64",
        csvSection="CONJUNTO DE DADOS", csvBtn="Carregar dados", noFile="Sem arquivo",
        configSection="CONFIGURACAO", threads="Threads", cpuLimit="Limite CPU",
        silent="silencioso", balanced="equilibrado", performance="desempenho",
        start="INICIAR", stop="PARAR", statsSection="ESTATISTICAS",
        liveSection="AO VIVO", waitingStart="Aguardando inicio...",
        matchSection="CORRESPONDENCIAS", noMatch="Nenhuma ainda...", logSection="LOG",
        loadFirst="Carregue os dados primeiro", copying="Copiando...",
        permTitle="Permissao necessaria", permMsg="Precisa de acesso a todos os arquivos.",
        notifMatchTitle="CARTEIRA ENCONTRADA", notifStatusTitle="BTC Hunter",
        speed="Velocidade", seeds="Seeds", time="Tempo", matches="Corresp.",
        ram="RAM", temp="Temp", language="Idioma", langBtn="Idioma",
        modeSection="MODO", modeBip39="Seed Scan", modePuzzle="Puzzle (intervalo hex)",
        puzzleSelect="Selecionar Puzzle:", rangeStart="Inicio (hex):", rangeEnd="Fim (hex):",
        targetAddr="Endereco alvo:", enterRange="Digite o intervalo hex", enterTarget="Digite o endereco alvo",
        loading="Carregando", errorPrefix="Erro", footer="Propriedade de Dax2201 | Otimizado por Claude",
        scanned="Escaneadas", elapsed="Decorrido"
    )

    val ALL = linkedMapOf(
        "ES" to ES, "EN" to EN, "JA" to JA, "KO" to KO,
        "DE" to DE, "FR" to FR, "RU" to RU, "PT" to PT
    )

    fun fromSystem(): String {
        return when(java.util.Locale.getDefault().language) {
            "es" -> "ES"
            "en" -> "EN"
            "ja" -> "JA"
            "ko" -> "KO"
            "de" -> "DE"
            "fr" -> "FR"
            "ru" -> "RU"
            "pt" -> "PT"
            else -> "EN"
        }
    }
}
