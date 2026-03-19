#!/usr/bin/env python3
"""
patch_step6.py
Agrega la tab Recovery a MainActivity.kt.
Ejecutar desde la raiz del proyecto: python3 patch_step6.py
"""

import os, sys, re

PROJECT_ROOT = os.getcwd()
MAIN_KT = os.path.join(PROJECT_ROOT, "app", "src", "main", "java",
                       "com", "hunter", "btc", "MainActivity.kt")

if not os.path.exists(MAIN_KT):
    print(f"ERROR: No se encontró {MAIN_KT}")
    sys.exit(1)

content = open(MAIN_KT).read()

# ── 1. Agregar import RecoveryEngine si no está ───────────────────────────────
import_line = "import com.hunter.btc.recovery.RecoveryEngine"
if import_line not in content:
    content = content.replace(
        "import android.app.Activity",
        "import android.app.Activity\n" + import_line
    )
    # fallback: agregar después del último import
    if import_line not in content:
        last_import = content.rfind("\nimport ")
        end_of_import = content.find("\n", last_import + 1)
        content = content[:end_of_import] + "\n" + import_line + content[end_of_import:]
    print("✓ Import RecoveryEngine agregado")
else:
    print("→ Import ya existe")

# ── 2. Agregar variable miembro recoveryEngine ────────────────────────────────
member_var = "    private lateinit var recoveryEngine: RecoveryEngine"
if "recoveryEngine" not in content:
    # Agregar después de la declaración de clase o junto a otras variables privadas
    content = content.replace(
        "    private val recentAddrs = mutableListOf<String>()",
        "    private val recentAddrs = mutableListOf<String>()\n" + member_var
    )
    print("✓ Variable recoveryEngine agregada")
else:
    print("→ Variable recoveryEngine ya existe")

