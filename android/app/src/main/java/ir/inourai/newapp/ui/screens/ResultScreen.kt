package ir.inourai.newapp.ui.screens

import ir.inourai.newapp.data.friendlyGenerationError
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.ImageLikes
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

/** دانلود بایت‌های تصویر نتیجه (برای اشتراک‌گذاری و دانلود).
 *  internal است تا نمایشگر گالری هم از همین تابع استفاده کند. */
internal suspend fun downloadResultBytes(url: String): ByteArray {
    return withContext(Dispatchers.IO) {
        val resp = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) throw RuntimeException("download failed: ${resp.code}")
        resp.body?.bytes() ?: throw RuntimeException("empty image")
    }
}

/** فهرست سوایپ صفحه‌ی نتیجه وقتی از «تصاویر من» باز شده است: شناسه‌های
 *  همان فهرستی که عکس از آن لمس شده (تب عکس: در انتظار + آماده؛ تب قلب:
 *  لایک‌شده‌ها). «تصاویر من» قبل از رفتن به نتیجه آن را پر می‌کند؛ صفحه‌ی
 *  بعد از ساخت (fresh) سوایپ ندارد و این فهرست را نادیده می‌گیرد. */
object ResultSwipeContext {
    var ids: List<Long> = emptyList()
        private set

    fun set(newIds: List<Long>) {
        ids = newIds
    }
}

/** شناسه‌ی generationای که با اعتبار هدیه ساخته شد (نسخه‌ی ۱۲، افزایشی):
 *  سرور منبع پرداخت را در پاسخ ساخت برنمی‌گرداند (charged برای هدیه و
 *  سکه هر دو True است)؛ صفحه‌ی ساخت پیش از ثبت، free_image را می‌خواند
 *  و اگر هدیه داشت شناسه را اینجا می‌گذارد تا صفحه‌ی نتیجه‌ی تازه
 *  (fresh) فقط همان یک بار پیام هدیه را نشان بدهد. با خوانده‌شدن پاک
 *  می‌شود تا بازدید بعدی همان عکس (از «تصاویر من») پیام نگیرد. */
object ResultGiftContext {
    var usedGiftGenId: Long? = null
        private set

    fun set(genId: Long) {
        usedGiftGenId = genId
    }

    fun take(): Long? {
        val id = usedGiftGenId
        usedGiftGenId = null
        return id
    }
}

