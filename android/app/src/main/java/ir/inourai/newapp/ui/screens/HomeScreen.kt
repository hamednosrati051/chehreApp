package ir.inourai.newapp.ui.screens
import ir.inourai.newapp.ui.theme.themed

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ir.inourai.newapp.R
import ir.inourai.newapp.data.Announcement
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Category
import ir.inourai.newapp.data.Favorites
import ir.inourai.newapp.data.Session
import ir.inourai.newapp.data.Trend
import ir.inourai.newapp.data.World

/** خانه، هم‌شکل اپ مرجع: سربرگ برند + چیپ دسته‌ها + ردیف ترندها
 *  (کاروسل با نقطه‌ها) + آواتارهای دایره‌ای + شبکه‌ی تازه‌ها. */
@Composable
fun HomeScreen(onWorld: (Long) -> Unit) {
    val context = LocalContext.current
    var worlds by remember { mutableStateOf<List<World>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    // اعلان‌های داخل اپ (endpoint عمومی؛ خطا یعنی هیچ بنری).
    var announcements by remember { mutableStateOf<List<Announcement>>(emptyList()) }
    // ردشده‌ها فقط برای همین نشست؛ هیچ persistenceای ندارد.
    var dismissedAnnouncements by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // null یعنی هنوز خوانده نشده یا خواندنش ناموفق بوده (→ ترندهای پیش‌فرض).
    var trends by remember { mutableStateOf<List<Trend>?>(null) }
    // null یعنی «پیشنهادی» (دنیاهای featured).
    var selected by remember { mutableStateOf<Long?>(null) }
    val favIds by Favorites.ids

    // پاپ‌آپ هدیه‌ی ورود — برای هر ثبت‌نام جدید (بدون محدودیت یک بار در هر گوشی)،
    // و فقط وقتی هدیه روشن است.
    // v18 (5): مودال مقایسه‌ی رایگان/پولی (جفت باغ قاجار، دنیا ۲۰۴) با
    // مودال هدیه یکی شده؛ همان یک دکمه‌ی «شروع کن» و بس.
    // v18 (9): مودال پرومو — بعد از بسته‌شدن مودال هدیه، در همان باز شدن
    // اپ؛ اگر مودال هدیه‌ای در کار نباشد، مستقیم.
    var showGift by remember { mutableStateOf(false) }
    var showPromo by remember { mutableStateOf(false) }
    var promoPending by remember { mutableStateOf(false) }
    var promoTitle by remember { mutableStateOf("") }
    var promoBody by remember { mutableStateOf("") }
    var promoShowNumber by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val settings = try {
            Api.service.publicSettings()
        } catch (_: Exception) {
            null
        }
        if (Session.justRegistered) {
            Session.justRegistered = false
            // نبودن تنظیم یعنی روشن (پیش‌فرض بک‌اند)؛ خطا در خواندن یعنی پاپ‌آپ نشان داده نشود
            // تا وعده‌ی هدیه‌ای داده نشود که ثبت نشده است.
            val giftOn = settings?.let { s ->
                val v = s.entry_gift_enabled
                v != "0" && !v.equals("false", ignoreCase = true)
            } ?: false
            if (giftOn) {
                showGift = true
            }
        }
        // پرومو: فقط وقتی روشن است و شماره‌ی نمایش سرور از شماره‌ای که
        // کاربر دیده بزرگ‌تر باشد؛ ویرایش متن با همان شماره دوباره نشان نمی‌دهد.
        val showNumber = settings?.promo_modal_show_number?.toIntOrNull() ?: 0
        val promoOn = settings?.promo_modal_enabled?.let { v ->
            v != "0" && !v.equals("false", ignoreCase = true)
        } ?: false
        if (promoOn && showNumber > Session.promoSeenShowNumber) {
            val title = settings?.promo_modal_title ?: ""
            val body = settings?.promo_modal_body ?: ""
            if (title.isNotBlank() || body.isNotBlank()) {
                promoTitle = title
                promoBody = body
                promoShowNumber = showNumber
                if (showGift) promoPending = true else showPromo = true
            }
        }
    }
    if (showGift) {
        val dismissGift: () -> Unit = {
            showGift = false
            // پرومو بلافاصله بعد از هدیه، در همان باز شدن اپ — هرگز هم‌زمان.
            if (promoPending) {
                promoPending = false
                showPromo = true
            }
        }
        Dialog(onDismissRequest = dismissGift) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = themed(Color.White, Color(0xFF1E293B))),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "۱ تصویر رایگان هدیه گرفتید!",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "همون عکس، دو کیفیت — ببین تفاوت رو:",
                        fontSize = 13.sp,
                        color = themed(Color.Gray, Color(0xFFAAB4C4)),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    // جفت مقایسه (باغ قاجار، دنیا ۲۰۴): رایگان راست،
                    // پولی چپ با قاب طلایی — مثل موکاپ تاییدشده.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // رایگان (مدل ۷) — راست.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box {
                                Image(
                                    painter = painterResource(R.drawable.modal_free),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                        .clip(RoundedCornerShape(14.dp)),
                                )
                                QualityChip(
                                    isFree = true,
                                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("مدل رایگان", fontSize = 12.sp, color = themed(Color.Gray, Color(0xFFAAB4C4)))
                        }
                        // پولی (مدل ۶) — چپ، قاب طلایی.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box {
                                Image(
                                    painter = painterResource(R.drawable.modal_paid),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .border(2.dp, Color(0xFFE8B84B), RoundedCornerShape(14.dp)),
                                )
                                QualityChip(
                                    isFree = false,
                                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("مدل پولی", fontSize = 12.sp, color = themed(Color.Gray, Color(0xFFAAB4C4)))
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "با سکه، چهره و جزئیات قشنگ‌تر می‌شه",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = dismissGift,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text("شروع کن", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    // v18 (9): مودال پرومو — فقط بعد از هدیه (یا مستقیم، اگر هدیه‌ای
    // نباشد)؛ با بستن، شماره‌ی نمایش دیده‌شده ذخیره می‌شود.
    if (showPromo) {
        val dismissPromo: () -> Unit = {
            showPromo = false
            Session.promoSeenShowNumber = promoShowNumber
        }
        Dialog(onDismissRequest = dismissPromo) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = themed(Color.White, Color(0xFF1E293B))),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (promoTitle.isNotBlank()) {
                        Text(
                            promoTitle,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (promoBody.isNotBlank()) {
                        Text(
                            promoBody,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                    Button(
                        onClick = dismissPromo,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text("باشه", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    // بارگذاری داده؛ با هر pull-to-refresh (RefreshBus.tick) دوباره خوانده می‌شود.
    val refreshTick = RefreshBus.tick
    LaunchedEffect(refreshTick) {
        try {
            worlds = Api.service.worlds()
        } catch (_: Exception) {
        }
        try {
            categories = Api.service.categories()
        } catch (_: Exception) {
        }
        try {
            trends = Api.service.trends()
        } catch (_: Exception) {
            trends = null
        }
        try {
            announcements = Api.service.announcements()
        } catch (_: Exception) {
        }
        Favorites.refresh()
    }

    // ترندهای داینامیک از GET /trends (مرتب با sort_order)؛ اگر خواندنش
    // ناموفق باشد، ۴ ترند پیش‌فرض از روی فلگ‌های دنیا ساخته می‌شود تا
    // خانه هیچ‌وقت خالی نماند (رفتار قبلی حفظ می‌شود).
    val tarikhiId = categories.firstOrNull { it.slug == "tarikhi" }?.id
    val effectiveTrends: List<Trend> = trends?.sortedBy { it.sort_order } ?: listOf(
        Trend(0, "ترندهای اینستاگرام", "instagram", 1, "card",
            worlds.filter { it.is_instagram_trend }.map { it.id }),
        Trend(0, "ترندهای ایرانی", "iranian", 2, "round",
            worlds.filter { it.is_iranian_trend }.map { it.id }),
        Trend(0, "ترند ورزشی", "sport", 3, "round",
            worlds.filter { it.is_sport_trend }.map { it.id }),
        Trend(0, "ترند فصلی", "seasonal", 4, "round",
            worlds.filter { it.is_seasonal_trend }.map { it.id }),
    )
    // عضویت هر ترند: ترتیب دنیاها همان ترتیب worlds است (filter ترتیب
    // را حفظ می‌کند). ترند خالی: اینستاگرام → همه‌ی دنیاها؛ ایرانی →
    // تاریخی → featured؛ ورزشی/فصلی/سفارشی (کلید null) → پنهان.
    // اعضای ترند: دقیقاً به ترتیب world_ids خود ترند (نسخه‌ی ۱۰) —
    // قبلاً worlds.filter بود و ترتیب فهرست دنیاها را نگه می‌داشت،
    // برای همین مرتب‌سازی سروری در اپ دیده نمی‌شد.
    val worldsById = worlds.associateBy { it.id }
    fun trendMembers(t: Trend): List<World> {
        val ids = t.world_ids ?: emptyList()
        val members = ids.mapNotNull { worldsById[it] }
        if (members.isNotEmpty()) return members
        val key = (t.key ?: "").lowercase()
        return when {
            "instagram" in key -> worlds
            "iranian" in key -> worlds.filter { it.category_id == tarikhiId }
                .ifEmpty { worlds.filter { it.is_featured } }
            else -> emptyList()
        }
    }
    val renderedTrends = effectiveTrends.mapNotNull { t ->
        trendMembers(t).takeIf { it.isNotEmpty() }?.let { t to it }
    }
    val favWorlds = worlds.filter { it.id in favIds }
    // «تازه‌های هفته»: دنیاهای ۷ روز اخیر (جدیدترین اول)، حداکثر ۲۷ تا؛
    // اگر در این هفته دنیایی نیامده باشد، ۱۸ دنیای آخری که وارد شده‌اند.
    fun createdMillis(w: World): Long {
        return try {
            val s = (w.created_at ?: return 0L).replace("Z", "")
            java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
    val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
    // «تازه‌های هفته» با دخالت ادمین (نسخه‌ی ۱۰): سنجاق‌ها همیشه اول
    // (به ترتیب /worlds)، بعد خودکار ۷ روز اخیر، حداکثر ۲۷ تا؛ پنهان‌ها
    // هرگز نمی‌آیند. اگر هیچی نبود (نه سنجاق، نه تازه‌ی خودکار)،
    // ۱۸ دنیای آخر بدون پنهان‌ها. بدون دخالت ادمین، دقیقاً مثل قبل است.
    val freshPinned = worlds.filter { it.is_fresh_pinned }
    val freshAuto = worlds.filter {
        !it.is_fresh_pinned && !it.is_fresh_hidden && createdMillis(it) >= weekAgo
    }.sortedByDescending { createdMillis(it) }
    val fresh = (freshPinned + freshAuto).take(27)
    val freshFallback = worlds.filter {
        !it.is_fresh_hidden
    }.sortedByDescending { createdMillis(it) }.take(18)
    val shown = if (selected != null) {
        worlds.filter { it.category_id == selected }
    } else {
        fresh.ifEmpty { freshFallback }
    }
    // حالت کاروسل مال ردیف اول است، هر ترندی که باشد.
    val trendState = rememberLazyListState()
    val firstTrendSize = renderedTrends.firstOrNull()?.second?.size ?: 0
    val trendIndex by remember(trendState, firstTrendSize) {
        derivedStateOf {
            trendState.firstVisibleItemIndex.coerceIn(0, (firstTrendSize - 1).coerceAtLeast(0))
        }
    }

    PullRefreshLayout(onRefresh = { RefreshBus.refresh() }) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // بنر اعلان‌ها بالای همه‌ی محتوای خانه (بالای ترندها)؛
        // لیست خالی یعنی هیچ بنری و هیچ تغییری در چیدمان.
        items(announcements.filter { it.id !in dismissedAnnouncements }) { ann ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = themed(Color.White, Color(0xFF1E293B))),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ann.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(ann.body, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "✕",
                        fontSize = 16.sp,
                        color = themed(Color.Gray, Color(0xFFAAB4C4)),
                        modifier = Modifier.clickable {
                            dismissedAnnouncements = dismissedAnnouncements + ann.id
                        },
                    )
                }
            }
        }
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
        // ترندها و تازه‌های هفته فقط در «پیشنهادی»؛ در دسته‌ها
        // فقط دنیاهای همان دسته نشان داده می‌شود.
        if (selected == null) {
        // ترندهای داینامیک به ترتیب sort_order؛ فقط در «پیشنهادی».
        // اسکرول کاروسل + نقطه‌ها فقط مال ردیف اول است (موقعیتی، نه
        // وابسته به کلید ترند)؛ بقیه ردیف‌های ساده‌اند. شکل آیتم از
        // display_type می‌آید: card → کارت مستطیلی، round → آواتار دایره‌ای.
        // علاقه‌مندی‌ها زیر اولین ترند رندرشده می‌آید و اگر هیچ ترندی
        // رندر نشود، بالای بخش‌هاست.
        if (renderedTrends.isEmpty() && favWorlds.isNotEmpty()) {
            item {
                Text("علاقه‌مندی‌ها", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favWorlds) { w ->
                        WorldCard(w, { onWorld(w.id) }, Modifier.width(160.dp))
                    }
                }
            }
        }
        renderedTrends.forEachIndexed { index, (trend, members) ->
            item {
                Text(trend.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            item {
                if (trend.display_type == "round") {
                    // حلقه‌ی ترندهای دایره‌ای (نسخه‌ی ۱۰): گرادیان آبی
                    // تم (#1D6FE0→#0B3D91) به‌صورت قاب ۲ پیکسلی جدا، با ۴
                    // پیکسل فاصله از دایره؛ دایره‌ی بیرونی ۸۸، تصویر ۷۶.
                    // null یعنی پیش‌فرض روشن؛ خاموش = دایره‌ی ساده‌ی قبلی.
                    val ringOn = trend.show_ring != false
                    LazyRow(
                        state = if (index == 0) trendState else rememberLazyListState(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(members) { w ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(if (ringOn) 92.dp else 76.dp).clickable { onWorld(w.id) },
                            ) {
                                if (ringOn) {
                                    Box(
                                        modifier = Modifier.size(88.dp)
                                            .border(
                                                2.dp,
                                                Brush.linearGradient(
                                                    listOf(Color(0xFF1D6FE0), Color(0xFF0B3D91)),
                                                ),
                                                CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AsyncImage(
                                            model = w.sample_image_url,
                                            contentDescription = w.name,
                                            modifier = Modifier.size(76.dp).clip(CircleShape),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                } else {
                                    AsyncImage(
                                        model = w.sample_image_url,
                                        contentDescription = w.name,
                                        modifier = Modifier.size(72.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
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
                } else {
                    LazyRow(
                        state = if (index == 0) trendState else rememberLazyListState(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(members) { w ->
                            WorldCard(w, { onWorld(w.id) }, Modifier.width(160.dp))
                        }
                    }
                }
            }
            if (index == 0) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        repeat(firstTrendSize) { i ->
                            Box(
                                modifier = Modifier
                                    .size(if (i == trendIndex) 8.dp else 6.dp)
                                    .background(
                                        if (i == trendIndex) MaterialTheme.colorScheme.primary
                                        else themed(Color.LightGray, Color(0xFF4A5568)),
                                        CircleShape,
                                    ),
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                if (favWorlds.isNotEmpty()) {
                    item {
                        Text("علاقه‌مندی‌ها", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(favWorlds) { w ->
                                WorldCard(w, { onWorld(w.id) }, Modifier.width(160.dp))
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("تازه‌های هفته", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        }
        if (shown.isEmpty()) {
            item {
                Text("دنیایی اینجا نیست؛ دسته‌ی دیگری را انتخاب کن.", color = themed(Color.Gray, Color(0xFFAAB4C4)))
            }
        } else {
            item {
                // ارتفاع شبکه از روی نسبت کارت حساب می‌شود تا اسکرول
                // تودرتو پیش نیاید (پیشنهادی ۳ ستون، دسته‌ها ۲ ستون).
                // در «پیشنهادی» (تازه‌ها) کارت‌ها یک‌پنجم کوتاه‌ترند.
                val cols = if (selected == null) 3 else 2
                val screenW = LocalConfiguration.current.screenWidthDp.dp
                val cardW = (screenW - 32.dp - 12.dp * (cols - 1)) / cols
                val cardH = cardW * 4 / 3 * if (selected == null) 0.8f else 1f
                val rows = (shown.size + cols - 1) / cols
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(
                        cardH * rows + 12.dp * (rows - 1).coerceAtLeast(0) + 8.dp
                    ),
                ) {
                    items(shown) { w ->
                        WorldCard(w, { onWorld(w.id) }, aspectRatio = if (selected == null) 0.9375f else 3f / 4f)
                    }
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
                if (selected) MaterialTheme.colorScheme.primary else themed(Color(0xFFF1F3F5), Color(0xFF243247)),
                RoundedCornerShape(28.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color = if (selected) themed(Color.White, Color(0xFF0B1E3A)) else themed(Color(0xFF334155), Color(0xFFD7DEE8)),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp,
        )
    }
}
