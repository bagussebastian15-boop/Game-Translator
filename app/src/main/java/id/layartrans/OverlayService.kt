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

/** Satu paragraf teks: posisi di layar, isi, tinggi baris asli, dan skor kualitas bacaan. */
class Item(val rect: Rect, val text: String, val lineH: Int, val score: Float = 0f)

class OverlayService : Service() {

    companion object {
        @Volatile
        var running = false
    }

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
    private var tappedPause = false

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
        running = true
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

    // ---------------- OCR: baca beberapa kali, gabung per paragraf ----------------

    /** Buang teks sampah: terlalu pendek, atau kebanyakan angka/simbol (HUD, tombol, skor, jam). */
    private fun worthIt(t: String, src: String): Boolean {
        val asian = src != "en"
        val letters = t.count { it.isLetter() }
        val visible = t.count { !it.isWhitespace() }
        if (letters < (if (asian) 2 else 3)) return false
        return letters * 100 / maxOf(visible, 1) >= 60
    }

    /** Perbaiki salah baca umum pada teks Latin. */
    private fun fixToken(tok: String): String {
        val letters = tok.count { it.isLetter() }
        val digits = tok.count { it.isDigit() }
        if (letters < 3 || digits != 1 || !tok.all { it.isLetterOrDigit() }) return tok
        val upper = tok.filter { it.isLetter() }.all { it.isUpperCase() }
        return tok.map { c ->
            when (c) {
                '0' -> if (upper) 'O' else 'o'
                '1' -> if (upper) 'I' else 'l'
                '5' -> if (upper) 'S' else 's'
                else -> c
            }
        }.joinToString("")
    }

    /** TEKS HURUF BESAR SEMUA diubah ke huruf normal supaya terjemahannya lebih akurat. */
    private fun sentenceCase(t: String): String {
        val letters = t.filter { it.isLetter() }
        if (letters.length < 4 || letters.any { it.isLowerCase() }) return t
        val sb = StringBuilder()
        var cap = true
        for (c in t.lowercase()) {
            if (cap && c.isLetter()) {
                sb.append(c.uppercaseChar())
                cap = false
            } else {
                sb.append(c)
            }
            if (c == '.' || c == '!' || c == '?') cap = true
        }
        return sb.toString()
    }

    private fun normalize(src: String, raw: String): String {
        var t = raw.trim()
        if (src != "en") return t
        t = t.replace('|', 'I')
        t = t.split(' ').joinToString(" ") { fixToken(it) }
        t = sentenceCase(t)
        t = Regex("\\bi\\b").replace(t, "I")
        return t
    }

    /** Apakah baris b adalah lanjutan paragraf yang sama dengan baris a (a tepat di atas b)? */
    private fun sameParagraph(a: Rect, b: Rect): Boolean {
        val ha = maxOf(a.height(), 1)
        val hb = maxOf(b.height(), 1)
        val ratio = ha.toFloat() / hb
        if (ratio < 0.6f || ratio > 1.67f) return false
        val hMax = maxOf(ha, hb)
        val gap = b.top - a.bottom
        if (gap < -hMax / 2 || gap > hMax * 0.9f) return false
        val ox = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val minW = minOf(a.width(), b.width())
        return ox > 0.25f * minW || Math.abs(a.left - b.left) < 1.5f * hMax
    }

