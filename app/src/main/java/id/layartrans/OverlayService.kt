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
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import android.text.Layout
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
    private var pauseMode = false
    private var pauseBlocked = false

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
            .setContentTitle("Game Translate aktif")
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
        val cur = Shared.current
        if (cur != null) {
            cur.finish()
            return
        }
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

    /** Buang teks sampah: terlalu pendek, atau kebanyakan angka/simbol (HUD, tombol, skor, jam). */
    private fun worthIt(t: String, src: String): Boolean {
        val asian = src != "en"
        val letters = t.count { it.isLetter() }
        val visible = t.count { !it.isWhitespace() }
        if (letters < (if (asian) 2 else 3)) return false
        return letters * 100 / maxOf(visible, 1) >= 60
    }

    private fun ocr(src: String, bmp: Bitmap, done: (List<Pair<Rect, String>>) -> Unit) {
        val joiner = if (src == "ja" || src == "zh") "" else " "
        recognizer(src).process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val out = ArrayList<Pair<Rect, String>>()
                for (b in text.textBlocks) {
                    val r = b.boundingBox ?: continue
                    val t = b.lines.joinToString(joiner) { it.text }.trim()
                    if (worthIt(t, src)) out.add(Pair(r, t))
                }
                done(out)
            }
            .addOnFailureListener {
                toast("OCR gagal. Coba lagi.")
                done(emptyList())
            }
    }

    /** Abu-abu + kontras tinggi, membantu font bergaris tepi atau berwarna tipis. */
    private fun enhance(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val k = 1.8f
        val t = (-0.5f * k + 0.5f) * 255f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    k, 0f, 0f, 0f, t,
                    0f, k, 0f, 0f, t,
                    0f, 0f, k, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        return out
    }

    private fun overlaps(a: Rect, b: Rect): Boolean {
        val i = Rect()
        if (!i.setIntersect(a, b)) return false
        val ia = i.width().toLong() * i.height()
        val m = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return m > 0 && ia * 100 / m > 30
    }

    private fun process(bmp: Bitmap) {
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        val src = prefs.getString("src", "en") ?: "en"
        val thorough = prefs.getBoolean("thorough", false)
        pauseMode = prefs.getBoolean("pause", false)
        // Jeda dulu: buka layar penuh SEKARANG, OCR + terjemahan menyusul di belakang
        if (pauseMode) openPause(bmp)
        ocr(src, bmp) { first ->
            if (!thorough) {
                translateAll(src, bmp, first)
            } else {
                val boosted = enhance(bmp)
                ocr(src, boosted) { second ->
                    boosted.recycle()
                    val merged = ArrayList<Pair<Rect, String>>(first)
                    for (s in second) {
                        if (merged.none { overlaps(it.first, s.first) }) merged.add(s)
                    }
                    translateAll(src, bmp, merged)
                }
            }
        }
    }

    private fun openPause(bmp: Bitmap) {
        Shared.frame = bmp
        Shared.closed = false
        pauseBlocked = false
        val before = Shared.opens
        try {
            startActivity(
                Intent(this, ResultActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            )
        } catch (e: Exception) {
            pauseBlocked = true
        }
        handler.postDelayed({
            if (Shared.opens == before && !Shared.closed) {
                pauseBlocked = true
                toast("Layar jeda diblokir sistem. Pakai tampilan biasa.")
            }
        }, 1200)
    }

    private fun translateAll(src: String, frame: Bitmap, items: List<Pair<Rect, String>>) {
        if (items.isEmpty()) {
            deliver(frame, emptyList())
            return
        }
        val tr = translator(src)
        tr.downloadModelIfNeeded()
            .addOnSuccessListener {
                val out = ArrayList<Pair<Rect, String>>()
                var left = items.size
                for (item in items) {
                    tr.translate(item.second)
                        .addOnSuccessListener { result ->
                            val g = gaul(result)
                            // Nama/teks yang hasilnya sama dengan aslinya tidak perlu ditampilkan
                            if (!g.equals(item.second, ignoreCase = true)) {
                                out.add(Pair(item.first, g))
                            }
                        }
                        .addOnCompleteListener {
                            left--
                            if (left == 0) deliver(frame, out)
                        }
                }
            }
            .addOnFailureListener {
                deliver(frame, emptyList(), "Gagal mengunduh model bahasa (cek internet)")
            }
    }

    private fun deliver(
        frame: Bitmap,
        items: List<Pair<Rect, String>>,
        emptyMsg: String = "Teks tidak ditemukan",
        wait: Int = 0
    ) {
        if (pauseMode) {
            if (Shared.closed) {
                busy = false
                return
            }
            val v = Shared.view
            if (v != null) {
                v.status = if (items.isEmpty()) "$emptyMsg (ketuk untuk menutup)" else null
                v.items.clear()
                v.items.addAll(items)
                v.invalidate()
                busy = false
                return
            }
            if (!pauseBlocked && wait < 8) {
                handler.postDelayed({ deliver(frame, items, emptyMsg, wait + 1) }, 250)
                return
            }
            // Layar jeda tidak bisa dibuka: pakai jendela overlay biasa
            Shared.frame = null
        }
        busy = false
        if (items.isEmpty()) {
            frame.recycle()
            toast(emptyMsg)
            return
        }
        val view = ResultView(this)
        view.frame = frame
        view.items.addAll(items)
        showWindow(view)
    }

    private fun gaul(s: String): String {
        var r = s
        for ((a, b) in slang) r = Regex("\\b$a\\b", RegexOption.IGNORE_CASE).replace(r, b)
        return r
    }

    private fun showWindow(view: ResultView) {
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
        // Tertutup sendiri setelah 60 detik supaya tidak menghalangi game selamanya
        handler.postDelayed({ if (overlay === view) dismiss() }, 60000)
    }

    private fun dismiss() {
        overlay?.let {
            try { wm.removeView(it) } catch (e: Exception) { }
            it.frame?.recycle()
            it.frame = null
        }
        overlay = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        Shared.current?.finish()
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
    var frame: Bitmap? = null
    var status: String? = null

    private val bg = Paint().apply { color = 0xE6101820.toInt() }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics
        )
    }

    override fun onDraw(canvas: Canvas) {
        // Layar dibekukan: tampilkan tangkapan layar, lalu digelapkan sedikit
        var sx = 1f
        var sy = 1f
        val f = frame
        if (f != null && !f.isRecycled) {
            sx = width.toFloat() / f.width
            sy = height.toFloat() / f.height
            canvas.drawBitmap(f, null, Rect(0, 0, width, height), null)
            canvas.drawColor(0x66000000)
        }
        val d = resources.displayMetrics.density
        val pad = (4 * d).toInt()
        val minW = (160 * d).toInt()
        for ((r, t) in items) {
            val rl = (r.left * sx).toInt()
            val rt = (r.top * sy).toInt()
            val rw = (r.width() * sx).toInt()
            val maxW = maxOf(rw, minW)
            val textW = Math.ceil(Layout.getDesiredWidth(t, tp).toDouble()).toInt()
            val inner = minOf(textW, maxW)
            val sl = StaticLayout.Builder.obtain(t, 0, t.length, tp, inner).build()
            val bw = inner + 2 * pad
            val bh = sl.height + 2 * pad
            val left = rl.coerceAtMost(width - bw).coerceAtLeast(0)
            val top = rt.coerceAtMost(height - bh).coerceAtLeast(0)
            canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            canvas.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), bg)
            canvas.translate(pad.toFloat(), pad.toFloat())
            sl.draw(canvas)
            canvas.restore()
        }
        val s = status
        if (s != null) {
            val pw = Layout.getDesiredWidth(s, tp)
            val x = (width - pw) / 2f
            val top = 12 * d
            canvas.drawRect(x - 12 * d, top, x + pw + 12 * d, top + tp.textSize + 16 * d, bg)
            canvas.drawText(s, x, top + 8 * d + tp.textSize * 0.85f, tp)
        }
    }
}
