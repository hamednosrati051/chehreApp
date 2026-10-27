package ir.inourai.newapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Category
import ir.inourai.newapp.data.Favorites
import ir.inourai.newapp.data.Session
import ir.inourai.newapp.data.World

/** خانه، هم‌شکل اپ مرجع: سربرگ برند + چیپ دسته‌ها + ردیف ترندها
 *  (کاروسل با نقطه‌ها) + آواتارهای دایره‌ای + شبکه‌ی تازه‌ها. */
@Composable
fun HomeScreen(onWorld: (Long) -> Unit) {
    val context = LocalContext.current
    var worlds by remember { mutableStateOf<List<World>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    // null یعنی «پیشنهادی» (دنیاهای featured).
    var selected by remember { mutableStateOf<Long?>(null) }

    // توست هدیه‌ی ورود (مثل رقیب) — فقط یک بار بعد از اولین ورود.
    LaunchedEffect(Unit) {
        if (Session.justRegistered) {
            Session.justRegistered = false
            Toast.makeText(
                context, "🎁 ۱ تصویر هدیه به حسابت اضافه شد", Toast.LENGTH_LONG
            ).show()
        }
        try {
            worlds = Api.service.worlds()
        } catch (_: Exception) {
        }
        try {
            categories = Api.service.categories()
        } catch (_: Exception) {
        }
        Favorites.refresh()
    }

    // «ترندهای اینستاگرام»: دنیاهای تیک‌خورده در ادمین؛ اگر هیچ‌کدام تیک
    // نخورده باشد، همه‌ی دنیاها (رفتار قبلی).
    val insta = worlds.filter { it.is_instagram_trend }.ifEmpty { worlds }
    // «ترندهای ایرانی»: اول تیک‌خورده‌ها، بعد دسته‌ی تاریخی، بعد featuredها.
    val tarikhiId = categories.firstOrNull { it.slug == "tarikhi" }?.id
    val iranian = worlds.filter { it.is_iranian_trend }.ifEmpty {
        worlds.filter { it.category_id == tarikhiId }.ifEmpty {
            worlds.filter { it.is_featured }
        }
    }
    // «تازه‌های هفته»: دنیاهای ۷ روز اخیر (جدیدترین اول)؛ اگر در این هفته
    // دنیایی نیامده باشد، ۶ دنیای آخری که وارد شده‌اند.
    fun createdMillis(w: World): Long {
        return try {
            val s = (w.created_at ?: return 0L).replace("Z", "")
            java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
    val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
    val fresh = worlds.filter { createdMillis(it) >= weekAgo }.sortedByDescending { createdMillis(it) }
    val freshFallback = worlds.sortedByDescending { createdMillis(it) }.take(6)
    val shown = if (selected != null) {
        worlds.filter { it.category_id == selected }
    } else {
        fresh.ifEmpty { freshFallback }
    }
    val trendState = rememberLazyListState()
    val trendIndex by remember {
        derivedStateOf {
            trendState.firstVisibleItemIndex.coerceIn(0, (insta.size - 1).coerceAtLeast(0))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "چهره‌ساز",
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    HomeChip("پیشنهادی", selected == null) { selected = null }
                }
                items(categories) { c ->
                    HomeChip(c.name, selected == c.id) { selected = c.id }
                }
            }
        }
        // ترندهای اینستاگرام: کاروسل افقی کارت‌های بلند + نقطه‌ها.
        if (insta.isNotEmpty()) {
            item {
                Text("ترندهای اینستاگرام", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            item {
                LazyRow(
                    state = trendState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(insta) { w ->
                        WorldCard(w, { onWorld(w.id) }, Modifier.width(160.dp))
                    }
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    repeat(insta.size) { i ->
                        Box(
                            modifier = Modifier
                                .size(if (i == trendIndex) 8.dp else 6.dp)
                                .background(
                                    if (i == trendIndex) MaterialTheme.colorScheme.primary
                                    else Color.LightGray,
                                    CircleShape,
                                ),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        // ترندهای ایرانی: آواتارهای دایره‌ای.
        if (iranian.isNotEmpty()) {
            item {
                Text("ترندهای ایرانی", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(iranian) { w ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(76.dp).clickable { onWorld(w.id) },
                        ) {
                            AsyncImage(
                                model = w.sample_image_url,
                                contentDescription = w.name,
                                modifier = Modifier.size(72.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                w.name,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text("تازه‌های هفته", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        if (shown.isEmpty()) {
            item {
                Text("دنیایی اینجا نیست؛ دسته‌ی دیگری را انتخاب کن.", color = Color.Gray)
            }
        } else {
            item {
                // ارتفاع شبکه از روی نسبت ۳:۴ کارت حساب می‌شود تا اسکرول
                // تودرتو پیش نیاید (کارت‌ها در سه ستون‌اند).
                val screenW = LocalConfiguration.current.screenWidthDp.dp
                val cardW = (screenW - 32.dp - 24.dp) / 3
                val cardH = cardW * 4 / 3
                val rows = (shown.size + 2) / 3
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(
                        cardH * rows + 12.dp * (rows - 1).coerceAtLeast(0) + 8.dp
                    ),
                ) {
                    items(shown) { w ->
                        WorldCard(w, { onWorld(w.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color(0xFFF1F3F5),
                RoundedCornerShape(28.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else Color(0xFF334155),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp,
        )
    }
}
