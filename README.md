# Tablet Agent (Android)

Pengganti klien Termux (`tablet_agent_ws.js`) sebagai aplikasi Android native.
Menghubungkan tablet langsung ke server agen (WebSocket + AES-256-CBC) tanpa Termux,
node, atau file config.

## Cara pakai

1. Install APK.
2. Buka app → isi **Alamat server** (mis. `ws://43.159.35.225:2198`) dan **Token** (`WS_SECRET_TOKEN` di `.env` server).
3. Izinkan kamera, mikrofon, lokasi, dan notifikasi.
4. Tekan **Mulai**. Notifikasi tetap muncul = agen hidup.
5. Tekan **Izinkan tetap jalan di latar belakang** supaya Android tidak membekukan app saat layar mati.

Kalau klien Termux lama masih jalan, hentikan dulu: server hanya menyimpan satu koneksi aktif.

## Aksi yang didukung

| aksi | perilaku |
|---|---|
| `get_system_info` | laporan tree CPU/RAM/baterai/Wi-Fi (format sama dengan klien Node) |
| `get_location` | link Google Maps dari GPS/jaringan |
| `take_photo` | CameraX, `camera_id` 0 = belakang, 1 = depan, dikirim sebagai JPEG |
| `record_audio` | MediaRecorder AAC/m4a, durasi dari `duration` |
| `play_alarm` | TTS 3x, fallback nada dering alarm |
| `test_cmd` | hanya `termux-torch on/off`, `termux-vibrate`, `termux-toast`; shell bebas tidak mungkin di app non-root |
| `read_wa` | dinonaktifkan (sama seperti klien Termux) |

## Protokol

Sama dengan klien Node: pesan teks `"<iv_hex>:<ct_hex>"` AES-256-CBC dengan kunci
`sha256(token)`, hello `{client, version}` saat connect, balasan `{result, message_id}`,
media dikirim sebagai frame biner sebelum teks konfirmasi (server mengenali JPEG dari
`FF D8 FF`). Heartbeat: OkHttp ping tiap 25 detik, server memutus klien yang tidak
menjawab pong.

## Build

APK dibangun GitHub Actions (`.github/workflows/build.yml`), bukan lokal:

```
gradle :app:assembleDebug
```

Artefak: `app/build/outputs/apk/debug/app-debug.apk`.

Catatan: build ini memakai debug keystore yang dibuat ulang tiap build, jadi memperbarui
APK di atas versi lama bisa ditolak ("app not installed") — uninstall dulu.