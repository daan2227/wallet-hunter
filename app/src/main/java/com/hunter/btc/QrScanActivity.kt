package com.hunter.btc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Lector de QR para la dirección de destino.
 *
 * Enviar obligaba a teclear la dirección a mano —34 a 62 caracteres donde uno
 * cambiado manda el dinero a otro sitio— o a copiarla de otra app. Esto lee
 * el QR con la cámara y devuelve el texto tal cual; quien llama lo interpreta
 * (una dirección suelta o un "bitcoin:…?amount=…", ver PagoUri).
 *
 * Camera2 a pelo y el descodificador de zxing, que la app ya lleva para
 * DIBUJAR el QR de recibir: CameraX o una librería de escaneo meterían varios
 * megas para una sola pantalla.
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXTO = "qr_texto"
        private const val REQ_CAMARA = 51
    }

    private lateinit var vista: TextureView
    private var camara: CameraDevice? = null
    private var sesion: CameraCaptureSession? = null
    private var lector: ImageReader? = null
    private var hilo: HandlerThread? = null
    private var manejador: Handler? = null
    @Volatile private var hecho = false
    private val qr = com.google.zxing.qrcode.QRCodeReader()
    private val pistas = mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(savedInstanceState)
        AppLock.init(this)

        val raiz = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        vista = TextureView(this)
        raiz.addView(vista, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // El recuadro donde poner el código.
        val lado = (resources.displayMetrics.widthPixels * 0.68f).toInt()
        raiz.addView(android.view.View(this).apply {
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(3), AppTheme.ACCENT); cornerRadius = dp(22).toFloat()
            }
        }, FrameLayout.LayoutParams(lado, lado, Gravity.CENTER))
        raiz.addView(TextView(this).apply {
            text = "Point the camera at the QR code"
            setTextColor(android.graphics.Color.WHITE); textSize = AppTheme.SP_BODY
            typeface = AppTheme.medium(context); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { topMargin = lado / 2 + dp(40) })
        raiz.addView(TextView(this).apply {
            text = "Cancel"
            setTextColor(android.graphics.Color.WHITE); textSize = AppTheme.SP_BODY
            typeface = AppTheme.bold(context); gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x66000000); cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(48) })
        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMARA)
            return
        }
        empezar()
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_CAMARA && (res.isEmpty() || res[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Without camera permission the QR cannot be read. " +
                "You can paste the address instead.", Toast.LENGTH_LONG).show()
            finish()
        }
        // Si se concede, onResume vuelve a pasar y arranca.
    }

    override fun onPause() {
        parar()
        super.onPause()
    }

    private fun empezar() {
        if (camara != null) return
        hilo = HandlerThread("qr").also { it.start() }
        manejador = Handler(hilo!!.looper)
        if (vista.isAvailable) abrir()
        else vista.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = abrir()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun abrir() {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull()
        } catch (e: Exception) { null }
        if (id == null) { fallo("No camera found"); return }

        // El tamaño más cercano a 1280x720: de sobra para un QR, y ligero.
        val mapa = cm.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val tam = mapa?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width * it.height <= 1920 * 1080 }
            ?.minByOrNull { Math.abs(it.width * it.height - 1280 * 720) } ?: Size(640, 480)

        lector = ImageReader.newInstance(tam.width, tam.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ r -> leer(r) }, manejador)
        }
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { camara = c; sesionar(c, tam) }
                override fun onDisconnected(c: CameraDevice) { c.close(); camara = null }
                override fun onError(c: CameraDevice, e: Int) { c.close(); camara = null; fallo("Camera error $e") }
            }, manejador)
        } catch (e: Exception) { fallo("Could not open the camera") }
    }

    @Suppress("DEPRECATION")
    private fun sesionar(c: CameraDevice, tam: Size) {
        val st = vista.surfaceTexture ?: return
        st.setDefaultBufferSize(tam.width, tam.height)
        val previa = Surface(st)
        val destino = lector!!.surface
        c.createCaptureSession(listOf(previa, destino), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                sesion = s
                try {
                    val req = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previa); addTarget(destino)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    s.setRepeatingRequest(req.build(), null, manejador)
                } catch (e: Exception) { fallo("Could not start the camera") }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { fallo("Could not start the camera") }
        }, manejador)
    }

    /** Sólo el plano Y (luminancia): es todo lo que necesita un QR. */
    private fun leer(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            if (hecho) return
            val p = img.planes[0]
            val w = img.width; val h = img.height
            val paso = p.rowStride
            val buf = p.buffer
            val datos = ByteArray(w * h)
            if (paso == w) buf.get(datos, 0, w * h)
            else for (y in 0 until h) { buf.position(y * paso); buf.get(datos, y * w, w) }
            val fuente = com.google.zxing.PlanarYUVLuminanceSource(datos, w, h, 0, 0, w, h, false)
            val bmp = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(fuente))
            val res = try { qr.decode(bmp, pistas) } catch (e: Exception) { null } finally { qr.reset() }
            if (res != null && res.text.isNotBlank()) {
                hecho = true
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXTO, res.text.trim()))
                    finish()
                }
            }
        } finally { img.close() }
    }

    private fun fallo(t: String) = runOnUiThread {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show(); finish()
    }

    private fun parar() {
        try { sesion?.close() } catch (e: Exception) {}
        try { camara?.close() } catch (e: Exception) {}
        try { lector?.close() } catch (e: Exception) {}
        sesion = null; camara = null; lector = null
        hilo?.quitSafely(); hilo = null; manejador = null
    }
}