    /** Gabungkan baris-baris yang berdekatan menjadi paragraf utuh, urut dari atas ke bawah. */
    private fun group(src: String, lines: List<Pair<Rect, String>>): List<Item> {
        val asian = src != "en"
        val sorted = lines.sortedWith(compareBy<Pair<Rect, String>>({ it.first.top }, { it.first.left }))
        val paras = ArrayList<ArrayList<Pair<Rect, String>>>()
        for (ln in sorted) {
            var target: ArrayList<Pair<Rect, String>>? = null
            for (p in paras) {
                if (sameParagraph(p[p.size - 1].first, ln.first)) {
                    target = p
                    break
                }
            }
            if (target == null) {
                val np = ArrayList<Pair<Rect, String>>()
                np.add(ln)
                paras.add(np)
            } else {
                target.add(ln)
            }
        }
        val out = ArrayList<Item>()
        for (p in paras) {
            val rect = Rect(p[0].first)
            var sumH = 0
            val sb = StringBuilder()
            for ((i, ln) in p.withIndex()) {
                rect.union(ln.first)
                sumH += ln.first.height()
                if (i > 0 && !asian) {
                    if (sb.endsWith("-") && ln.second.firstOrNull()?.isLowerCase() == true) {
                        sb.setLength(sb.length - 1)
                    } else {
                        sb.append(' ')
                    }
                }
                sb.append(ln.second)
            }
            val text = normalize(src, sb.toString())
            if (!worthIt(text, src)) continue
            val letters = text.count { it.isLetter() }
            val visible = text.count { !it.isWhitespace() }
            out.add(Item(rect, text, sumH / p.size, letters.toFloat() * letters / maxOf(visible, 1)))
        }
        return out
    }

