package ir.inourai.newapp

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CRASH_FILE = "last_crash.txt"

/**
 * ثبت‌کننده‌ی کرش: هر خطای مهارنشده (از جمله کرش‌های Compose روی نخ اصلی)
 * را با زمان و stack trace در حافظه‌ی داخلی اپ ذخیره می‌کند، بعد رفتار
 * پیش‌فرض سیستم را ادامه می‌دهد تا اپ مثل قبل بسته شود (نه هنگ کند).
 * گزارش از «پروفایل > گزارش خطا» خوانده و کپی می‌شود.
 */
fun installCrashHandler(context: Context) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        try {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = "زمان: $stamp\nنخ: ${thread.name}\n${error::class.java.name}: ${error.message}\n${sw}"
            File(context.filesDir, CRASH_FILE).writeText(text)
        } catch (_: Exception) {
        }
        if (previous != null) {
            previous.uncaughtException(thread, error)
        } else {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(1)
        }
    }
}

fun readCrashLog(context: Context): String? {
    return try {
        val f = File(context.filesDir, CRASH_FILE)
        if (f.exists()) f.readText().take(3000) else null
    } catch (_: Exception) {
        null
    }
}

fun clearCrashLog(context: Context) {
    try {
        File(context.filesDir, CRASH_FILE).delete()
    } catch (_: Exception) {
    }
}
