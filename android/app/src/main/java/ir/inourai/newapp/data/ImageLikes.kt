package ir.inourai.newapp.data

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** لایک تصویرها (قلب روی نتیجه) — کش درون‌حافظه‌ای مشترک همه‌ی صفحه‌ها،
 *  هم‌شکل Favorites ولی با شناسه‌ی generation به‌جای شناسه‌ی دنیا.
 *  وقتی لاگین نیست، قلب نشان داده نمی‌شود و toggle کاری نمی‌کند. */
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

    suspend fun toggle(genId: Long) {
        if (Session.token == null) return
        try {
            if (genId in ids.value) {
                Api.service.unlikeImage(genId)
                ids.value = ids.value - genId
            } else {
                Api.service.likeImage(ImageLikeRequest(genId))
                ids.value = ids.value + genId
            }
        } catch (_: Exception) {
        }
    }
}
