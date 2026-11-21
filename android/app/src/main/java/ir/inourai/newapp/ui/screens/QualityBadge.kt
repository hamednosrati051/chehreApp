package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.inourai.newapp.ui.theme.themed

// v18 (6): برچسب کیفیت — فقط در «تصاویر من» + نگارخانه‌ی عمومی،
// هرگز روی صفحه‌ی نتیجه. طلایی همان طلاییِ پیام هدیه است
// (0xFFFFC107) تا برچسب «کیفیت بالا» در همه‌جا یک رنگ باشد؛
// «کیفیت پایین» هیچ‌جا نوشته نمی‌شود.
private val QualityGold = Color(0xFFFFC107)
private val QualityGoldText = Color(0xFF4A3200)

/** چیپ کیفیت روی عکس: پولی → طلایی «کیفیت بالا»، رایگان → تیره‌ی
 *  نیمه‌شفاف «کیفیت متوسط» (هم‌شکل چیپ «✓ نگارخانه»). در نگارخانه‌ی
 *  عمومی فقط برای پولی صدا زده می‌شود (positive-only). */
@Composable
fun QualityChip(isFree: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                if (isFree) Color.Black.copy(alpha = 0.45f) else QualityGold,
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            if (isFree) "کیفیت متوسط" else "کیفیت بالا",
            color = if (isFree) Color.White else QualityGoldText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** نام دنیا زیر عکس (v18، مورد ۷): خط اول خنثی و وسط‌چین؛ اگر
 *  onClick داده شود، نام قابل‌لمس است و به صفحه‌ی ساخت همان دنیا می‌رود.
 *  رنگ پیش‌فرض وابسته به تم است (روشن: 0xFF334155؛ تاریک: روشن)؛
 *  فراخوانِ نمایشگر گالری رنگ سفید را صریح می‌دهد (پس‌زمینه‌اش تیره است). */
@Composable
fun WorldNameText(
    name: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    Text(
        name,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = color ?: themed(Color(0xFF334155), Color(0xFFD7DEE8)),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    )
}
