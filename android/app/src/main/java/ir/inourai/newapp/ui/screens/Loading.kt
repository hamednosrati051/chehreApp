package ir.inourai.newapp.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
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

/** جای اسپلش اول اپ: انیمیشن Lottie فانتزی و عامیانه با نهایی‌شدن برند. */
@Composable
fun SplashPlaceholder() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("اسپلش اول اپ (Lottie) — با نهایی‌شدن برند ساخته می‌شود")
    }
}
