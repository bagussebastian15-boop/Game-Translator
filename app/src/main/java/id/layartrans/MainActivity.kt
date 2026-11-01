package id.layartrans

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val langs = listOf("Inggris" to "en", "Jepang" to "ja", "Korea" to "ko", "Mandarin" to "zh")
    private lateinit var spinner: Spinner
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Game Translate\n\n1. Pilih bahasa teks di game.\n2. Tekan Mulai, izinkan overlay & rekam layar.\n" +
                "3. Buka game, ketuk bubble 文 untuk menerjemahkan layar.\n4. Ketuk hasil terjemahan untuk menutup.\n" +
                "Tahan bubble lama untuk berhenti."
            textSize = 16f
        })
        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                langs.map { it.first }
            )
        }
        val saved = prefs().getString("src", "en")
        spinner.setSelection(langs.indexOfFirst { it.second == saved }.coerceAtLeast(0))
        root.addView(spinner)
        root.addView(sw("Mode teliti (lebih akurat, lebih berat)", "thorough"))
        root.addView(sw("Jeda game: layar penuh (cadangan)", "pause"))
        root.addView(sw("Ketuk tombol pause game otomatis", "tappause"))
        root.addView(sw("Ketuk lagi saat hasil ditutup (lanjutkan game)", "tapback"))
        root.addView(btn("1. Aktifkan layanan Aksesibilitas") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("2. Atur posisi tombol pause") { calibrate() })
        info = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(info)
        root.addView(btn("Mulai") { start() })
        root.addView(btn("Berhenti") {
            stopService(Intent(this@MainActivity, OverlayService::class.java))
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val acc = if (TapService.instance != null) "aktif" else "belum aktif"
        val pos = if (prefs().contains("fx")) "sudah diatur" else "belum diatur"
        info.text = "Aksesibilitas: $acc\nPosisi tombol pause: $pos"
    }

    private fun sw(label: String, key: String) = Switch(this).apply {
        text = label
        val p = (8 * resources.displayMetrics.density).toInt()
        setPadding(0, p, 0, p)
        isChecked = prefs().getBoolean(key, key == "thorough")
        setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean(key, on).apply() }
    }

    private fun btn(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun prefs() = getSharedPreferences("p", MODE_PRIVATE)

    private fun calibrate() {
        if (!OverlayService.running) {
            Toast.makeText(this, "Tekan Mulai dulu", Toast.LENGTH_LONG).show()
            return
        }
        prefs().edit().putBoolean("calibrate", true).apply()
        Toast.makeText(
            this,
            "Buka game sampai tombol pause terlihat, lalu ketuk bubble 文",
            Toast.LENGTH_LONG
        ).show()
        moveTaskToBack(true)
    }

    private fun start() {
        prefs().edit()
            .putString("src", langs[spinner.selectedItemPosition].second)
            .putBoolean("calibrate", false)
            .apply()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan 'tampil di atas aplikasi lain', lalu tekan Mulai lagi", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
