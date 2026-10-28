package ir.inourai.newapp.ui.screens

import ir.inourai.newapp.data.friendlyGenerationError
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Favorites
import ir.inourai.newapp.data.GallerySubmitRequest
import ir.inourai.newapp.data.Session
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val ViewerBg = Color(0xFF0F172A)
private val RoyalBlue = Color(0xFF2E5BFF)

/** دانلود بایت‌های تصویر نتیجه (برای اشتراک‌گذاری و دانلود). */
private suspend fun downloadResultBytes(url: String): ByteArray {
    return withContext(Dispatchers.IO) {
        val resp = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) throw RuntimeException("download failed: ${resp.code}")
        resp.body?.bytes() ?: throw RuntimeException("empty image")
    }
}

/** نمایش تمام‌صفحه‌ی نتیجه (مثل رقیب): نام دنیا + قلب + اشتراک‌گذاری
 *  و دانلود؛ وقتی تازه ساخته شده، شیت امتیاز بازار هم نشان داده می‌شود. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(genId: Long, fresh: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("loading") }
    var outputUrl by remember { mutableStateOf<String?>(null) }
    var worldName by remember { mutableStateOf<String?>(null) }
    var worldId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var ratingDismissed by remember { mutableStateOf(false) }
    val favIds by Favorites.ids
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // اگر نوتیفیکیشن «آماده شد» برای همین ساخت آمده بود، با بازشدن
    // نتیجه دیگر لازم نیست.
    LaunchedEffect(genId) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(genId.toInt())
        Favorites.refresh()
        try {
            submitted = Api.service.myGallery().any { it.generation_id == genId }
        } catch (_: Exception) {
        }
        while (true) {
            try {
                val st = Api.service.generation(genId)
                status = st.status
                outputUrl = st.output_url
                worldName = st.world_name
                worldId = st.world_id
                if (st.status == "failed") error = friendlyGenerationError(st.error)
                if (st.status == "done" || st.status == "failed") break
            } catch (e: Exception) {
                error = e.message
                break
            }
            delay(3000)
        }
    }

    // پیام کوتاه و موقت: بعد از ~۲٫۵ ثانیه محو می‌شود.
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2500)
            notice = null
        }
    }

    // پیام‌های انتظار هنگام ساخت تصویر: هر ۲۰ ثانیه عوض می‌شوند؛
    // درصد تقلبی ندارد و زمان واقعی (۳۰ ثانیه تا ۲ دقیقه) گفته می‌شود.
    val waitLines = listOf(
        "چهره‌ات را با فضای دنیا ترکیب می‌کنیم…",
        "نور و رنگ صحنه را تنظیم می‌کنیم…",
        "تصویرت دارد آماده می‌شود…",
    )
    var waitIndex by remember { mutableStateOf(0) }
    LaunchedEffect(outputUrl == null) {
        if (outputUrl == null) {
            while (true) {
                delay(20000)
                waitIndex = (waitIndex + 1) % waitLines.size
            }
        }
    }

    fun share() {
        val url = outputUrl ?: return
        scope.launch {
            try {
                val bytes = downloadResultBytes(url)
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, "result_$genId.jpg").apply { writeBytes(bytes) }
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "اشتراک‌گذاری تصویر"))
            } catch (e: Exception) {
                notice = e.message
            }
        }
    }

    fun download() {
        val url = outputUrl ?: return
        scope.launch {
            try {
                val bytes = downloadResultBytes(url)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "chehresaz_$genId.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChehreSaz")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw RuntimeException("media store failed")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                notice = "دانلود شد"
            } catch (e: Exception) {
                notice = e.message
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(ViewerBg),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowForward, contentDescription = null, tint = Color.White)
            }
            Text(
                worldName ?: "",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            val wid = worldId
            if (wid != null && Session.token != null) {
                IconButton(onClick = { scope.launch { Favorites.toggle(wid) } }) {
                    Icon(
                        if (wid in favIds) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = if (wid in favIds) Color(0xFFFF5A5A) else Color.White,
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                outputUrl != null -> AsyncImage(
                    model = outputUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                error != null -> Text(error ?: "", color = Color.White)
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "داریم تو را وارد ${worldName?.let { "دنیای $it" } ?: "دنیا"} می‌کنیم…",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "ساخت تصویر معمولاً ۳۰ ثانیه تا ۲ دقیقه طول می‌کشد.",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        waitLines[waitIndex],
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                    )
                }
            }
        }
        if (outputUrl != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Button(
                    onClick = { share() },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("اشتراک‌گذاری", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { download() },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("دانلود", color = Color.White)
                }
            }
            if (!submitted) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                Api.service.gallerySubmit(GallerySubmitRequest(genId))
                                submitted = true
                                notice = "برای نمایش عمومی، در صف تایید گالری چهره‌ساز قرار گرفت"
                            } catch (e: Exception) {
                                notice = e.message
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text("ارسال به گالری چهره‌ساز", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) {
                    Text("ارسال شده به گالری")
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        }

        // بنر نوتیف شناور: پیام زیر عکس نمی‌آید؛ مثل یک نوتیف کوتاه
        // بالای نوار پایین می‌نشیند و بعد از ~۲٫۵ ثانیه محو می‌شود.
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

    // شیت امتیاز بعد از ساخت (فقط برای ساخت تازه، یک بار).
    if (fresh && status == "done" && outputUrl != null && !ratingDismissed) {
        ModalBottomSheet(
            onDismissRequest = { ratingDismissed = true },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = outputUrl,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("از تصویرت راضی بودی؟", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "اگه خوشت اومد، امتیاز و نظرت توی کافه‌بازار کلی بهمون کمک می‌کنه.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        ratingDismissed = true
                        val url = "https://cafebazaar.ir/app/" + context.packageName
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("عالی بود، امتیاز می‌دم", fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = { ratingDismissed = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("راضی نبودم")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
