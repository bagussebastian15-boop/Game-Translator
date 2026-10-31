package id.layartrans

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var w = 0
    private var h = 0

    private var bubble: View? = null
    private var overlay: ResultView? = null
    private var busy = false

    private val translators = HashMap<String, Translator>()
    private val recognizers = HashMap<String, TextRecognizer>()

    // Kamus slang sederhana. Tambah sendiri: "kata baku" to "kata gaul"
    private val slang = listOf(
        "terima kasih" to "makasih",
        "tidak" to "nggak",
        "saya" to "gue",
        "aku" to "gue",
        "kamu" to "lu",
        "anda" to "lu",
        "sudah" to "udah",
        "belum" to "belom",
        "sedang" to "lagi",
        "hanya" to "cuma",
        "bagaimana" to "gimana",
        "mengapa" to "kenapa",
        "tetapi" to "tapi",
        "kalau" to "kalo",
        "sekali" to "banget",
        "benar" to "bener",
        "teman" to "temen"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        startFg()
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        projection = p
        setupDisplay()
        addBubble()
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("t", "Penerjemah layar", NotificationManager.IMPORTANCE_MIN)
        )
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "t")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Layar Trans aktif")
            .setContentText("Ketuk untuk berhenti")
            .setContentIntent(stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics =
        DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }

    private fun setupDisplay() {
        val m = realMetrics()
        w = m.widthPixels
        h = m.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd = projection!!.createVirtualDisplay(
            "lt", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null
        )
    }

    private fun refreshSize() {
        val m = realMetrics()
        if (m.widthPixels != w || m.heightPixels != h) {
            w = m.widthPixels
            h = m.heightPixels
            reader?.close()
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            vd?.resize(w, h, m.densityDpi)
        }
    }

    private fun addBubble() {
        val d = resources.displayMetrics.density
        val size = (48 * d).toInt()
        val v = TextView(this).apply {
            text = "文"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1565C0.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (200 * d).toInt()
        }
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = ox + dx.toInt()
                        lp.y = oy + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (e.eventTime - e.downTime > 700) stopSelf() else onTap()
                    }
                }
            }
            true
        }
        wm.addView(v, lp)
        bubble = v
    }

    private fun onTap() {
        if (overlay != null) {
            dismiss()
            return
        }
        if (busy) return
        busy = true
        refreshSize()
        bubble?.visibility = View.INVISIBLE
        vd?.setSurface(reader?.surface)
        handler.postDelayed({ grab(0) }, 200)
    }

    private fun grab(tries: Int) {
        val r = reader ?: return
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) {
            if (tries < 15) {
                handler.postDelayed({ grab(tries + 1) }, 120)
            } else {
                vd?.setSurface(null)
                bubble?.visibility = View.VISIBLE
                busy = false
                toast("Gagal menangkap layar")
            }
            return
        }
        val p = img.planes[0]
        val full = Bitmap.createBitmap(p.rowStride / p.pixelStride, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        img.close()
        vd?.setSurface(null)
        bubble?.visibility = View.VISIBLE
        val bmp = Bitmap.createBitmap(full, 0, 0, w, h)
        full.recycle()
        process(bmp)
    }

    private fun recognizer(src: String): TextRecognizer = recognizers.getOrPut(src) {
        when (src) {
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private fun translator(src: String): Translator = translators.getOrPut(src) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build()
        )
    }

    private fun process(bmp: Bitmap) {
        val src = getSharedPreferences("p", Context.MODE_PRIVATE).getString("src", "en") ?: "en"
        val joiner = if (src == "ja" || src == "zh") "" else " "
        recognizer(src).process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val blocks = text.textBlocks.filter { it.boundingBox != null && it.text.isNotBlank() }
                if (blocks.isEmpty()) {
                    toast("Teks tidak ditemukan")
                    busy = false
                    return@addOnSuccessListener
                }
                val tr = translator(src)
                tr.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        val view = ResultView(this)
                        var left = blocks.size
                        for (b in blocks) {
                            val original = b.lines.joinToString(joiner) { it.text }
                            tr.translate(original)
                                .addOnSuccessListener { t -> view.items.add(Pair(b.boundingBox!!, gaul(t))) }
                                .addOnCompleteListener {
                                    left--
                                    if (left == 0) showOverlay(view)
                                }
                        }
                    }
                    .addOnFailureListener {
                        toast("Gagal mengunduh model bahasa. Cek internet (cukup sekali).")
                        busy = false
                    }
            }
            .addOnFailureListener {
                toast("OCR gagal. Coba lagi.")
                busy = false
            }
    }

    private fun gaul(s: String): String {
        var r = s
        for ((a, b) in slang) r = Regex("\\b$a\\b", RegexOption.IGNORE_CASE).replace(r, b)
        return r
    }

    private fun showOverlay(view: ResultView) {
        busy = false
        if (view.items.isEmpty()) {
            toast("Terjemahan kosong")
            return
        }
        dismiss()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        view.setOnClickListener { dismiss() }
        wm.addView(view, lp)
        overlay = view
    }

    private fun dismiss() {
        overlay?.let {
            try { wm.removeView(it) } catch (e: Exception) { }
        }
        overlay = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        dismiss()
        bubble?.let {
            try { wm.removeView(it) } catch (e: Exception) { }
        }
        bubble = null
        vd?.release()
        reader?.close()
        projection?.stop()
        translators.values.forEach { it.close() }
        recognizers.values.forEach { it.close() }
        super.onDestroy()
    }
}

class ResultView(context: Context) : View(context) {

    val items = ArrayList<Pair<Rect, String>>()

    private val bg = Paint().apply { color = 0xE6101820.toInt() }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics
        )
    }

    override fun onDraw(canvas: Canvas) {
        val minW = (160 * resources.displayMetrics.density).toInt()
        for ((r, t) in items) {
            val wd = maxOf(r.width(), minW)
            val sl = StaticLayout.Builder.obtain(t, 0, t.length, tp, wd).build()
            val left = r.left.coerceAtMost(width - wd).coerceAtLeast(0)
            val top = r.top.coerceAtMost(height - sl.height).coerceAtLeast(0)
            canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            canvas.drawRect(0f, 0f, wd.toFloat(), sl.height.toFloat(), bg)
            sl.draw(canvas)
            canvas.restore()
        }
    }
}
