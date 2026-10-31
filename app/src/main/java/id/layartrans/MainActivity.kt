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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val langs = listOf("Inggris" to "en", "Jepang" to "ja", "Korea" to "ko", "Mandarin" to "zh")
    private lateinit var spinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Layar Trans\n\n1. Pilih bahasa teks di game.\n2. Tekan Mulai, izinkan overlay & rekam layar.\n" +
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
        root.addView(Button(this).apply {
            text = "Mulai"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "Berhenti"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        setContentView(root)
    }

    private fun prefs() = getSharedPreferences("p", MODE_PRIVATE)

    private fun start() {
        prefs().edit().putString("src", langs[spinner.selectedItemPosition].second).apply()
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
