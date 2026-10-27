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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** اسپلش اول اپ: تصویر اینترو + نوشته‌ی زیرش؛ با ضربه رد می‌شود. */
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
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.intro_grid),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer(scaleX = zoom, scaleY = zoom)
                    .clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.FillWidth,
            )
            Text(
                "با چهره‌ساز شخصیت‌های مختلف\nاز خودت بساز و ببین",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}
