package ir.inourai.newapp.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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

/** اسپلش اول اپ: پوستر اینترو تمام‌صفحه؛ با ضربه رد می‌شود. */
@Composable
fun SplashScreen(onSkip: () -> Unit) {
    // زوم آرام روی تصویر در ۲ ثانیه‌ی اسپلش.
    val zoom by animateFloatAsState(
        targetValue = 1.05f,
        animationSpec = tween(2000),
        label = "splashZoom",
    )
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color(0xFF0B1020))
            .clickable { onSkip() },
    ) {
        Image(
            painter = painterResource(id = R.drawable.splash_poster),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(scaleX = zoom, scaleY = zoom),
            contentScale = ContentScale.Crop,
        )
    }
}