# ── 3. Agregar página Recovery (recoveryScroll) antes del tabBar ──────────────
recovery_page = '''
        /* ══ RECOVERY PAGE ══ */
        val recoveryScroll=ScrollView(this).apply{setBackgroundColor(BG_CARD)}
        val recoveryPage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(80))}

        // Header
        recoveryPage.addView(TextView(this).apply{
            text="⚷  SEED RECOVERY";textSize=13f;setTextColor(AMBER)
            typeface=Typeface.create("monospace",Typeface.BOLD);setPadding(0,dp(4),0,dp(2))
        })
        recoveryPage.addView(TextView(this).apply{
            text="Ingresa tu seed phrase. Usa ??? para las palabras que no recuerdas."
            textSize=10f;setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE
            setPadding(0,0,0,dp(12))
        })

        // Input seed phrase
        val etSeed=android.widget.EditText(this).apply{
            hint="abandon ??? letter ??? advice cage absurd amount doctor acoustic avoid ???"
            setHintTextColor(0xFF555566.toInt());setTextColor(TXT_PRI)
            textSize=11f;typeface=Typeface.MONOSPACE
            setBackgroundColor(BG_PANEL);setPadding(dp(12),dp(10),dp(12),dp(10))
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines=3;maxLines=5;isSingleLine=false
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)}
        }
        recoveryPage.addView(etSeed)

        // Input dirección objetivo
        recoveryPage.addView(TextView(this).apply{
            text="DIRECCIÓN BTC OBJETIVO (opcional)";textSize=9f
            setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE;setPadding(0,dp(4),0,dp(4))
        })
        val etTarget=android.widget.EditText(this).apply{
            hint="1A2B3C... o bc1q...";setHintTextColor(0xFF555566.toInt())
            setTextColor(TXT_PRI);textSize=11f;typeface=Typeface.MONOSPACE
            setBackgroundColor(BG_PANEL);setPadding(dp(12),dp(10),dp(12),dp(10))
            isSingleLine=true
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)}
        }
        recoveryPage.addView(etTarget)

        // Info: combinaciones y tiempo estimado
        val tvRecoveryInfo=TextView(this).apply{
            text="Palabras faltantes: —";textSize=10f
            setTextColor(AppTheme.CYAN);typeface=Typeface.MONOSPACE
            setPadding(0,dp(4),0,dp(8))
        }
        recoveryPage.addView(tvRecoveryInfo)

        // Actualizar info en tiempo real al escribir
        etSeed.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){}
            override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){}
            override fun afterTextChanged(s:android.text.Editable?){
                val input=s?.toString()?:""
                val missing=input.split(" ").count{it.trim()=="???"}
                if(missing>0){
                    val combos=Math.pow(2048.0,missing.toDouble()).toLong()
                    val combosStr=when{combos<1_000_000L->"${combos/1000}K";combos<1_000_000_000L->"${combos/1_000_000}M";else->"${combos/1_000_000_000}B"}
                    val secs=combos/50_000L
                    val timeStr=when{secs<60->"$secs seg";secs<3600->"${secs/60} min";secs<86400->"${secs/3600} h";else->"${secs/86400} días"}
                    tvRecoveryInfo.text="Faltantes: $missing  |  Combinaciones: ~$combosStr  |  Tiempo est.: $timeStr"
                }else{
                    tvRecoveryInfo.text="Palabras faltantes: —"
                }
            }
        })

        // Barra de progreso
        val pbRecovery=android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{
            max=1000;progress=0
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(8)).apply{bottomMargin=dp(4)}
            visibility=android.view.View.GONE
        }
        recoveryPage.addView(pbRecovery)

        // Status text
        val tvRecoveryStatus=TextView(this).apply{
            text="";textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE
            setPadding(0,0,0,dp(8));visibility=android.view.View.GONE
        }
        recoveryPage.addView(tvRecoveryStatus)

        // Resultado
        val tvRecoveryResult=TextView(this).apply{
            text="";textSize=11f;setTextColor(0xFF00FF88.toInt())
            typeface=Typeface.create("monospace",Typeface.BOLD)
            setPadding(dp(12),dp(12),dp(12),dp(12))
            setBackgroundColor(BG_PANEL)
            visibility=android.view.View.GONE
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)}
        }
        recoveryPage.addView(tvRecoveryResult)

        // Botones Start / Cancel
        val btnRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val btnStartRecovery=Button(this).apply{
            text="▶  INICIAR RECOVERY";textSize=11f
            setTextColor(0xFF000000.toInt());setBackgroundColor(AMBER)
            typeface=Typeface.create("monospace",Typeface.BOLD)
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply{marginEnd=dp(8)}
        }
        val btnCancelRecovery=Button(this).apply{
            text="■  CANCELAR";textSize=11f
            setTextColor(AMBER);setBackgroundColor(BG_PANEL)
            typeface=Typeface.create("monospace",Typeface.BOLD)
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            visibility=android.view.View.GONE
        }
        btnRow.addView(btnStartRecovery);btnRow.addView(btnCancelRecovery)
        recoveryPage.addView(btnRow)

        // Inicializar RecoveryEngine
        recoveryEngine=RecoveryEngine(this)
        val wordlistLoaded=recoveryEngine.loadWordlist()

        recoveryEngine.listener=object:com.hunter.btc.recovery.RecoveryEngine.ProgressListener{
            override fun onProgress(attempts:Long,total:Long,currentWord:String){
                runOnUiThread{
                    val pct=((attempts.toFloat()/total)*1000).toInt()
                    pbRecovery.progress=pct
                    tvRecoveryStatus.text="Probando: $currentWord  ($attempts / $total)"
                }
            }
            override fun onFound(mnemonic:String){
                runOnUiThread{
                    pbRecovery.visibility=android.view.View.GONE
                    tvRecoveryStatus.visibility=android.view.View.GONE
                    btnCancelRecovery.visibility=android.view.View.GONE
                    btnStartRecovery.visibility=android.view.View.VISIBLE
                    tvRecoveryResult.text="✓ ENCONTRADO\n\n$mnemonic"
                    tvRecoveryResult.visibility=android.view.View.VISIBLE
                    // Guardar en logs
                    val ts=java.text.SimpleDateFormat("yyyyMMdd_HHmmss",java.util.Locale.US).format(java.util.Date())
                    val f=java.io.File(getExternalFilesDir(null),"recovery_$ts.txt")
                    f.writeText("RECOVERY MATCH\n$mnemonic\n")
                }
            }
            override fun onNotFound(){
                runOnUiThread{
                    pbRecovery.visibility=android.view.View.GONE
                    tvRecoveryStatus.text="No encontrado. Verifica las palabras conocidas."
                    btnCancelRecovery.visibility=android.view.View.GONE
                    btnStartRecovery.visibility=android.view.View.VISIBLE
                }
            }
            override fun onCancelled(){
                runOnUiThread{
                    pbRecovery.visibility=android.view.View.GONE
                    tvRecoveryStatus.text="Cancelado."
                    btnCancelRecovery.visibility=android.view.View.GONE
                    btnStartRecovery.visibility=android.view.View.VISIBLE
                }
            }
        }

        btnStartRecovery.setOnClickListener{
            val input=etSeed.text.toString().trim()
            if(input.isEmpty()){
                tvRecoveryStatus.text="Ingresa la seed phrase primero."
                tvRecoveryStatus.visibility=android.view.View.VISIBLE
                return@setOnClickListener
            }
            if(!wordlistLoaded){
                tvRecoveryStatus.text="Error: wordlist BIP39 no cargado."
                tvRecoveryStatus.visibility=android.view.View.VISIBLE
                return@setOnClickListener
            }
            val wl=recoveryEngine.getWordlistSet()
            val parseResult=com.hunter.btc.recovery.RecoveryParser.parse(input,wl)
            when(parseResult){
                is com.hunter.btc.recovery.ParseResult.Error->{
                    tvRecoveryStatus.text=parseResult.message
                    tvRecoveryStatus.visibility=android.view.View.VISIBLE
                }
                is com.hunter.btc.recovery.ParseResult.Success->{
                    tvRecoveryResult.visibility=android.view.View.GONE
                    pbRecovery.progress=0
                    pbRecovery.visibility=android.view.View.VISIBLE
                    tvRecoveryStatus.visibility=android.view.View.VISIBLE
                    tvRecoveryStatus.text="Iniciando..."
                    btnStartRecovery.visibility=android.view.View.GONE
                    btnCancelRecovery.visibility=android.view.View.VISIBLE
                    recoveryEngine.startRecovery(parseResult.parsed,etTarget.text.toString().trim())
                }
            }
        }

        btnCancelRecovery.setOnClickListener{ recoveryEngine.cancel() }

        recoveryScroll.addView(recoveryPage)
        cf.addView(recoveryScroll)

'''

