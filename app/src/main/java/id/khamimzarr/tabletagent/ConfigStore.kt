package id.khamimzarr.tabletagent

import android.content.Context

class ConfigStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("tablet_agent", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", "ws://43.159.35.225:2198").orEmpty()
        set(v) = p.edit().putString("server", v).apply()

    var token: String
        get() = p.getString("token", "").orEmpty()
        set(v) = p.edit().putString("token", v).apply()
}
