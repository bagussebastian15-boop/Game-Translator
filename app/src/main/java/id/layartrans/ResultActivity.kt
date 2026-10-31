package id.layartrans

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

object Shared {
    var frame: Bitmap? = null
    var current: Activity? = null
    var view: ResultView? = null
    var opens = 0
    var closed = false
}

class ResultActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = Shared.frame
        if (f == null || f.isRecycled) {
            finish()
            return
        }
        requestedOrientation = if (f.width > f.height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val a = window.attributes
            a.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = a
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        val v = ResultView(this)
        v.frame = f
        v.status = "Menerjemahkan…"
        v.setOnClickListener { finish() }
        setContentView(v)
        Shared.view = v
        Shared.current = this
        Shared.opens++
    }

    override fun onDestroy() {
        Shared.view = null
        Shared.current = null
        Shared.frame = null
        Shared.closed = true
        super.onDestroy()
    }
}
