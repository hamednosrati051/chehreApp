package ir.inourai.newapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// آبی/سفید، هماهنگ با طرح ترکیبی تاییدشده.
private val Blue = Color(0xFF1D6FE0)
private val DarkBlue = Color(0xFF0B3D91)

private val Scheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = DarkBlue,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
