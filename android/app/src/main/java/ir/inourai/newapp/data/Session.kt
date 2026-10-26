package ir.inourai.newapp.data

import android.content.Context
import android.content.SharedPreferences

/** نگهداری ساده‌ی توکن (برای نسخه‌ی اول؛ بعداً DataStore). */
object Session {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    }

    var token: String?
        get() = if (::prefs.isInitialized) prefs.getString("token", null) else null
        set(value) {
            prefs.edit().putString("token", value).apply()
        }

    fun logout() {
        prefs.edit().remove("token").apply()
    }

    /** تازه ثبت‌نام کرده (برای توست هدیه‌ی ورود)؛ فقط در حافظه است. */
    var justRegistered: Boolean = false
}
