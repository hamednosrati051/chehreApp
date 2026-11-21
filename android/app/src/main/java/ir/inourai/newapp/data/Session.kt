package ir.inourai.newapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/** نگهداری ساده‌ی توکن (برای نسخه‌ی اول؛ بعداً DataStore). */
object Session {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        darkThemeState.value = prefs.getBoolean("dark_theme", false)
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

    /** v18 (9): شماره‌ی نمایشِ پرومویی که کاربر دیده (همان
     *  SharedPreferences توکن)؛ مودال وقتی نشان داده می‌شود که شماره‌ی
     *  سرور بزرگ‌تر باشد. */
    var promoSeenShowNumber: Int
        get() = if (::prefs.isInitialized) prefs.getInt("promo_seen_show_number", 0) else 0
        set(value) {
            prefs.edit().putInt("promo_seen_show_number", value).apply()
        }

    /** v18 (8): تم تاریک دستی (پیش‌فرض روشن)؛ همان SharedPreferences
     *  توکن، کلید "dark_theme". darkThemeState حالتِ قابل‌مشاهده برای
     *  بازترکیب AppTheme است. */
    val darkThemeState = mutableStateOf(false)

    var darkTheme: Boolean
        get() = darkThemeState.value
        set(value) {
            darkThemeState.value = value
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean("dark_theme", value).apply()
            }
        }
}
