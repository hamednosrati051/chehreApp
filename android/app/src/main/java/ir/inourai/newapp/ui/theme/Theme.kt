package ir.inourai.newapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ir.inourai.newapp.R

// آبی/سفید با لهجه‌ی طلایی، هماهنگ با آیکون برند چهره‌ساز.
private val Blue = Color(0xFF1D6FE0)
private val DarkBlue = Color(0xFF0B3D91)
private val Gold = Color(0xFFC98A1B)

private val Scheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = DarkBlue,
    onSecondary = Color.White,
    tertiary = Gold,
    onTertiary = Color.White,
)

// فونت فارسی وزیرمتن، روی همه‌ی استایل‌های متنی.
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private val BaseTypography = Typography()
val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = Vazirmatn),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = Vazirmatn),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = Vazirmatn),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = Vazirmatn),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = Vazirmatn),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = Vazirmatn),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = Vazirmatn),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = Vazirmatn),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = Vazirmatn),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = Vazirmatn),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = Vazirmatn),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = Vazirmatn),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = Vazirmatn),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = Vazirmatn),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = Vazirmatn),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