/** نمایش تمام‌صفحه‌ی نتیجه (مثل رقیب): نام دنیا + قلب + اشتراک‌گذاری
 *  و دانلود؛ وقتی تازه ساخته شده، شیت امتیاز بازار هم نشان داده می‌شود.
 *  وقتی از «تصاویر من» باز شده باشد، با سوایپ افقی بین عکس‌های همان
 *  فهرست حرکت می‌کند؛ صفحه‌ی بعد از ساخت تک‌عکس می‌ماند. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(genId: Long, fresh: Boolean, swipeIds: List<Long>? = null, onBack: () -> Unit, onLogin: () -> Unit = {}) {
    val context = LocalContext.current
    // سوایپ فقط وقتی معنا دارد که صفحه از «تصاویر من» آمده باشد، فهرست
    // دست‌کم ۲ عکس داشته باشد و عکس فعلی عضو همان فهرست باشد؛ در غیر
    // این صورت (از جمله صفحه‌ی تازه‌ساخته‌شده) صفحه تک‌عکس است.
    val swipeEnabled = !fresh && swipeIds != null && swipeIds.size > 1 && swipeIds.contains(genId)
    var currentId by remember(genId) { mutableStateOf(genId) }
    var slideDir by remember { mutableStateOf(1) }
    // عکس‌های آماده‌شده بر حسب شناسه، تا در انیمیشن سوایپ، صفحه‌ی قبلی
    // هنوز عکس خودش را نشان بدهد و صفحه‌ی تازه تا آماده‌شدن، چرخک بزند.
    var photoCache by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val density = LocalDensity.current
    val swipeThresholdPx = remember(density) { with(density) { 80.dp.toPx() } }
    var status by remember { mutableStateOf("loading") }
    var outputUrl by remember { mutableStateOf<String?>(null) }
    var worldName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var showGalleryConfirm by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var ratingDismissed by remember { mutableStateOf(false) }
    // پیام هدیه (نسخه‌ی ۱۲): فقط در صفحه‌ی تازه‌ساخته‌شده و فقط وقتی
    // همین generation با اعتبار هدیه ثبت شده باشد؛ با خوانده‌شدن،
    // شناسه از ResultGiftContext پاک می‌شود.
    var showGiftNudge by remember(genId) { mutableStateOf(false) }
    var showPacks by remember { mutableStateOf(false) }
    LaunchedEffect(genId) {
        if (fresh && ResultGiftContext.take() == genId) {
            showGiftNudge = true
        }
    }
    val likedIds by ImageLikes.ids
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // اگر نوتیفیکیشن «آماده شد» برای همین ساخت آمده بود، با بازشدن
    // نتیجه دیگر لازم نیست. با عوض‌شدن عکس (سوایپ)، حالت به «در حال
    // بارگذاری» برمی‌گردد تا عکس قبلی جای عکس تازه ننشیند.
    LaunchedEffect(currentId) {
        status = "loading"
        outputUrl = null
        worldName = null
        error = null
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(currentId.toInt())
        ImageLikes.refresh()
        try {
            submitted = Api.service.myGallery().any { it.generation_id == currentId }
        } catch (_: Exception) {
        }
        var failures = 0
        var pollErrorShown = false
        while (true) {
            try {
                val st = Api.service.generation(currentId)
                failures = 0
                status = st.status
                outputUrl = st.output_url
                worldName = st.world_name
                if (st.status == "done" && st.output_url != null) {
                    photoCache = photoCache + (currentId to st.output_url)
                }
                if (st.status == "failed") error = friendlyGenerationError(st.error)
                if (st.status == "done" || st.status == "failed") break
            } catch (_: Exception) {
                // خطای موقت نظرسنجی (مثلاً قطعی شبکه) چرخه را نمی‌شکند و
                // پیام خام نشان نمی‌دهد: بعد از ۳ شکست پشت سر هم یک
                // پیام کوتاه شبکه می‌آید و نظرسنجی ادامه پیدا می‌کند.
                failures++
                if (failures >= 3 && !pollErrorShown) {
                    pollErrorShown = true
                    notice = "مشکل شبکه؛ دوباره تلاش کن"
                }
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


    fun share() {
        val url = outputUrl ?: return
        scope.launch {
            try {
                val bytes = downloadResultBytes(url)
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, "result_$currentId.jpg").apply { writeBytes(bytes) }
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

    fun download() {
        val url = outputUrl ?: return
        scope.launch {
            try {
                val bytes = downloadResultBytes(url)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "chehresaz_$currentId.jpg")
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

    Box(
        modifier = Modifier.fillMaxSize().background(ViewerBg),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            // ستون به‌طور پیش‌فرض فرزندان را به start (= راست در RTL)
            // می‌چسباند؛ وسط‌چین‌کردن، دکمه‌ی گالری را زیر دکمه‌ها وسط می‌آورد.
            horizontalAlignment = Alignment.CenterHorizontally,
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
            if (Session.token != null) {
                IconButton(onClick = {
                    scope.launch {
                        val wasLiked = currentId in likedIds
                        val ok = ImageLikes.toggle(currentId)
                        if (!ok) {
                            notice = if (wasLiked) "لغو پسند ناموفق بود؛ دوباره تلاش کن"
                            else "پسندیدن ناموفق بود؛ دوباره تلاش کن"
                        }
                    }
                }) {
                    Icon(
                        if (currentId in likedIds) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = if (currentId in likedIds) Color(0xFFFF5A5A) else Color.White,
                    )
                }
            }
        }
        // ناحیه‌ی عکس: وقتی سوایپ فعال است، کشیدن افقی به چپ/راست به
        // عکس بعدی/قبلی همان فهرست می‌رود (در دو سر فهرست، سوایپ کاری
        // نمی‌کند)؛ هر عکس با داده‌ی خودش (نام دنیا، قلب، وضعیت ارسال)
        // نشان داده می‌شود.
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .then(
                    if (swipeEnabled) {
                        Modifier.pointerInput(swipeIds, currentId) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = {
                                    val ids = swipeIds
                                    if (ids != null) {
                                        val idx = ids.indexOf(currentId)
                                        if (idx >= 0) {
                                            if (total < -swipeThresholdPx && idx < ids.lastIndex) {
                                                slideDir = 1
                                                currentId = ids[idx + 1]
                                            } else if (total > swipeThresholdPx && idx > 0) {
                                                slideDir = -1
                                                currentId = ids[idx - 1]
                                            }
                                        }
                                    }
                                },
                                onHorizontalDrag = { _, dragAmount -> total += dragAmount },
                            )
                        }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = currentId,
                transitionSpec = {
                    (slideInHorizontally { w -> slideDir * w } + fadeIn()) togetherWith
                        (slideOutHorizontally { w -> -slideDir * w } + fadeOut())
                },
                label = "result-photo",
                modifier = Modifier.fillMaxSize(),
            ) { id ->
                val cached = photoCache[id]
                when {
                    cached != null -> AsyncImage(
                        model = cached,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    id == currentId && error != null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(error ?: "", color = Color.White)
                    }
                    else -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp),
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }
        }
        if (outputUrl != null) {
            // ردیف آیکون‌های اقدام: خالص و بدون نوشته، با فاصله‌ی یکسان؛
            // همین ردیف هم در صفحه‌ی بعد از ساخت و هم در «تصاویر من» دیده می‌شود
            // (هر دو همان ResultScreen است). جایگزین دکمه‌های متنی قبلی.
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                IconButton(onClick = { download() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = { share() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                if (!submitted) {
                    IconButton(onClick = { showGalleryConfirm = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                } else {
                    // حالت ارسال‌شده: تیک سبز و غیرفعال، تا آیکون هرگز
                    // «ارسال‌نشده» به نظر نرسد.
                    IconButton(onClick = {}, enabled = false, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
        // پیام هدیه (نسخه‌ی ۱۲): یک متن کوتاه و ساده، بدون قاب یا دکمه‌ی
        // پررنگ — فقط در صفحه‌ی تازه‌ساخته‌شده و فقط وقتی همین
        // generation با اعتبار هدیه ساخته شده باشد. «خرید سکه» یک
        // دکمه‌ی متنی ساده است و شیت بسته‌ها را باز می‌کند.
        if (showGiftNudge && outputUrl != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "این تصویر با هدیه رایگان ساخته شد",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "تصویر بعدی با سکه، کیفیت خیلی عالی دارد — چهره و حالت‌ها قشنگ‌تر",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
                TextButton(onClick = { showPacks = true }) {
                    Text("خرید سکه", color = Color(0xFF7EB3FF), fontWeight = FontWeight.Bold)
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

    // تایید ارسال به گالری عمومی: با زدن دکمه، اول این دیالوگ می‌آید؛
    // «ارسال» همان ارسال قبلی را اجرا می‌کند، «انصراف» هیچ کاری نمی‌کند.
    if (showGalleryConfirm) {
        AlertDialog(
            onDismissRequest = { showGalleryConfirm = false },
            containerColor = Color(0xFF222222),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("ارسال به نگارخانه عمومی؟", fontWeight = FontWeight.Bold) },
            text = { Text("با زدن «ارسال»، این عکس به نگارخانه عمومی چهره‌ساز می‌رود و همه‌ی کاربران می‌توانند آن را ببینند.") },
            confirmButton = {
                Button(
                    onClick = {
                        showGalleryConfirm = false
                        scope.launch {
                            try {
                                Api.service.gallerySubmit(GallerySubmitRequest(currentId))
                                submitted = true
                                notice = "برای نمایش عمومی، در صف تایید نگارخانه چهره‌ساز قرار گرفت"
                            } catch (_: Exception) {
                                notice = "ارسال به نگارخانه ناموفق بود؛ دوباره تلاش کن"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue),
                    shape = RoundedCornerShape(50),
                ) {
                    Text("ارسال", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGalleryConfirm = false }) {
                    Text("انصراف", color = Color.White)
                }
            },
        )
    }

    // شیت بسته‌های سکه (نسخه‌ی ۱۲): از دکمه‌ی متنی «خرید سکه» در پیام هدیه.
    if (showPacks) {
        PacksSheet(
            onDismiss = { showPacks = false },
            onPurchased = { notice = "سکه‌ها اضافه شد" },
            onError = { notice = it },
            onLogin = onLogin,
        )
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
