package ir.inourai.newapp.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.inourai.newapp.R
import kotlinx.coroutines.delay

/** لودینگ ساخت عکس: جمله‌های عوض‌شونده + سه‌نقطه‌ی متحرک. */
@Composable
fun GenerationLoading() {
    val texts = listOf(
        "داریم چهره‌ت رو به دنیا می‌بریم...",
        "نور و رنگ رو تنظیم می‌کنیم...",
        "چشمک بزن، نزدیک شد...",
    )
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            index = (index + 1) % texts.size
        }
    }
    val alpha by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dots",
    )
    Column(modifier = Modifier.padding(16.dp)) {
        Text(texts[index])
        Text("...", modifier = Modifier.alpha(alpha))
    }
}

/** اسپلش اول اپ: تصویر متحرک تمام‌صفحه، بدون هیچ نوشته‌ای؛ با ضربه رد می‌شود.
 *  (ساخت ویدیو برای این حساب در دسترس نیست، پس انیمیشن با همان تصویر برند
 *  و جرقه‌های طلایی در خود کد ساخته شده.) */
@Composable
fun SplashScreen(onSkip: () -> Unit) {
    // زوم آرام روی تصویر در ۲ ثانیه‌ی اسپلش.
    val zoom by animateFloatAsState(
        targetValue = 1.12f,
        animationSpec = tween(2000),
        label = "splashZoom",
    )
    // چشمک جرقه‌های طلایی.
    val sparkles = rememberInfiniteTransition(label = "sparkles")
    val tw1 by sparkles.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tw1",
    )
    val tw2 by sparkles.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tw2",
    )
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color(0xFF0B3D91))
            .clickable { onSkip() },
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(scaleX = zoom, scaleY = zoom),
            contentScale = ContentScale.Crop,
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stars = listOf(
                Triple(0.15f, 0.20f, 0),
                Triple(0.85f, 0.15f, 1),
                Triple(0.70f, 0.35f, 0),
                Triple(0.25f, 0.55f, 1),
                Triple(0.90f, 0.60f, 0),
                Triple(0.10f, 0.75f, 1),
                Triple(0.50f, 0.25f, 0),
                Triple(0.40f, 0.85f, 1),
                Triple(0.80f, 0.85f, 0),
                Triple(0.30f, 0.40f, 1),
            )
            for ((fx, fy, which) in stars) {
                val alpha = if (which == 0) tw1 else tw2
                drawCircle(
                    color = Color(0xFFFFD97A).copy(alpha = alpha),
                    radius = 8.dp.toPx(),
                    center = Offset(size.width * fx, size.height * fy),
                )
            }
        }
    }
}
