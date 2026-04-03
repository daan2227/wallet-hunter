#!/usr/bin/env python3
"""
Paso 1: Reemplazar tab bar inferior por Header + Drawer lateral
"""

path = "app/src/main/java/com/hunter/btc/MainActivity.kt"
content = open(path).read()

# ── 1. Reemplazar el bloque del TabBar (líneas 265-311) ──
OLD_TABBAR = '''        // ── TabBar ────────────────────────────────────────────────────────────
        // Banner AdMob
        val adBanner = AdManager.createBanner(this)
        root.addView(adBanner)

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG_PANEL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)
            )
        }
        fun tabBtn(ico: String, lbl: String): TextView = TextView(this).apply {
            text = "$ico\\n$lbl"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.1f; gravity = Gravity.CENTER; isAllCaps = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val tb0 = tabBtn("⊙", "Scan")
        val tb1 = tabBtn("⬡", "Puzzle")
        val tb2 = tabBtn("◈", "Wallet")
        val tb3 = tabBtn("⚷", "Recovery")
        val tb4 = tabBtn("🌐", "Red")
        val tb5 = tabBtn("🐛", "Debug")
        listOf(tb0, tb1, tb2, tb3, tb4, tb5).forEach { tabBar.addView(it) }
        root.addView(tabBar)
        setContentView(root)

        tabPages = listOfNotNull(scanScroll, puzzleScroll, walletScroll, recoveryScroll)
        tabBtns  = listOf(tb0, tb1, tb2, tb3, tb4, tb5)
        tb4.setOnClickListener {
            startActivity(android.content.Intent(this, NetworkActivity::class.java))
        }
        tb5.setOnClickListener {
            startActivity(android.content.Intent(this, DebugActivity::class.java))
        }
        listOf(tb0, tb1, tb2, tb3).forEachIndexed { i, b ->
            b.setOnClickListener {
                if (i == 2) {
                    if (WalletManager.hasPin(this) && !PinAuthHelper.isSessionValid()) {
                        PinAuthHelper.show(this) { ok -> if (ok) goTab(2) }
                    } else goTab(2)
                } else goTab(i)
            }
        }
        goTab(0)'''

NEW_TABBAR = '''        // ── Header + Drawer ───────────────────────────────────────────────────
        val header = buildHeader()
        root.addView(header)

        val drawerLayout = buildDrawerLayout()
        root.addView(drawerLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        tabPages = listOfNotNull(scanScroll, puzzleScroll, walletScroll, recoveryScroll)
        tabBtns  = listOf<TextView>()
        goTab(0)'''

if OLD_TABBAR in content:
    content = content.replace(OLD_TABBAR, NEW_TABBAR)
    print("✓ TabBar reemplazado por Header + Drawer")
else:
    print("✗ ERROR: No se encontró el bloque TabBar exacto")
    print("  Buscando fragmento clave...")
    if "val tb0 = tabBtn" in content:
        print("  → Fragmento 'tb0' encontrado pero bloque no coincide exactamente")
    raise SystemExit(1)

# ── 2. Reemplazar el bloque root (quitar cf, agregar header arriba) ──
OLD_ROOT = '''        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val cf = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }'''

NEW_ROOT = '''        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val cf = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }'''

if OLD_ROOT in content:
    content = content.replace(OLD_ROOT, NEW_ROOT)
    print("✓ FrameLayout cf actualizado a MATCH_PARENT")
else:
    print("! AVISO: bloque root no coincide, saltando")

# ── 3. Reemplazar addView(cf) y el orden de tabs ──
OLD_ADDCF = '''        scanScroll?.let { cf.addView(it) }
        puzzleScroll?.let { cf.addView(it) }
        walletScroll?.let { cf.addView(it) }
        recoveryScroll?.let { cf.addView(it) }
        root.addView(cf)'''

NEW_ADDCF = '''        scanScroll?.let { cf.addView(it) }
        puzzleScroll?.let { cf.addView(it) }
        walletScroll?.let { cf.addView(it) }
        recoveryScroll?.let { cf.addView(it) }
        contentFrame = cf'''

if OLD_ADDCF in content:
    content = content.replace(OLD_ADDCF, NEW_ADDCF)
    print("✓ contentFrame asignado")
else:
    print("! AVISO: bloque addView(cf) no coincide, saltando")

# ── 4. Agregar variable contentFrame y drawerOpen en propiedades de clase ──
OLD_PROPS = '''    private var tabPages: List<ScrollView> = emptyList()
    private var tabBtns:  List<TextView>  = emptyList()'''

NEW_PROPS = '''    private var tabPages:    List<ScrollView> = emptyList()
    private var tabBtns:     List<TextView>  = emptyList()
    private var contentFrame: FrameLayout?   = null
    private var drawerOpen:   Boolean        = false
    private var menuBtn:      View?          = null
    private var drawerView:   View?          = null
    private var overlayView:  View?          = null'''

