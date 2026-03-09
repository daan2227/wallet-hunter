package com.hunter.btc

object Strings {
    data class Lang(
        val title: String,
        val subtitle: String,
        val csvSection: String,
        val csvBtn: String,
        val noFile: String,
        val configSection: String,
        val threads: String,
        val cpuLimit: String,
        val silent: String,
        val balanced: String,
        val performance: String,
        val start: String,
        val stop: String,
        val statsSection: String,
        val liveSection: String,
        val waitingStart: String,
        val matchSection: String,
        val noMatch: String,
        val logSection: String,
        val loadFirst: String,
        val copying: String,
        val permTitle: String,
        val permMsg: String,
        val notifMatchTitle: String,
        val notifStatusTitle: String,
        val speed: String,
        val seeds: String,
        val time: String,
        val matches: String,
        val ram: String,
        val temp: String,
        val language: String
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
        permTitle="Permiso necesario", permMsg="Para leer el CSV necesitas 'Acceso a todos los archivos'.",
        notifMatchTitle="WALLET ENCONTRADA", notifStatusTitle="BTC Hunter",
        speed="Velocidad", seeds="Seeds", time="Tiempo", matches="Matches",
        ram="RAM", temp="Temp", language="Idioma"
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
        permTitle="Permission needed", permMsg="To read the CSV you need 'All files access'.",
        notifMatchTitle="WALLET FOUND", notifStatusTitle="BTC Hunter",
        speed="Speed", seeds="Seeds", time="Time", matches="Matches",
        ram="RAM", temp="Temp", language="Language"
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
        ram="RAM", temp="温度", language="言語"
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
        ram="RAM", temp="온도", language="언어"
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
        permTitle="Berechtigung erforderlich", permMsg="Zum Lesen der CSV benoetigen Sie Zugriff auf alle Dateien.",
        notifMatchTitle="WALLET GEFUNDEN", notifStatusTitle="BTC Hunter",
        speed="Geschwindigkeit", seeds="Seeds", time="Zeit", matches="Treffer",
        ram="RAM", temp="Temp", language="Sprache"
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
        speed="Vitesse", seeds="Seeds", time="Temps", matches="Correspond.",
        ram="RAM", temp="Temp", language="Langue"
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
        notifMatchTitle="КОШЕЛЁК НАЙДЕН", notifStatusTitle="BTC Hunter",
        speed="Скорость", seeds="Сидов", time="Время", matches="Совпад.",
        ram="ОЗУ", temp="Темп", language="Язык"
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
        ram="RAM", temp="Temp", language="Idioma"
    )

    val ALL = linkedMapOf(
        "ES" to ES, "EN" to EN, "JA" to JA, "KO" to KO,
        "DE" to DE, "FR" to FR, "RU" to RU, "PT" to PT
    )
}
