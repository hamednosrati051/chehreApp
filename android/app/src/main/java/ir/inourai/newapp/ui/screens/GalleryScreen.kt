package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.GalleryItem
import kotlinx.coroutines.launch

/**
 * گالری تصاویر کاربران: فقط عکس‌های تاییدشده‌ی ادمین (منتشرشده در اپ).
 * بدون لاگین هم باز می‌شود؛ داده از /gallery/public می‌آید.
 * v18 (6+7): برچسب طلایی «کیفیت بالا» فقط روی عکس‌های پولی + نام دنیا
 * زیر هر عکس (لمس → صفحه‌ی ساخت همان دنیا). /gallery/public حالا
 * world_id هم می‌فرستد، پس تطابق با نام لازم نیست.
 */
@Composable
fun GalleryScreen(onGoHome: () -> Unit, onWorld: (Long) -> Unit = {}) {
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    // نمایشگر تمام‌صفحه (همان نمایشگر «تصاویر من»، بدون دکمه‌ی ارسال؛
    // قلب فقط وقتی generationId عکس معلوم باشد):
    // null یعنی بسته است.
    var viewerPhotos by remember { mutableStateOf<List<GalleryPhoto>?>(null) }
    var viewerIndex by remember { mutableStateOf(0) }

    // ضربه به تب «نگارخانه» وقتی همین صفحه بالاست، در ناوبری هیچ کاری
    // نمی‌کند؛ با این سیگنال نمایشگر باز بسته می‌شود تا کاربر روی همان
    // صفحه گیر نکند (همان گیرکردن تب «تصاویر من» با نمایشگر باز).
    val resetTick = GalleryReset.tick
    LaunchedEffect(resetTick) {
        if (resetTick > 0) viewerPhotos = null
    }

    // رفرش با کشیدن پایین، همان نگارخانه را تازه می‌کند (فهرست عکس‌های
    // منتشرشده) و هرگز به خانه برنمی‌گردد؛ عکس‌های قبلی تا آمدن تازه‌ها
    // سر جایشان می‌مانند تا صفحه چشمک نزند.
    fun refreshGallery() {
        scope.launch {
            try {
                items = Api.service.publicGallery()
            } catch (_: Exception) {
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshGallery()
    }

    PullRefreshLayout(onRefresh = { RefreshBus.refresh(); refreshGallery() }) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "نگارخانه کاربران",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("هنوز عکسی در نگارخانه منتشر نشده است.")
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    // v18 (6+7): برچسب طلایی فقط روی پولی؛ نام دنیا زیر
                    // عکس (فقط نام، بدون اعلان ۲۴ ساعته). world_id از
                    // سرور می‌آید؛ تطابق با نام لازم نیست.
                    val worldId = item.world_id
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    viewerPhotos = items.map {
                                        GalleryPhoto(
                                            id = it.id,
                                            outputUrl = it.image_url,
                                            generationId = it.generation_id,
                                            isFree = it.is_free,
                                            worldName = it.world_name,
                                            worldId = it.world_id,
                                        )
                                    }
                                    viewerIndex = index
                                },
                        ) {
                            AsyncImage(
                                model = item.image_url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (!item.is_free) {
                                QualityChip(
                                    isFree = false,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                                )
                            }
                        }
                        if (item.world_name != null) {
                            WorldNameText(
                                name = item.world_name,
                                onClick = worldId?.let { id -> { onWorld(id) } },
                            )
                        }
                    }
                }
            }
        }
    }
    }

    viewerPhotos?.let { photos ->
        GalleryViewer(
            photos = photos,
            startIndex = viewerIndex,
            onClose = { viewerPhotos = null },
            onWorld = { worldId ->
                viewerPhotos = null
                onWorld(worldId)
            },
        )
    }
}
