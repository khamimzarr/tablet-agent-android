package id.khamimzarr.tabletagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: ConfigStore
    private lateinit var etServer: TextInputEditText
    private lateinit var etToken: TextInputEditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val required = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val ticker = object : Runnable {
        override fun run() {
            val s = AgentService.status
            tvStatus.text = if (AgentService.running) "● $s" else "○ $s"
            val log = java.io.File(filesDir, "agent.log")
            if (log.exists() && log.length() > 0) {
                tvLog.text = log.readLines().takeLast(4).joinToString("\n") { it.substringAfter(" ") }
                tvLog.visibility = android.view.View.VISIBLE
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = ConfigStore(this)

        etServer = findViewById(R.id.etServer)
        etToken = findViewById(R.id.etToken)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        etServer.setText(prefs.server)
        etToken.setText(prefs.token)

        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener { startAgent() }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener { stopAgent() }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { askBatteryExemption() }

        ui.post(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun startAgent() {
        val server = etServer.text?.toString()?.trim().orEmpty()
        val token = etToken.text?.toString()?.trim().orEmpty()
        if (server.isEmpty() || token.isEmpty()) {
            toast("server dan token wajib diisi")
            return
        }
        if (!server.startsWith("ws://") && !server.startsWith("wss://")) {
            toast("alamat server harus diawali ws:// atau wss://")
            return
        }
        prefs.server = server
        prefs.token = token

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ)
            return
        }
        launchService()
    }

    private fun launchService() {
        val i = Intent(this, AgentService::class.java).setAction(AgentService.ACTION_START)
        try {
            ContextCompat.startForegroundService(this, i)
            toast("agen dinyalakan")
        } catch (e: Exception) {
            tvStatus.text = "gagal menyalakan service: ${e.message}"
        }
    }

    private fun stopAgent() {
        val i = Intent(this, AgentService::class.java).setAction(AgentService.ACTION_STOP)
        startService(i)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ) return
        val denied = grantResults.indices.filter { grantResults[it] != PackageManager.PERMISSION_GRANTED }
        if (denied.isEmpty()) {
            launchService()
        } else {
            val names = denied.map { permissions[it].substringAfterLast('.') }
            toast("izin ditolak: ${names.joinToString(", ")}")
        }
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("sudah dikecualikan dari optimasi baterai")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ = 101
    }
}