package ir.inourai.newapp.data

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** علاقه‌مندی‌ها (قلب روی دنیاها) — کش درون‌حافظه‌ای مشترک همه‌ی صفحه‌ها.
 *  وقتی لاگین نیست، قلب‌ها نشان داده نمی‌شوند و toggle کاری نمی‌کند. */
object Favorites {
    val ids: MutableState<Set<Long>> = mutableStateOf(emptySet())

    suspend fun refresh() {
        if (Session.token == null) {
            ids.value = emptySet()
            return
        }
        try {
            ids.value = Api.service.favorites().map { it.id }.toSet()
        } catch (_: Exception) {
        }
    }

    suspend fun toggle(worldId: Long) {
        if (Session.token == null) return
        try {
            if (worldId in ids.value) {
                Api.service.removeFavorite(worldId)
                ids.value = ids.value - worldId
            } else {
                Api.service.addFavorite(FavoriteRequest(worldId))
                ids.value = ids.value + worldId
            }
        } catch (_: Exception) {
        }
    }
}
