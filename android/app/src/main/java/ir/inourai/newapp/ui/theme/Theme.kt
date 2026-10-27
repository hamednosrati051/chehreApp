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

// زمینه‌ی صفحه‌ها آبی‌خاکستری خیلی روشن است تا سفیدی مطلق کم شود؛
 // کارت‌ها سفید می‌مانند تا از زمینه جدا دیده شوند.
private val Scheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = DarkBlue,
    onSecondary = Color.White,
    tertiary = Gold,
    onTertiary = Color.White,
    background = Color(0xFFF2F5FA),
    surface = Color(0xFFF2F5FA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
)

// فونت فارسی صمیم (رایگان، OFL)، روی همه‌ی استایل‌های متنی.
val Samim = FontFamily(
    Font(R.font.samim_regular, FontWeight.Normal),
    Font(R.font.samim_bold, FontWeight.Bold),
)

// فونت فانتزی نمایشی (لاله‌زار) برای تیترها؛ حروف فارسی گ/چ/پ/ژ را دارد.
// متن‌های بدنه و برچسب‌ها استعداد می‌مانند تا خوانایی در سایز کوچک حفظ شود.
val Lalezar = FontFamily(
    Font(R.font.lalezar_regular, FontWeight.Normal),
    Font(R.font.lalezar_regular, FontWeight.Bold),
)

private val BaseTypography = Typography()
val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = Lalezar),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = Lalezar),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = Lalezar),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = Lalezar),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = Lalezar),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = Lalezar),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = Lalezar),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = Samim),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = Samim),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = Samim),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = Samim),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = Samim),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = Samim),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = Samim),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = Samim),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
