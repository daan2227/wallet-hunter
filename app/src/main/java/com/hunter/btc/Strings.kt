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
        val loading: String, val errorPrefix: String, val footer: String
    )

    val ES = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh",
        csvSection="ARCHIVO CSV", csvBtn="CSV", noFile="Sin archivo",
        configSection="CONFIGURACION", threads="Threads", cpuLimit="Limite CPU",
        silent="silencioso", balanced="balanceado", performance="rendimiento",
        start="INICIAR", stop="DETENER", statsSection="ESTADISTICAS",
        liveSection="CONSULTANDO EN VIVO", waitingStart="Esperando inicio...",
        matchSection="COINCIDENCIAS", noMatch="Ninguna aun...", logSection="LOG",
        loadFirst="Carga el CSV primero", copying="Copiando CSV...",
        permTitle="Permiso necesario", permMsg="Para leer el CSV necesitas acceso a todos los archivos.",
        notifMatchTitle="WALLET ENCONTRADA", notifStatusTitle="BTC Hunter",
        speed="Velocidad", seeds="Seeds", time="Tiempo", matches="Matches",
        ram="RAM", temp="Temp", language="Idioma", langBtn="Idioma",
        modeSection="MODO DE BUSQUEDA", modeBip39="Seed Scan", modePuzzle="Puzzle (rango hex)",
        puzzleSelect="Seleccionar Puzzle:", rangeStart="Rango Inicio (hex):", rangeEnd="Rango Fin (hex):",
        targetAddr="Direccion Objetivo:", enterRange="Ingresa el rango hex", enterTarget="Ingresa la direccion objetivo",
        loading="Cargando", errorPrefix="Error", footer="Propiedad de Dax2201 | Optimizado por Claude"
    )
    val EN = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh",
        csvSection="CSV FILE", csvBtn="CSV", noFile="No file",
        configSection="SETTINGS", threads="Threads", cpuLimit="CPU Limit",
        silent="silent", balanced="balanced", performance="performance",
        start="START", stop="STOP", statsSection="STATISTICS",
        liveSection="LIVE SCAN", waitingStart="Waiting to start...",
        matchSection="MATCHES", noMatch="None yet...", logSection="LOG",
        loadFirst="Load CSV first", copying="Copying CSV...",
        permTitle="Permission needed", permMsg="To read the CSV you need all files access.",
        notifMatchTitle="WALLET FOUND", notifStatusTitle="BTC Hunter",
        speed="Speed", seeds="Seeds", time="Time", matches="Matches",
        ram="RAM", temp="Temp", language="Language", langBtn="Language",
        modeSection="SEARCH MODE", modeBip39="Seed Scan", modePuzzle="Puzzle (hex range)",
        puzzleSelect="Select Puzzle:", rangeStart="Range Start (hex):", rangeEnd="Range End (hex):",
        targetAddr="Target Address:", enterRange="Enter hex range", enterTarget="Enter target address",
        loading="Loading", errorPrefix="Error", footer="Property of Dax2201 | Optimized by Claude"
    )
    val JA = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh",
        csvSection="CSVファイル", csvBtn="CSV", noFile="ファイルなし",
        configSection="設定", threads="スレッド", cpuLimit="CPU制限",
        silent="静音", balanced="バランス", performance="高性能",
        start="開始", stop="停止", statsSection="統計",
        liveSection="ライブスキャン", waitingStart="開始を待機中...",
        matchSection="一致", noMatch="まだなし...", logSection="ログ",
        loadFirst="先にCSVを読み込んでください", copying="CSVをコピー中...",
        permTitle="権限が必要", permMsg="CSVを読むには全ファイルアクセスが必要です。",
        notifMatchTitle="ウォレット発見", notifStatusTitle="BTC Hunter",
        speed="速度", seeds="シード", time="時間", matches="一致数",
        ram="RAM", temp="温度", language="言語", langBtn="言語",
        modeSection="検索モード", modeBip39="Seed Scan", modePuzzle="パズル (16進範囲)",
        puzzleSelect="パズル選択:", rangeStart="範囲開始 (hex):", rangeEnd="範囲終了 (hex):",
        targetAddr="ターゲットアドレス:", enterRange="16進範囲を入力", enterTarget="ターゲットアドレスを入力",
        loading="読込中", errorPrefix="エラー", footer="Dax2201 所有 | Claude 最適化"
    )
    val KO = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh",
        csvSection="CSV 파일", csvBtn="CSV", noFile="파일 없음",
        configSection="설정", threads="스레드", cpuLimit="CPU 제한",
        silent="조용", balanced="균형", performance="성능",
        start="시작", stop="중지", statsSection="통계",
        liveSection="실시간 스캔", waitingStart="시작 대기중...",
        matchSection="일치", noMatch="아직 없음...", logSection="로그",
        loadFirst="먼저 CSV를 로드하세요", copying="CSV 복사중...",
        permTitle="권한 필요", permMsg="CSV를 읽으려면 전체 파일 접근이 필요합니다.",
        notifMatchTitle="지갑 발견", notifStatusTitle="BTC Hunter",
        speed="속도", seeds="시드", time="시간", matches="일치수",
        ram="RAM", temp="온도", language="언어", langBtn="언어",
        modeSection="검색 모드", modeBip39="Seed Scan", modePuzzle="퍼즐 (16진 범위)",
        puzzleSelect="퍼즐 선택:", rangeStart="범위 시작 (hex):", rangeEnd="범위 끝 (hex):",
        targetAddr="대상 주소:", enterRange="16진 범위 입력", enterTarget="대상 주소 입력",
        loading="로딩중", errorPrefix="오류", footer="Dax2201 소유 | Claude 최적화"
    )
    val DE = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh",
        csvSection="CSV-DATEI", csvBtn="CSV", noFile="Keine Datei",
        configSection="EINSTELLUNGEN", threads="Threads", cpuLimit="CPU-Limit",
        silent="leise", balanced="ausgewogen", performance="leistung",
        start="STARTEN", stop="STOPPEN", statsSection="STATISTIKEN",
        liveSection="LIVE-SCAN", waitingStart="Warte auf Start...",
        matchSection="TREFFER", noMatch="Noch keine...", logSection="LOG",
        loadFirst="CSV zuerst laden", copying="CSV wird kopiert...",
        permTitle="Berechtigung erforderlich", permMsg="Zum Lesen der CSV wird Zugriff auf alle Dateien benoetigt.",
        notifMatchTitle="WALLET GEFUNDEN", notifStatusTitle="BTC Hunter",
        speed="Geschwindigkeit", seeds="Seeds", time="Zeit", matches="Treffer",
        ram="RAM", temp="Temp", language="Sprache", langBtn="Sprache",
        modeSection="SUCHMODUS", modeBip39="Seed Scan", modePuzzle="Puzzle (Hex-Bereich)",
        puzzleSelect="Puzzle auswaehlen:", rangeStart="Bereich Start (hex):", rangeEnd="Bereich Ende (hex):",
        targetAddr="Zieladresse:", enterRange="Hex-Bereich eingeben", enterTarget="Zieladresse eingeben",
        loading="Laden", errorPrefix="Fehler", footer="Eigentum von Dax2201 | Optimiert von Claude"
    )
    val FR = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh",
        csvSection="FICHIER CSV", csvBtn="CSV", noFile="Aucun fichier",
        configSection="PARAMETRES", threads="Threads", cpuLimit="Limite CPU",
        silent="silencieux", balanced="equilibre", performance="performance",
        start="DEMARRER", stop="ARRETER", statsSection="STATISTIQUES",
        liveSection="SCAN EN DIRECT", waitingStart="En attente...",
        matchSection="CORRESPONDANCES", noMatch="Aucune encore...", logSection="LOG",
        loadFirst="Charger le CSV d'abord", copying="Copie du CSV...",
        permTitle="Permission requise", permMsg="Pour lire le CSV vous avez besoin de l'acces a tous les fichiers.",
        notifMatchTitle="PORTEFEUILLE TROUVE", notifStatusTitle="BTC Hunter",
        speed="Vitesse", seeds="Seeds", time="Temps", matches="Corresp.",
        ram="RAM", temp="Temp", language="Langue", langBtn="Langue",
        modeSection="MODE DE RECHERCHE", modeBip39="Seed Scan", modePuzzle="Puzzle (plage hex)",
        puzzleSelect="Choisir Puzzle:", rangeStart="Debut plage (hex):", rangeEnd="Fin plage (hex):",
        targetAddr="Adresse cible:", enterRange="Entrer la plage hex", enterTarget="Entrer l'adresse cible",
        loading="Chargement", errorPrefix="Erreur", footer="Propriete de Dax2201 | Optimise par Claude"
    )
    val RU = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh",
        csvSection="CSV ФАЙЛ", csvBtn="CSV", noFile="Нет файла",
        configSection="НАСТРОЙКИ", threads="Потоки", cpuLimit="Лимит ЦП",
        silent="тихий", balanced="баланс", performance="производ.",
        start="СТАРТ", stop="СТОП", statsSection="СТАТИСТИКА",
        liveSection="СКАНИРОВАНИЕ", waitingStart="Ожидание запуска...",
        matchSection="СОВПАДЕНИЯ", noMatch="Пока нет...", logSection="ЛОГ",
        loadFirst="Сначала загрузите CSV", copying="Копирование CSV...",
        permTitle="Нужно разрешение", permMsg="Для чтения CSV нужен доступ ко всем файлам.",
        notifMatchTitle="КОШЕЛЕК НАЙДЕН", notifStatusTitle="BTC Hunter",
        speed="Скорость", seeds="Сидов", time="Время", matches="Совпад.",
        ram="ОЗУ", temp="Темп", language="Язык", langBtn="Язык",
        modeSection="РЕЖИМ ПОИСКА", modeBip39="Seed Scan", modePuzzle="Паззл (hex диапазон)",
        puzzleSelect="Выбрать паззл:", rangeStart="Начало (hex):", rangeEnd="Конец (hex):",
        targetAddr="Целевой адрес:", enterRange="Введите hex диапазон", enterTarget="Введите целевой адрес",
        loading="Загрузка", errorPrefix="Ошибка", footer="Собственность Dax2201 | Оптимизировано Claude"
    )
    val PT = Lang(
        title="Bitcoin Wallet Hunter", subtitle="libsecp256k1 | PBKDF2:1 | p2pkh+p2sh+p2wpkh",
        csvSection="ARQUIVO CSV", csvBtn="CSV", noFile="Sem arquivo",
        configSection="CONFIGURACAO", threads="Threads", cpuLimit="Limite CPU",
        silent="silencioso", balanced="equilibrado", performance="desempenho",
        start="INICIAR", stop="PARAR", statsSection="ESTATISTICAS",
        liveSection="SCAN AO VIVO", waitingStart="Aguardando inicio...",
        matchSection="CORRESPONDENCIAS", noMatch="Nenhuma ainda...", logSection="LOG",
        loadFirst="Carregue o CSV primeiro", copying="Copiando CSV...",
        permTitle="Permissao necessaria", permMsg="Para ler o CSV precisa de acesso a todos os arquivos.",
        notifMatchTitle="CARTEIRA ENCONTRADA", notifStatusTitle="BTC Hunter",
        speed="Velocidade", seeds="Seeds", time="Tempo", matches="Corresp.",
        ram="RAM", temp="Temp", language="Idioma", langBtn="Idioma",
        modeSection="MODO DE BUSCA", modeBip39="Seed Scan", modePuzzle="Puzzle (intervalo hex)",
        puzzleSelect="Selecionar Puzzle:", rangeStart="Inicio intervalo (hex):", rangeEnd="Fim intervalo (hex):",
        targetAddr="Endereco alvo:", enterRange="Digite o intervalo hex", enterTarget="Digite o endereco alvo",
        loading="Carregando", errorPrefix="Erro", footer="Propriedade de Dax2201 | Otimizado por Claude"
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
