package ir.inourai.newapp.data

import java.io.IOException
import retrofit2.HttpException

/** حداکثر حجم آپلود که سرور قبول می‌کند (۲۰ مگابایت)؛ اپ خودش هم
 *  همین سقف را پیش از آپلود چک می‌کند تا دلیل رد مشخص باشد. */
const val UPLOAD_MAX_BYTES = 20L * 1024 * 1024

/** خطای کاربرپسند: متن کوتاه فارسی + اینکه «تلاش دوباره» معنا دارد یا نه.
 *  هیچ‌وقت متن خام exception، کد HTTP، آدرس یا عدد داخلی اینجا نمی‌آید. */
data class UserError(val message: String, val retryable: Boolean)

fun networkError(): UserError =
    UserError("مشکل شبکه؛ دوباره تلاش کن", true)

fun genericError(): UserError =
    UserError("خطایی پیش آمد؛ دوباره تلاش کن", true)

/**
 * نگاشت هر exception به پیام کاربرپسند. ترتیب مهم است: اول کدهای
 * داخلی آپلود (که uploadFile پرتاب می‌کند)، بعد HTTP، بعد شبکه،
 * و در انتها پیام کلی — هیچ شاخه‌ای متن خام برنمی‌گرداند.
 */
fun userError(e: Exception): UserError {
    val msg = e.message ?: ""
    when {
        msg == "upload_too_big" ->
            return UserError("عکس بزرگ‌تر از ۲۰ مگابایت است", false)
        msg == "upload_conversion" ->
            return UserError("تبدیل عکس ناموفق بود؛ عکس دیگری را امتحان کن", false)
        msg == "upload_failed" ->
            return networkError()
        msg.contains("content_policy_violation") || msg.contains("safety system") ->
            return UserError(
                "رد ایمنی مدل؛ عکس یا دنیای دیگری را امتحان کن — سکه‌ات کم نشده است.",
                false,
            )
    }
    if (e is HttpException) {
        return when (e.code()) {
            401 -> UserError("برای ادامه وارد شوید", false)
            402 -> UserError("سکه کافی نداری", false)
            429 -> UserError("تعداد درخواست زیاد است؛ کمی بعد دوباره تلاش کن", false)
            in 500..599 -> UserError("مشکل سرور؛ دوباره تلاش کن", true)
            else -> genericError()
        }
    }
    if (e is IOException) return networkError()
    return genericError()
}

/** خطاهای خرید بازار: لغو و نتیجه‌ی ناخوانا جدا هندل می‌شوند؛
 *  بقیه‌ی خطاها (سرویس بازار، وریفای سروری، شبکه) یک پیام کوتاه دارند. */
fun billingErrorMessage(e: Exception): String {
    if (e is IOException) return "خرید ناموفق بود؛ دوباره تلاش کن"
    return "خرید ناموفق بود؛ دوباره تلاش کن"
}