if OLD_PROPS in content:
    content = content.replace(OLD_PROPS, NEW_PROPS)
    print("✓ Propiedades de drawer agregadas")
else:
    print("! AVISO: propiedades no encontradas, verificar manualmente")

# ── 5. Agregar funciones buildHeader y buildDrawerLayout antes de buildScanTab ──
HEADER_FUNCTIONS = '''
    // ── HEADER ───────────────────────────────────────────────────────────────────
    private fun buildHeader(): LinearLayout {
        val ACCENT = 0xFF00C896.toInt()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0B0E14.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            setPadding(dp(16), 0, dp(16), 0)
            elevation = dp(4).toFloat()
        }

        // Logo icon
        val logoIcon = TextView(this).apply {
            text = "₿"
            textSize = 16f
            setTextColor(0xFF000000.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                colors = intArrayOf(ACCENT, 0xFF0087FF.toInt())
                gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            }
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also {
                it.gravity = Gravity.CENTER_VERTICAL
            }
        }
        header.addView(logoIcon)

        // Logo text
        val logoText = TextView(this).apply {
            text = android.text.SpannableString("Wallet Hunter").also { sp ->
                sp.setSpan(
                    android.text.style.ForegroundColorSpan(ACCENT),
                    6, 13,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFE8EAF0.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.gravity = Gravity.CENTER_VERTICAL
                it.marginStart = dp(10)
            }
        }
        header.addView(logoText)

        // Status dot
        val dot = android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF5A607A.toInt())
                setSize(dp(8), dp(8))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also {
                it.gravity = Gravity.CENTER_VERTICAL
                it.marginEnd = dp(12)
            }
            tag = "statusDot"
        }
        header.addView(dot)

        // Menu button
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0xFF111520.toInt())
                setStroke(1, 0xFF1E2540.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).also {
                it.gravity = Gravity.CENTER_VERTICAL
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleDrawer() }
        }
        repeat(3) {
            val bar = android.view.View(this).apply {
                setBackgroundColor(0xFFE8EAF0.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(2)).also {
                    it.setMargins(0, dp(2), 0, dp(2))
                }
            }
            menu.addView(bar)
        }
        menuBtn = menu
        header.addView(menu)

        // Bottom border
        val border = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E2540.toInt())
        }

        return header
    }

    // ── DRAWER ───────────────────────────────────────────────────────────────────
    private fun buildDrawerLayout(): FrameLayout {
        val ACCENT = 0xFF00C896.toInt()
        val frame = FrameLayout(this)

        // Content frame (tabs go here)
        val cf = contentFrame ?: FrameLayout(this).also { contentFrame = it }
        frame.addView(cf, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Overlay
        val overlay = android.view.View(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            alpha = 0f
            visibility = android.view.View.GONE
            setOnClickListener { closeDrawer() }
        }
        overlayView = overlay
        frame.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Drawer panel
        val drawerWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111520.toInt())
            translationX = -drawerWidth.toFloat()
            elevation = dp(16).toFloat()
        }
        drawerView = drawer
        frame.addView(drawer, FrameLayout.LayoutParams(drawerWidth, FrameLayout.LayoutParams.MATCH_PARENT))

        // Drawer header
        val drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF111520.toInt())
                setStroke(0, 0)
            }
        }
        val dTitle = TextView(this).apply {
            text = android.text.SpannableString("WalletHunter").also { sp ->
                sp.setSpan(
                    android.text.style.ForegroundColorSpan(ACCENT),
                    6, 12,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFE8EAF0.toInt())
        }
        val dSub = TextView(this).apply {
            text = "com.hunter.btc · ARM64"
            textSize = 10f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(0xFF5A607A.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        drawerHeader.addView(dTitle)
        drawerHeader.addView(dSub)

        // Divider
        val divider = android.view.View(this).apply {
            setBackgroundColor(0xFF1E2540.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }
        drawer.addView(drawerHeader)
        drawer.addView(divider)

        // Nav items
        val navContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        data class NavItem(val icon: String, val label: String, val idx: Int, val special: Boolean = false)
        val items = listOf(
            NavItem("⚡", "Scan", 0),
            NavItem("🧩", "Puzzle", 1),
            NavItem("◈", "Wallet", 2),
            NavItem("⚷", "Recovery", 3),
            NavItem("🌐", "Network", -1, true),
            NavItem("🐛", "Debug", -2, true)
        )

        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, dp(4)) }
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (item.idx == 0) 0x1400C896.toInt() else 0x00000000.toInt())
                    if (item.idx == 0) setStroke(1, 0x3300C896.toInt())
                }
                tag = "nav_${item.idx}"
                setOnClickListener {
                    when {
                        item.idx == -1 -> {
                            startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java))
                            closeDrawer()
                        }
                        item.idx == -2 -> {
                            startActivity(android.content.Intent(this@MainActivity, DebugActivity::class.java))
                            closeDrawer()
                        }
                        item.idx == 2 -> {
                            if (WalletManager.hasPin(this@MainActivity) && !PinAuthHelper.isSessionValid()) {
                                PinAuthHelper.show(this@MainActivity) { ok -> if (ok) { goTab(2); closeDrawer() } }
                            } else { goTab(item.idx); closeDrawer() }
                        }
                        else -> { goTab(item.idx); closeDrawer() }
                    }
                }
            }

            val iconTv = TextView(this).apply {
                text = item.icon
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).also {
                    it.gravity = Gravity.CENTER_VERTICAL
                }
            }
            val labelTv = TextView(this).apply {
                text = item.label
                textSize = 14f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(if (item.idx == 0) ACCENT else 0xFF5A607A.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.gravity = Gravity.CENTER_VERTICAL
                    it.marginStart = dp(14)
                }
            }
            row.addView(iconTv)
            row.addView(labelTv)
            navContainer.addView(row)
        }

        drawer.addView(navContainer)

        // Drawer footer
        val footerDiv = android.view.View(this).apply {
            setBackgroundColor(0xFF1E2540.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        val footer = TextView(this).apply {
            text = "v2.4 · Wallet Hunter"
            textSize = 10f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(0xFF3A4060.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        }
        drawer.addView(footerDiv)
        drawer.addView(footer)

        return frame
    }

    // ── DRAWER CONTROLS ──────────────────────────────────────────────────────────
    private fun toggleDrawer() {
        if (drawerOpen) closeDrawer() else openDrawer()
    }

    private fun openDrawer() {
        val d = drawerView ?: return
        val o = overlayView ?: return
        drawerOpen = true
        o.visibility = android.view.View.VISIBLE
        d.animate().translationX(0f).setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        o.animate().alpha(1f).setDuration(300).start()
    }

    fun closeDrawer() {
        val d = drawerView ?: return
        val o = overlayView ?: return
        drawerOpen = false
        val w = (resources.displayMetrics.widthPixels * 0.72f)
        d.animate().translationX(-w).setDuration(280)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).start()
        o.animate().alpha(0f).setDuration(280).withEndAction {
            o.visibility = android.view.View.GONE
        }.start()
    }

    private fun updateDrawerSelection(idx: Int) {
        val ACCENT = 0xFF00C896.toInt()
        val drawer = drawerView as? LinearLayout ?: return
        // Find navContainer (3rd child: header, divider, navContainer)
        val navContainer = drawer.getChildAt(2) as? LinearLayout ?: return
        for (i in 0 until navContainer.childCount) {
            val row = navContainer.getChildAt(i) as? LinearLayout ?: continue
            val tag = row.tag as? String ?: continue
            val rowIdx = tag.removePrefix("nav_").toIntOrNull() ?: continue
            val isActive = rowIdx == idx
            val bg = row.background as? android.graphics.drawable.GradientDrawable
            bg?.setColor(if (isActive) 0x1400C896.toInt() else 0x00000000.toInt())
            bg?.setStroke(if (isActive) 1 else 0, if (isActive) 0x3300C896.toInt() else 0x00000000.toInt())
            val label = row.getChildAt(1) as? TextView
            label?.setTextColor(if (isActive) ACCENT else 0xFF5A607A.toInt())
        }
    }

'''

