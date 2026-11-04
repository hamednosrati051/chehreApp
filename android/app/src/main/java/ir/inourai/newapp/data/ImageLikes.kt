package ir.inourai.newapp.data

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** لایک تصویرها (قلب روی نتیجه) — کش درون‌حافظه‌ای مشترک همه‌ی صفحه‌ها،
 *  هم‌شکل Favorites ولی با شناسه‌ی generation به‌جای شناسه‌ی دنیا.
 *  وقتی لاگین نیست، قلب نشان داده نمی‌شود و toggle کاری نمی‌کند.
 *  toggle فقط وقتی پاسخ موفق (2xx) بود کش محلی را عوض می‌کند و true
 *  برمی‌گرداند؛ در غیر این صورت false تا فراخواننده خطا نشان بدهد
 *  (پاسخ ناموفق استثنا نمی‌اندازد، پس بررسی isSuccessful لازم است). */
object ImageLikes {
    val ids: MutableState<Set<Long>> = mutableStateOf(emptySet())

    suspend fun refresh() {
        if (Session.token == null) {
            ids.value = emptySet()
            return
        }
        try {
            ids.value = Api.service.imageLikes().map { it.id }.toSet()
        } catch (_: Exception) {
        }
    }

    suspend fun toggle(genId: Long): Boolean {
        if (Session.token == null) return false
        return try {
            if (genId in ids.value) {
                val res = Api.service.unlikeImage(genId)
                if (!res.isSuccessful) return false
                ids.value = ids.value - genId
            } else {
                val res = Api.service.likeImage(ImageLikeRequest(genId))
                if (!res.isSuccessful) return false
                ids.value = ids.value + genId
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
