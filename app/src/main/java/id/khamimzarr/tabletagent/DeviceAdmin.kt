package id.khamimzarr.tabletagent

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat

/**
 * Device Admin minimal: satu-satunya kebijakan yang dipakai adalah force-lock,
 * supaya ShareMe bisa memanggil DevicePolicyManager.lockNow() dari jarak jauh.
 * Tidak ada izin wipe/reset password yang diminta.
 */
class DeviceAdmin : DeviceAdminReceiver() {

    companion object {
        fun component(ctx: Context) = ComponentName(ctx.applicationContext, DeviceAdmin::class.java)

        fun isActive(ctx: Context): Boolean = try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dpm.isAdminActive(component(ctx))
        } catch (e: Exception) {
            false
        }

        /** Intent popup "aktifkan admin perangkat" — harus diketuk sekali di layar tablet. */
        fun requestIntent(ctx: Context): Intent =
            Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
                .putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Dipakai ShareMe untuk mengunci layar tablet dari chat. Tidak ada izin hapus data."
                )

        /** Apakah app sudah boleh membaca app yang sedang dibuka (Usage Access). */
        fun hasUsageAccess(ctx: Context): Boolean {
            return try {
                val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    appOps.unsafeCheckOpNoThrow(
                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        ctx.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        ctx.packageName
                    )
                }
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
        }

        fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }
}