# Insertar antes del comentario TABBAR
if "recoveryScroll" not in content:
    content = content.replace(
        "        /* ══ TABBAR ══ */",
        recovery_page + "        /* ══ TABBAR ══ */"
    )
    print("✓ Página Recovery agregada")
else:
    print("→ Página Recovery ya existe")

# ── 4. Agregar tab button tb4 ─────────────────────────────────────────────────
if "tb4" not in content:
    content = content.replace(
        'val tb0=tabBtn("⊙","Scan");val tb2=tabBtn("◈","Stats");val tb3=tabBtn("⚙","Config")',
        'val tb0=tabBtn("⊙","Scan");val tb2=tabBtn("◈","Stats");val tb3=tabBtn("⚙","Config");val tb4=tabBtn("⚷","Recovery")'
    )
    content = content.replace(
        'listOf(tb0,tb2,tb3).forEach{tabBar.addView(it)}',
        'listOf(tb0,tb2,tb3,tb4).forEach{tabBar.addView(it)}'
    )
    content = content.replace(
        'tabPages=listOf(scanScroll,statsScroll,cfgScroll)',
        'tabPages=listOf(scanScroll,statsScroll,cfgScroll,recoveryScroll)'
    )
    content = content.replace(
        'tabBtns =listOf(tb0,tb2,tb3)',
        'tabBtns =listOf(tb0,tb2,tb3,tb4)'
    )
    content = content.replace(
        'listOf(tb0,tb2,tb3).forEachIndexed{i,b->b.setOnClickListener{goTab(i)}}',
        'listOf(tb0,tb2,tb3,tb4).forEachIndexed{i,b->b.setOnClickListener{goTab(i)}}'
    )
    print("✓ Tab button tb4 agregado")
else:
    print("→ Tab tb4 ya existe")

# ── 5. Agregar getWordlistSet() a RecoveryEngine ──────────────────────────────
# Necesitamos exponer el wordlist como Set para RecoveryParser
kt_dir = os.path.join(PROJECT_ROOT, "app", "src", "main", "java",
                      "com", "hunter", "btc", "recovery")
engine_kt = os.path.join(kt_dir, "RecoveryEngine.kt")

if os.path.exists(engine_kt):
    engine_content = open(engine_kt).read()
    if "getWordlistSet" not in engine_content:
        engine_content = engine_content.replace(
            "    fun loadWordlist(): Boolean {",
            """    fun getWordlistSet(): Set<String> = wordlist?.toSet() ?: emptySet()

    fun loadWordlist(): Boolean {"""
        )
        with open(engine_kt, "w") as f:
            f.write(engine_content)
        print("✓ getWordlistSet() agregado a RecoveryEngine.kt")
    else:
        print("→ getWordlistSet() ya existe")

# ── 6. Guardar MainActivity.kt ────────────────────────────────────────────────
with open(MAIN_KT, "w") as f:
    f.write(content)
print("✓ MainActivity.kt actualizado")

print("""
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Paso 6 completado:

  MainActivity.kt  ← tab ⚷ Recovery agregada
  RecoveryEngine.kt ← getWordlistSet() agregado

La tab Recovery incluye:
  • Input seed phrase con soporte ???
  • Input dirección objetivo (opcional)
  • Info en tiempo real: faltantes / combos / tiempo
  • Barra de progreso
  • Botones Start / Cancelar
  • Resultado visible en pantalla
  • Guardado automático en archivo recovery_*.txt

Siguiente: git add + push → verificar compilación
Luego Paso 7: conectar resultado con Stats tab (logs)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""")
