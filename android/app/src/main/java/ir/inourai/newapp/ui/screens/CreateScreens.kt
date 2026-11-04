package ir.inourai.newapp.ui.screens

import ir.inourai.newapp.data.friendlyGenerationError
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Balance
import ir.inourai.newapp.data.Favorites
import ir.inourai.newapp.data.GenerationRequest
import ir.inourai.newapp.data.Session
import ir.inourai.newapp.data.UserError
import ir.inourai.newapp.data.networkError
import ir.inourai.newapp.data.userError
import ir.inourai.newapp.data.World
import ir.inourai.newapp.service.GenerationPollService
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** سلفی انتخاب‌شده را به فایل واقعی در کش تبدیل می‌کند تا آپلود شود. */
private fun uriToFile(context: Context, uri: Uri): File {
    val out = File(context.cacheDir, "selfie_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    }
    return out
}

// عکس آخری: هر انتخاب در یک فایل یکتا با زمان ذخیره می‌شود و فایل‌های
// قبلی پاک می‌شوند. این عمدی است: اگر همه‌ی انتخاب‌ها روی یک فایل ثابت
// بازنویسی شوند، Uri پیش‌فرض همیشه یکی می‌ماند و Coil تصویر کش‌شده‌ی
// انتخاب قبلی را نشان می‌دهد در حالی که آپلود بایت‌های تازه را می‌فرستد
// (باگ «عکس نمایشی با عکس ارسالی فرق دارد»). با فایل یکتا، هر انتخاب
// Uri تازه دارد و نمایش و آپلود همیشه یک عکس‌اند.
// (فقط Uri ذخیره نمی‌شود چون دسترسی GetContent موقتی است و بعد از ری‌استارت از بین می‌رود.)
private fun saveLastPhoto(context: Context, uri: Uri) {
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val file = File(context.filesDir, "last_photo_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { output ->
                input.copyTo(output)
            }
            context.filesDir.listFiles { f ->
                f.name.startsWith("last_photo_") && f.name != file.name
            }?.forEach { old ->
                try {
                    old.delete()
                } catch (_: Exception) {
                }
            }
            File(context.filesDir, "last_photo.jpg").let { legacy ->
                if (legacy.exists()) {
                    try {
                        legacy.delete()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    } catch (_: Exception) {
    }
}

// Uri قابل‌نمایش/آپلود برای عکس آخری ذخیره‌شده؛ اگر فایلی نباشد null.
// تازه‌ترین فایل انتخاب می‌شود (سازگار با فایل قدیمی last_photo.jpg
// که پیش از نسخه‌ی ۱۰ ساخته شده باشد).
private fun lastPhotoUri(context: Context): Uri? {
    return try {
        val newest = context.filesDir.listFiles { f ->
            f.name.startsWith("last_photo_") && f.name.endsWith(".jpg")
        }?.maxByOrNull { it.lastModified() }
        val file = newest
            ?: File(context.filesDir, "last_photo.jpg").takeIf { it.exists() }
            ?: return null
        FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
    } catch (_: Exception) {
        null
    }
}

/** صفحه‌ی ساخت دنیا (مثل رقیب): نمونه‌ی تمام‌صفحه + قلب و نام دنیا بالا،
 *  پایین: بندانگشتی عکس کاربر + «تغییر تصویر» + «ساخت تصویر» با قیمت سکه. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateImageScreen(
    worldId: Long,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onDone: (Long) -> Unit,
) {
    val context = LocalContext.current
    var world by remember { mutableStateOf<World?>(null) }
    var balance by remember { mutableStateOf<Balance?>(null) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var jobId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf("idle") }
    var notice by remember { mutableStateOf<String?>(null) }
    // کانال خطا: اسنک‌بار پایین صفحه (متن کوتاه + «تلاش دوباره» وقتی
    // تکرار معنا دارد). متن خام exception هرگز اینجا نمی‌آید؛ نگاشت در
    // userError انجام می‌شود و snackbar فقط متن فارسی نهایی را نشان می‌دهد.
    val snackbarHostState = remember { SnackbarHostState() }
    var snack by remember { mutableStateOf<UserError?>(null) }
    var snackRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var snackSeq by remember { mutableStateOf(0) }
    fun showSnack(ue: UserError, retry: (() -> Unit)? = null) {
        snack = ue
        snackRetry = retry
        snackSeq++
    }
    LaunchedEffect(snackSeq) {
        val ue = snack ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = ue.message,
            actionLabel = if (ue.retryable) "تلاش دوباره" else null,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) snackRetry?.invoke()
        snack = null
        snackRetry = null
    }
    var showPicker by remember { mutableStateOf(false) }
    var showPacks by remember { mutableStateOf(false) }
    val favIds by Favorites.ids
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            picked = uri
            saveLastPhoto(context, uri)
        }
        showPicker = false
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        if (ok) {
            picked = cameraUri
            cameraUri?.let { saveLastPhoto(context, it) }
        }
        showPicker = false
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            cameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            notice = "برای دوربین اجازه لازم است"
            showPicker = false
        }
    }
    // اجازه‌ی نوتیفیکیشن (اندروید ۱۳+)؛ اگر ندهد هم ساخت درون‌اپ کار می‌کند.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(worldId) {
        // عکس آخری ذخیره‌شده (از دنیای قبلی) را پیش‌فرض بگذار؛
        // دکمه‌ی X فقط همین صفحه را خالی می‌کند و فایل را پاک نمی‌کند.
        if (picked == null) {
            lastPhotoUri(context)?.let { picked = it }
        }
        try {
            world = Api.service.world(worldId)
        } catch (_: Exception) {
        }
        if (Session.token != null) {
            try {
                balance = Api.service.balance()
            } catch (_: Exception) {
            }
            Favorites.refresh()
        }
    }

    // نظرسنجی وضعیت job تا آماده‌شدن. خطای موقت شبکه چرخه را نمی‌شکند:
    // بعد از ۳ پاسخ ناموفق پشت سر هم یک اسنک‌بار شبکه نشان داده می‌شود
    // و نظرسنجی ادامه پیدا می‌کند تا موفقیت یا شکست نهایی.
    LaunchedEffect(jobId) {
        val id = jobId ?: return@LaunchedEffect
        var failures = 0
        var pollErrorShown = false
        while (true) {
            delay(3000)
            try {
                val st = Api.service.generation(id)
                failures = 0
                status = st.status
                if (st.status == "done") {
                    GenerationPollService.stop(context)
                    val doneId = id
                    jobId = null
                    onDone(doneId)
                    break
                }
                if (st.status == "failed") {
                    GenerationPollService.stop(context)
                    showSnack(UserError(friendlyGenerationError(st.error), false))
                    jobId = null
                    break
                }
            } catch (_: Exception) {
                failures++
                if (failures >= 3 && !pollErrorShown) {
                    pollErrorShown = true
                    showSnack(networkError())
                }
            }
        }
    }

    // جمله‌های انتظار هنگام ساخت: هر ۲۰ ثانیه عوض می‌شوند؛ درصد تقلبی ندارد.
    val waitLines = listOf(
        "چهره‌ات را با فضای دنیا ترکیب می‌کنیم…",
        "نور و رنگ صحنه را تنظیم می‌کنیم…",
        "تصویرت دارد آماده می‌شود…",
    )
    var waitIndex by remember { mutableStateOf(0) }
    LaunchedEffect(jobId != null) {
        if (jobId != null) {
            while (true) {
                delay(20000)
                waitIndex = (waitIndex + 1) % waitLines.size
            }
        }
    }

    val cost = world?.coin_price ?: 0
    val isWorking = jobId != null
    // سکه‌ی ناکافی: لاگین‌کرده، عکس انتخاب‌شده، هدیه‌ی تصویری ندارد و
    // سکه‌اش از قیمت دنیا کمتر است — قبل از تلاش ساخت نشان داده می‌شود.
    val insufficient = Session.token != null && picked != null && cost > 0 &&
        (balance?.free_image ?: 0) <= 0 && (balance?.coins ?: 0) < cost

    fun refreshBalance() {
        scope.launch {
            balance = try {
                Api.service.balance()
            } catch (_: Exception) {
                null
            }
        }
    }

    // موجودی در هر بار ورود به صفحه‌ی ساخت (و بازگشت از انتخاب عکس)
    // تازه شود تا بررسی «سکه کافی» با عدد روز باشد.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && Session.token != null) {
                refreshBalance()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ساخت تصویر: آپلود سلفی + ثبت generation + شروع سرویس نظرسنجی.
    // به‌صورت تابع جدا آمده تا اسنک‌بار «تلاش دوباره» هم همان مسیر را
    // دوباره اجرا کند (نه کپی منطق). ۴۰۲ و ۴۰۱ مثل قبل جدا هندل می‌شوند
    // (بسته‌ها / ورود)؛ بقیه‌ی خطاها نگاشت‌شده به اسنک‌بار می‌روند.
    fun startBuild() {
        if (Session.token == null) {
            onLogin()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
        scope.launch {
            try {
                val uri = picked ?: return@launch
                val inputUrl = Api.uploadFile(uriToFile(context, uri))
                val created = Api.service.createGeneration(
                    GenerationRequest(
                        kind = "image",
                        world_id = worldId,
                        input_url = inputUrl,
                    ),
                )
                jobId = created.id
                status = "queued"
                GenerationPollService.start(context, created.id)
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 402) {
                    // سکه ناکافی: به‌جای خطا، موجودی تازه می‌شود تا حالت
                    // «سکه کافی نداری» بیاید.
                    refreshBalance()
                } else if (e is HttpException && e.code() == 401) {
                    onLogin()
                } else {
                    val ue = userError(e)
                    showSnack(ue, if (ue.retryable) { { startBuild() } } else null)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // نمونه‌ی دنیا، تمام‌صفحه (مثل رقیب).
        AsyncImage(
            model = world?.sample_image_url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // سایه‌ی بالا و پایین برای خوانایی.
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.45f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.65f),
                    ),
                ),
            ),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            // نوار بالا: برگشت + نام دنیا + قلب.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowForward, contentDescription = null, tint = Color.White)
                }
                Text(
                    world?.name ?: "",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (Session.token != null) {
                    IconButton(onClick = { scope.launch { Favorites.toggle(worldId) } }) {
                        Icon(
                            if (worldId in favIds) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            tint = if (worldId in favIds) Color(0xFFFF5A5A) else Color.White,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            // بخش پایین (مثل رقیب).
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                if (isWorking) {
                    // لودینگ ساخت: عکس خود کاربر تیره + اسپینر + نام دنیا.
                    Card(
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "${world?.name ?: ""} / در حال ساخت...",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(contentAlignment = Alignment.Center) {
                                picked?.let {
                                    AsyncImage(
                                        model = it,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(24.dp))
                                            .alpha(0.35f),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                CircularProgressIndicator(color = Color.White)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "داریم تو را وارد ${world?.name?.let { "دنیای $it" } ?: "دنیا"} می‌کنیم…",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "ساخت تصویر معمولاً ۳۰ ثانیه تا ۲ دقیقه طول می‌کشد.",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                waitLines[waitIndex],
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    if (picked == null) {
                        Button(
                            onClick = { showPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("تصویر خود را آپلود کنید", fontWeight = FontWeight.Bold)
                        }
                    } else if (insufficient) {
                        // حالت سکه‌ی ناکافی (مثل رقیب): بندانگشتی + تغییر تصویر،
                        // «سکه کافی نداری» و دکمه‌ی بزرگ «+ افزایش سکه».
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            picked?.let {
                                AsyncImage(
                                    model = it,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { showPicker = true }) {
                                Text("تغییر تصویر", color = Color.White)
                            }
                            IconButton(onClick = { picked = null }) {
                                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("سکه کافی نداری", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showPacks = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("+ افزایش سکه", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "هر ساخت $cost سکه",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            AsyncImage(
                                model = picked,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { showPicker = true }) {
                                Text("تغییر تصویر", color = Color.White)
                            }
                            IconButton(onClick = { picked = null }) {
                                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = { startBuild() },
                                shape = RoundedCornerShape(50),
                            ) {
                                Text("ساخت تصویر", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF8A5A00), RoundedCornerShape(24.dp))
                                        .padding(horizontal = 10.dp, vertical = 2.dp),
                                ) {
                                    Text("🪙 $cost", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    // هشدار «آخرین تصویر با سکه‌های فعلی» (مثل رقیب).
                    val bal = balance
                    if (bal != null && cost > 0 && bal.coins >= cost && bal.coins - cost < cost) {
                        Text(
                            "این آخرین تصویر با سکه‌های فعلی توست",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    notice?.let {
                        Text(it, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    // شیت «انتخاب عکس» (مثل رقیب): گالری / دوربین / انصراف.
    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("انتخاب عکس", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0xFFE8EEFF), CircleShape)
                                .clickable { galleryLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Image,
                                contentDescription = null,
                                tint = Color(0xFF2E5BFF),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("گالری", fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0xFFE8EEFF), CircleShape)
                                .clickable {
                                    if (ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.CAMERA
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                                        val file = File(
                                            dir, "camera_${System.currentTimeMillis()}.jpg"
                                        )
                                        val uri = FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", file
                                        )
                                        cameraUri = uri
                                        cameraLauncher.launch(uri)
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.CameraAlt,
                                contentDescription = null,
                                tint = Color(0xFF2E5BFF),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("دوربین", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                TextButton(onClick = { showPicker = false }) {
                    Text("انصراف")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // شیت بسته‌های سکه (همان شیت پروفایل).
    if (showPacks) {
        PacksSheet(
            onDismiss = { showPacks = false },
            onPurchased = { refreshBalance(); notice = "سکه‌ها اضافه شد" },
            onError = { showSnack(UserError(it, false)) },
            onLogin = onLogin,
        )
    }
}
