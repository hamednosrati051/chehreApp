package ir.inourai.newapp.ui.screens

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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

private fun friendlyError(e: Exception): String {
    if (e is HttpException && e.code() == 402) {
        return "سکه کافی نیست؛ از پروفایل سکه اضافه کن"
    }
    if (e.message?.contains("upload failed") == true) {
        return "آپلود عکس ناموفق بود؛ دوباره تلاش کن"
    }
    return e.message ?: "خطایی پیش آمد"
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
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    val favIds by Favorites.ids
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            picked = uri
            error = null
        }
        showPicker = false
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        if (ok) {
            picked = cameraUri
            error = null
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

    // نظرسنجی وضعیت job تا آماده‌شدن.
    LaunchedEffect(jobId) {
        val id = jobId ?: return@LaunchedEffect
        while (true) {
            delay(3000)
            try {
                val st = Api.service.generation(id)
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
                    error = st.error ?: "ساخت ناموفق بود"
                    jobId = null
                    break
                }
            } catch (_: Exception) {
            }
        }
    }

    val cost = world?.coin_price ?: 0
    val isWorking = jobId != null

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
                    Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = Color.White)
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
                            if (worldId in favIds) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
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
                        shape = RoundedCornerShape(20.dp),
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
                                            .clip(RoundedCornerShape(16.dp))
                                            .alpha(0.35f),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                CircularProgressIndicator(color = Color.White)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("در حال تحلیل و پردازش...", color = Color.White, fontSize = 14.sp)
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
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            AsyncImage(
                                model = picked,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { showPicker = true }) {
                                Text("تغییر تصویر", color = Color.White)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = {
                                    if (Session.token == null) {
                                        onLogin()
                                        return@Button
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
                                            error = friendlyError(e)
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(50),
                            ) {
                                Text("ساخت تصویر", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF8A5A00), RoundedCornerShape(20.dp))
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
                    error?.let {
                        Text(it, color = Color(0xFFFF8A8A), modifier = Modifier.padding(top = 8.dp))
                    }
                    notice?.let {
                        Text(it, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
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
                                Icons.Filled.Image,
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
                                Icons.Filled.CameraAlt,
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
}
