package ir.inourai.newapp.ui.screens

import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.GallerySubmitRequest
import ir.inourai.newapp.data.ImageLikes
import java.io.File
import kotlinx.coroutines.launch

private val GalleryViewerBg = Color(0xFF0F172A)

/** یک عکس گالری برای نمایشگر: شناسه‌ی نمایشی + خروجی + شناسه‌ی
 *  generation (برای قلب؛ در گالری عمومی ممکن است null باشد و آن‌وقت
 *  قلبی نشان داده نمی‌شود). */
data class GalleryPhoto(
    val id: Long,
    val outputUrl: String,
    val generationId: Long? = null,
)

/** نمایشگر تمام‌صفحه‌ی گالری: فقط عکس + ورق‌زدن بین عکس‌ها + دانلود +
 *  اشتراک‌گذاری + دکمه‌ی برگشت بالای عکس. در «تصاویر من» و فقط برای
 *  عکس‌های ساخته‌شده‌ی خود کاربر که هنوز ارسال نشده‌اند، یک دکمه‌ی
 *  ارسال به گالری عمومی هم هست (همان منطق صفحه‌ی نتیجه: اول دیالوگ
 *  تایید، بعد از ارسال موفق دکمه دیگر به‌صورت ارسال‌نشده برنمی‌گردد).
 *  در گالری عمومی، وقتی generationId عکس معلوم باشد، قلب هم هست
 *  (بدون generationId قلبی نیست؛ قلب فقط با پاسخ موفق سرور عوض می‌شود
 *  وگرنه خطای کوتاه نشان داده می‌شود). منطق دانلود/اشتراک‌گذاری
 *  عمداً همین‌جا تکرار شده تا صفحه‌ی نتیجه دست نخورد؛ تغییر یکی،
 *  دیگری را نمی‌شکند. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryViewer(
    photos: List<GalleryPhoto>,
    startIndex: Int,
    onClose: () -> Unit,
    showSendButton: Boolean = false,
    submittedIds: Set<Long> = emptySet(),
    onSent: (Long) -> Unit = {},
    showHeart: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val likedIds by ImageLikes.ids
    var notice by remember { mutableStateOf<String?>(null) }
    var showSendConfirm by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2500)
            notice = null
        }
    }

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)),
    ) { photos.size }
    val current = photos.getOrNull(pagerState.currentPage)

    fun sharePhoto(photo: GalleryPhoto) {
        scope.launch {
            try {
                val bytes = downloadResultBytes(photo.outputUrl)
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, "result_${photo.id}.jpg").apply { writeBytes(bytes) }
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "اشتراک‌گذاری تصویر"))
            } catch (_: Exception) {
                notice = "اشتراک‌گذاری ناموفق بود؛ دوباره تلاش کن"
            }
        }
    }

    fun downloadPhoto(photo: GalleryPhoto) {
        scope.launch {
            try {
                val bytes = downloadResultBytes(photo.outputUrl)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "chehresaz_${photo.id}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChehreSaz")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw RuntimeException("media store failed")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                notice = "دانلود شد"
            } catch (_: Exception) {
                notice = "دانلود ناموفق بود؛ دوباره تلاش کن"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(GalleryViewerBg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.ArrowForward, contentDescription = null, tint = Color.White)
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                val photo = photos[page]
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = photo.outputUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { current?.let { downloadPhoto(it) } }) {
                    Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White)
                }
                IconButton(onClick = { current?.let { sharePhoto(it) } }) {
                    Icon(Icons.Rounded.Share, contentDescription = null, tint = Color.White)
                }
                Spacer(modifier = Modifier.weight(1f))
                if (showSendButton && current != null && current.id !in submittedIds) {
                    IconButton(onClick = { showSendConfirm = true }) {
                        Icon(Icons.Rounded.Send, contentDescription = null, tint = Color.White)
                    }
                }
                if (showHeart) {
                    val heartId = current?.generationId
                    if (heartId != null) {
                        IconButton(onClick = {
                            scope.launch {
                                val wasLiked = heartId in likedIds
                                val ok = ImageLikes.toggle(heartId)
                                if (!ok) {
                                    notice = if (wasLiked) "لغو پسند ناموفق بود؛ دوباره تلاش کن"
                                    else "پسندیدن ناموفق بود؛ دوباره تلاش کن"
                                }
                            }
                        }) {
                            Icon(
                                if (heartId in likedIds) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = null,
                                tint = if (heartId in likedIds) Color(0xFFFF5A5A) else Color.White,
                            )
                        }
                    }
                }
            }
        }
        notice?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }

    // تایید ارسال به گالری عمومی (همان دیالوگ صفحه‌ی نتیجه).
    if (showSendConfirm && current != null) {
        AlertDialog(
            onDismissRequest = { showSendConfirm = false },
            containerColor = Color(0xFF222222),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("ارسال به نگارخانه عمومی؟", fontWeight = FontWeight.Bold) },
            text = { Text("با زدن «ارسال»، این عکس به نگارخانه عمومی چهره‌ساز می‌رود و همه‌ی کاربران می‌توانند آن را ببینند.") },
            confirmButton = {
                Button(
                    onClick = {
                        showSendConfirm = false
                        current?.let { photo ->
                            scope.launch {
                                try {
                                    Api.service.gallerySubmit(GallerySubmitRequest(photo.id))
                                    onSent(photo.id)
                                    notice = "برای نمایش عمومی، در صف تایید نگارخانه چهره‌ساز قرار گرفت"
                                } catch (_: Exception) {
                                    notice = "ارسال به نگارخانه ناموفق بود؛ دوباره تلاش کن"
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(50),
                ) {
                    Text("ارسال", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendConfirm = false }) {
                    Text("انصراف", color = Color.White)
                }
            },
        )
    }
}
