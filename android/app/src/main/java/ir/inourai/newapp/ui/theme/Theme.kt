package ir.inourai.newapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ir.inourai.newapp.R
import ir.inourai.newapp.data.Session

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

// v18 (8): تم تاریک — سرمه‌ای/خاکستری تیره، هماهنگ با آبی برند
// (#1D6FE0) و طلایی (#C98A1B). آبی اصلی کمی روشن‌تر شد تا روی زمینه‌ی
// تیره دیده شود ولی هنوز متن سفید رویش خوانا بماند؛ آبی تیره‌ی
// ثانویه (عنوان «چهره‌ساز»، چیپ انتخاب‌شده) روشن شد چون روی تیره
// محو می‌شد؛ طلایی هم روشن‌تر شد. متن‌ها سفید-خاکستری روشن‌اند.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF3D84F0),
    onPrimary = Color.White,
    secondary = Color(0xFF8FB4F5),
    onSecondary = Color(0xFF0B1E3A),
    tertiary = Color(0xFFD9A441),
    onTertiary = Color(0xFF2A1E00),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFE8ECF3),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFE8ECF3),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFAAB4C4),
    surfaceContainerLowest = Color(0xFF1A2438),
    surfaceContainerLow = Color(0xFF1E293B),
    surfaceContainer = Color(0xFF243247),
    surfaceContainerHigh = Color(0xFF2A3A52),
    surfaceContainerHighest = Color(0xFF31435E),
    outline = Color(0xFF3A465C),
    outlineVariant = Color(0xFF2A3648),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3A0D0D),
    surfaceTint = Color(0xFF3D84F0),
)

// v18 (8): رنگِ وابسته به تم. مقدار روشن دقیقاً همان رنگِ امروزِ
// تم روشن است تا ظاهر روشن ذره‌ای عوض نشود؛ مقدار تیره فقط وقتی
// کاربر «تم تاریک» را روشن کرده باشد استفاده می‌شود. فقط داخل
// کامپوزابل‌ها صدا زده می‌شود.
@Composable
fun themed(light: Color, dark: Color): Color =
    if (Session.darkThemeState.value) dark else light

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
    // v18 (8): دستی، از Session.darkTheme (پیش‌فرض روشن)؛ تغییر
    // سوییچ پروفایل از طریق darkThemeState کل درخت را بازترکیب می‌کند.
    val dark = Session.darkThemeState.value
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else Scheme,
        typography = AppTypography,
        content = content,
    )
}
