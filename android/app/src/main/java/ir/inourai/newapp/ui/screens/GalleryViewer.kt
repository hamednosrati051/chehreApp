package ir.inourai.newapp.ui.screens

import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.ImageLikes
import java.io.File
import kotlinx.coroutines.launch

private val GalleryViewerBg = Color(0xFF0F172A)

/** یک عکس گالری برای نمایشگر: خروجی + نام دنیا + تاریخ شمسی زیرنویس. */
data class GalleryPhoto(
    val id: Long,
    val outputUrl: String,
    val worldName: String?,
    val dateFa: String?,
)

/** نمایشگر تمام‌صفحه‌ی گالری (نسخه‌ی ۱۰): ورق‌زدن بین عکس‌ها +
 *  دانلود + اشتراک‌گذاری هر عکس + لغزنده‌ی قبل/بعد (سلفی در برابر خروجی).
 *  منطق دانلود/اشتراک‌گذاری عمداً همین‌جا تکرار شده تا صفحه‌ی نتیجه
 *  دست نخورد؛ تغییر یکی، دیگری را نمی‌شکند. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryViewer(
    photos: List<GalleryPhoto>,
    startIndex: Int,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val likedIds by ImageLikes.ids
    var notice by remember { mutableStateOf<String?>(null) }
    // ورودی (سلفی) هر عکس با بهترین تلاش خوانده می‌شود: جزئیات generation
    // فقط برای صاحبش input_url دارد؛ برای بقیه (مثلاً لایک‌شده‌ی دیگران)
    // ۴۰۴ می‌شود و لغزنده همان صفحه پنهان می‌ماند.
    var inputUrls by remember { mutableStateOf<Map<Long, String?>>(emptyMap()) }

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

    LaunchedEffect(current?.id) {
        val id = current?.id ?: return@LaunchedEffect
        if (id !in inputUrls) {
            inputUrls = inputUrls + (id to try {
                Api.service.generation(id).input_url
            } catch (_: Exception) {
                null
            })
        }
    }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        current?.worldName ?: "",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    if (current?.dateFa != null) {
                        Text(current.dateFa, color = Color.LightGray, fontSize = 12.sp)
                    }
                }
                Text(
                    "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                val photo = photos[page]
                val inputUrl = inputUrls[photo.id]
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (inputUrl != null) {
                        BeforeAfterSlider(inputUrl = inputUrl, outputUrl = photo.outputUrl)
                    } else {
                        AsyncImage(
                            model = photo.outputUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
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
                if (current != null) {
                    IconButton(onClick = { scope.launch { ImageLikes.toggle(current.id) } }) {
                        Icon(
                            if (current.id in likedIds) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            tint = if (current.id in likedIds) Color(0xFFFF5A5A) else Color.White,
                        )
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
}

/** لغزنده‌ی قبل/بعد: سلفی زیر، خروجی رو؛ کشیدن افقی، خط جداکننده را
 *  با انگشت حرکت می‌دهد. برچسب‌ها به سمت محتوایشان چسبیده‌اند:
 *  «نتیجه» سمت خروجی (چپ پیکسلی)، «سلفی» سمت ورودی (راست پیکسلی). */
@Composable
private fun BeforeAfterSlider(inputUrl: String, outputUrl: String) {
    var fraction by remember { mutableStateOf(0.5f) }
    var widthPx by remember { mutableStateOf(0) }
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (widthPx > 0) {
                        fraction = (fraction + dragAmount / widthPx).coerceIn(0f, 1f)
                    }
                }
            },
    ) {
        AsyncImage(
            model = inputUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
        AsyncImage(
            model = outputUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().drawWithContent {
                clipRect(right = size.width * fraction) {
                    this@drawWithContent.drawContent()
                }
            },
            contentScale = ContentScale.FillWidth,
        )
        // خط جداکننده + دستگیره‌ی گرد که دقیقاً رویش سوار است.
        val density = LocalDensity.current
        val handleHalfPx = with(density) { 18.dp.toPx() }
        Box(
            modifier = Modifier.fillMaxHeight().width(36.dp)
                .offset { IntOffset((widthPx * fraction - handleHalfPx).toInt(), 0) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.fillMaxHeight().width(2.dp)
                    .background(Color.White),
            )
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("↔", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text("نتیجه", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text("سلفی", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