    private fun ocr(src: String, bmp: Bitmap, done: (List<Item>) -> Unit) {
        recognizer(src).process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val lines = ArrayList<Pair<Rect, String>>()
                for (b in text.textBlocks) {
                    for (l in b.lines) {
                        val r = l.boundingBox ?: continue
                        val t = l.text.trim()
                        if (t.isNotEmpty()) lines.add(Pair(r, t))
                    }
                }
                done(group(src, lines))
            }
            .addOnFailureListener {
                toast("OCR gagal. Coba lagi.")
                done(emptyList())
            }
    }

    /** Abu-abu + kontras tinggi. */
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

    /** Sisakan hanya piksel terang (teks putih) atau gelap (teks hitam): hitam di atas putih. */
    private fun isolate(src: Bitmap, bright: Boolean): Bitmap {
        val wd = src.width
        val ht = src.height
        val px = IntArray(wd * ht)
        src.getPixels(px, 0, wd, 0, 0, wd, ht)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        for (i in px.indices) {
            val c = px[i]
            val lum = (((c shr 16) and 0xFF) * 30 + ((c shr 8) and 0xFF) * 59 + (c and 0xFF) * 11) / 100
            val on = if (bright) lum > 185 else lum < 70
            px[i] = if (on) black else white
        }
        val out = Bitmap.createBitmap(wd, ht, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, wd, 0, 0, wd, ht)
        return out
    }

    private fun overlaps(a: Rect, b: Rect): Boolean {
        val i = Rect()
        if (!i.setIntersect(a, b)) return false
        val ia = i.width().toLong() * i.height()
        val m = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return m > 0 && ia * 100 / m > 30
    }

    /** Dari beberapa hasil baca yang saling tumpang tindih, ambil yang skornya terbaik. */
    private fun merge(all: List<Item>): List<Item> {
        val sorted = all.sortedByDescending { it.score }
        val out = ArrayList<Item>()
        for (c in sorted) {
            if (out.none { overlaps(it.rect, c.rect) }) out.add(c)
        }
        return out.sortedWith(compareBy<Item>({ it.rect.top }, { it.rect.left }))
    }

    private fun pass(
        src: String,
        bmp: Bitmap,
        ids: List<Int>,
        i: Int,
        acc: ArrayList<Item>,
        done: (List<Item>) -> Unit
    ) {
        if (i >= ids.size) {
            done(merge(acc))
            return
        }
        val img = when (ids[i]) {
            1 -> isolate(bmp, true)
            2 -> isolate(bmp, false)
            3 -> enhance(bmp)
            else -> bmp
        }
        ocr(src, img) { found ->
            if (img !== bmp) img.recycle()
            acc.addAll(found)
            pass(src, bmp, ids, i + 1, acc, done)
        }
    }

    private fun process(bmp: Bitmap) {
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        if (prefs.getBoolean("calibrate", false)) {
            startCalibration(bmp)
            return
        }
        val src = prefs.getString("src", "en") ?: "en"
        val thorough = prefs.getBoolean("thorough", true)
        val tapMode = prefs.getBoolean("tappause", false)
        pauseMode = prefs.getBoolean("pause", false) && !tapMode
        // Jeda dulu, OCR + terjemahan menyusul di belakang
        if (tapMode) pauseTap()
        if (pauseMode) openPause(bmp)
        val ids = if (thorough) listOf(0, 1, 2, 3) else listOf(0)
        pass(src, bmp, ids, 0, ArrayList()) { items ->
            translateAll(src, bmp, items)
        }
    }

    // ---- Ketuk tombol pause game lewat layanan Aksesibilitas (opsional, default mati) ----

    private fun tapSaved(): Boolean {
        val p = getSharedPreferences("p", Context.MODE_PRIVATE)
        if (!p.contains("fx")) return false
        return TapService.tap(p.getFloat("fx", 0f) * w, p.getFloat("fy", 0f) * h)
    }

    private fun pauseTap() {
        val p = getSharedPreferences("p", Context.MODE_PRIVATE)
        if (!p.contains("fx")) {
            toast("Posisi tombol pause belum diatur")
            return
        }
        if (tapSaved()) tappedPause = true else toast("Layanan Aksesibilitas belum aktif")
    }

    private fun resumeIfNeeded() {
        if (!tappedPause) return
        tappedPause = false
        if (getSharedPreferences("p", Context.MODE_PRIVATE).getBoolean("tapback", false)) {
            handler.postDelayed({ tapSaved() }, 250)
        }
    }

    private fun startCalibration(bmp: Bitmap) {
        busy = false
        val view = ResultView(this)
        view.frame = bmp
        view.status = "Ketuk tombol PAUSE game di gambar ini"
        view.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                getSharedPreferences("p", Context.MODE_PRIVATE).edit()
                    .putFloat("fx", e.x / v.width)
                    .putFloat("fy", e.y / v.height)
                    .putBoolean("calibrate", false)
                    .apply()
                toast("Posisi tombol pause tersimpan")
                dismiss()
            }
            true
        }
        showWindow(view)
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

    // ---------------- Terjemahan ----------------

    private fun translateAll(src: String, frame: Bitmap, items: List<Item>) {
        if (items.isEmpty()) {
            deliver(frame, emptyList())
            return
        }
        val tr = translator(src)
        tr.downloadModelIfNeeded()
            .addOnSuccessListener {
                val out = ArrayList<Item>()
                var left = items.size
                for (item in items) {
                    tr.translate(item.text)
                        .addOnSuccessListener { result ->
                            val g = gaul(result)
                            // Nama/teks yang hasilnya sama dengan aslinya tidak perlu ditampilkan
                            if (!g.equals(item.text, ignoreCase = true)) {
                                out.add(Item(item.rect, g, item.lineH))
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
        items: List<Item>,
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
            resumeIfNeeded()
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
        closeWindow()
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

    private fun closeWindow() {
        overlay?.let {
            try { wm.removeView(it) } catch (e: Exception) { }
            it.frame?.recycle()
            it.frame = null
        }
        overlay = null
    }

    private fun dismiss() {
        val had = overlay != null
        closeWindow()
        if (had) resumeIfNeeded()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        running = false
        Shared.current?.finish()
        closeWindow()
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

    val items = ArrayList<Item>()
    var frame: Bitmap? = null
    var status: String? = null

    private val bg = Paint().apply { color = 0xE6101820.toInt() }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
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
        val dm = resources.displayMetrics
        val d = dm.density
        val pad = (4 * d).toInt()
        val minW = (160 * d).toInt()
        val minSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, dm)
        val maxSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26f, dm)
        for (item in items) {
            val r = item.rect
            val t = item.text
            // Ukuran teks hasil menyesuaikan ukuran teks asli di game
            tp.textSize = (item.lineH * sy * 0.62f).coerceIn(minSize, maxSize)
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
            val pw = Layout.getDesiredWidth(s, sp)
            val x = (width - pw) / 2f
            val top = 12 * d
            canvas.drawRect(x - 12 * d, top, x + pw + 12 * d, top + sp.textSize + 16 * d, bg)
            canvas.drawText(s, x, top + 8 * d + sp.textSize * 0.85f, sp)
        }
    }
}
