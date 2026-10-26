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

/** اسپلش اول اپ: آیکون برند + نام، با انیمیشن ورود؛ با ضربه رد می‌شود. */
@Composable
fun SplashScreen(onSkip: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.85f,
        animationSpec = tween(600),
        label = "splashScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(600),
        label = "splashAlpha",
    )
    LaunchedEffect(Unit) { shown = true }
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color(0xFF0B3D91))
            .clickable { onSkip() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha),
        ) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(140.dp).clip(RoundedCornerShape(32.dp)),
            )
            Text(
                "چهره‌ساز",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "سلفی بده، یه چهره‌ی تازه بگیر",
                color = Color(0xFFE8B84B),
                fontSize = 16.sp,
            )
            Text(
                "برای رد شدن ضربه بزن",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
        }
    }
}
