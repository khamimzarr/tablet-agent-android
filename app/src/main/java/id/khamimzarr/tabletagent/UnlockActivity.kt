package id.khamimzarr.tabletagent

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Aktivitas tak terlihat yang dipakai untuk menutup keyguard.
 *
 * Android hanya mengizinkan requestDismissKeyguard() dari sebuah Activity, jadi
 * jalur ini yang dipakai: layar dibawa ke depan, keyguard ditutup, lalu activity
 * langsung selesai. Kalau perangkat memakai PIN/pola, sistem akan menampilkan
 * prompt keamanan — app biasa (bukan device owner) tidak boleh melewatinya.
 */
class UnlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val km = getSystemService(KeyguardManager::class.java)
        if (km == null) {
            log("unlock: KeyguardManager tidak ada")
            finish()
            return
        }

        if (!km.isKeyguardLocked) {
            log("unlock: layar sudah terbuka")
            finish()
            return
        }

        try {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    log("unlock: keyguard ditutup")
                    finish()
                }

                override fun onDismissError() {
                    log("unlock: ditolak sistem (kemungkinan butuh PIN)")
                    finish()
                }

                override fun onDismissCancelled() {
                    log("unlock: dibatalkan")
                    finish()
                }
            })
        } catch (e: Exception) {
            log("unlock gagal: ${e.message}")
            finish()
        }
    }

    private fun log(msg: String) {
        try {
            File(filesDir, "agent.log").appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Exception) {
        }
    }
}