INSERT_BEFORE = "    // ── BUILD SCAN TAB ────────────────────────────────────────────────────────"

if INSERT_BEFORE in content:
    content = content.replace(INSERT_BEFORE, HEADER_FUNCTIONS + INSERT_BEFORE)
    print("✓ Funciones buildHeader, buildDrawerLayout y controles agregadas")
else:
    print("✗ ERROR: No se encontró el marcador para insertar funciones")
    raise SystemExit(1)

# ── 6. Actualizar goTab para llamar updateDrawerSelection ──
OLD_GOTAB = '''    private fun goTab(idx: Int) {
        tabPages.forEachIndexed { i, page ->
            page.visibility = if (i == idx) View.VISIBLE else View.GONE
        }
        tabBtns.forEachIndexed { i, btn ->
            btn.setTextColor(if (i == idx) LIME else TXT_MUTED)
        }
    }'''

NEW_GOTAB = '''    private fun goTab(idx: Int) {
        tabPages.forEachIndexed { i, page ->
            page.visibility = if (i == idx) View.VISIBLE else View.GONE
        }
        updateDrawerSelection(idx)
    }'''

if OLD_GOTAB in content:
    content = content.replace(OLD_GOTAB, NEW_GOTAB)
    print("✓ goTab actualizado")
else:
    print("! AVISO: goTab no coincide exactamente, buscando variante...")
    # Try simpler match
    if 'private fun goTab(idx: Int)' in content:
        print("  → goTab existe pero con implementación diferente, revisar manualmente")
    else:
        print("  → goTab no encontrado")

# ── Guardar ──
open(path, 'w').write(content)
print("\n✅ Paso 1 completado. Revisa y luego: git add -A && git commit -m 'Redesign Step 1: Header + Drawer navigation' && git push")
