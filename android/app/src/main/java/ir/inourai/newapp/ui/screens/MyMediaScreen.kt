package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.GenerationStatus
import ir.inourai.newapp.data.LikedGeneration
import ir.inourai.newapp.data.Session
import kotlinx.coroutines.launch

/** تصاویر من (مثل رقیب): سگمنت قلب | عکس؛ تب قلب دنیاهای نشان‌شده را
 *  با همان خالی‌نما نشان می‌دهد، تب عکس ساخته‌های خود کاربر را. */
@Composable
fun MyMediaScreen(
    onCreate: () -> Unit,
    onWorld: (Long) -> Unit,
    onImage: (Long) -> Unit,
    onLogin: () -> Unit,
    onGoHome: () -> Unit,
) {
    var tab by remember { mutableStateOf(1) } // 0 = قلب، 1 = عکس
    var images by remember { mutableStateOf<List<GenerationStatus>>(emptyList()) }
    var liked by remember { mutableStateOf<List<LikedGeneration>>(emptyList()) }
    var submitted by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val loggedIn = Session.token != null

    // ضربه به تب «تصاویر من» وقتی همین صفحه بالاست، شبکه‌ی عکس‌ها
    // نشان داده می‌شود تا کاربر روی همان صفحه گیر نکند.
    val resetTick = MyMediaReset.tick
    LaunchedEffect(resetTick) {
        if (resetTick > 0) {
            tab = 1
        }
    }

    fun refresh() {
        scope.launch {
            try {
                images = Api.service.myGenerations()
                    .filter { it.kind == "image" }
            } catch (_: Exception) {
            }
            try {
                liked = Api.service.imageLikes()
            } catch (_: Exception) {
            }
            try {
                submitted = Api.service.myGallery().mapNotNull { it.generation_id }.toSet()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn) refresh()
    }

    // پیام کوتاه و موقت: بعد از ~۲٫۵ ثانیه محو می‌شود، متن ماندگار پایین صفحه نمی‌ماند.
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2500)
            notice = null
        }
    }

    // اگر ساختی در حال انجام است، هر ۴ ثانیه تازه کن تا آماده‌شدنش همین‌جا دیده شود.
    LaunchedEffect(images) {
        if (loggedIn && images.any { it.status == "queued" || it.status == "processing" }) {
            kotlinx.coroutines.delay(4000)
            refresh()
        }
    }

    // رفرش با کشیدن پایین، همان صفحه را تازه می‌کند (فهرست‌های عکس،
    // قلب و وضعیت ارسال) و هرگز به خانه برنمی‌گردد.
    PullRefreshLayout(onRefresh = { RefreshBus.refresh(); refresh() }) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // سگمنت قلب | عکس (مثل رقیب).
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .background(
                        if (tab == 0) Color(0xFF2E5BFF) else Color(0xFFF1F3F5),
                        RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                    )
                    .clickable { tab = 0 }
                    .padding(horizontal = 32.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    tint = if (tab == 0) Color.White else Color.Gray,
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        if (tab == 1) Color(0xFF2E5BFF) else Color(0xFFF1F3F5),
                        RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50),
                    )
                    .clickable { tab = 1 }
                    .padding(horizontal = 32.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Rounded.Image,
                    contentDescription = null,
                    tint = if (tab == 1) Color.White else Color.Gray,
                )
            }
        }

        if (!loggedIn) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("برای دیدن تصاویرت وارد شوید", color = Color.Gray)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onLogin) {
                    Text("ورود / ساخت حساب")
                }
            }
            return@Column
        }

        if (tab == 0) {
            // تب قلب حالا تصویرهای لایک‌شده را نشان می‌دهد (نه دنیاها)؛
            // آیتم‌ها هم‌شکل تب عکس‌اند و با لمس، جزئیات همان تصویر باز می‌شود.
            val likedDone = liked.filter { it.output_url != null }
            if (likedDone.isEmpty()) {
                // خالی‌نمای علاقه‌مندی‌ها، با همان متن‌های رقیب.
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = Color.LightGray,
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "هنوز تصویری را نپسندیده‌ای",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "قلب بالای هر تصویر را بزن تا اینجا بماند و بعداً سراغش بیایی",
                        color = Color.Gray,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onCreate) {
                        Text("مشاهدهٔ دنیاها")
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    items(likedDone) { item ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable {
                                    ResultSwipeContext.set(likedDone.map { it.id })
                                    onImage(item.id)
                                },
                        ) {
                            AsyncImage(
                                model = item.output_url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            if (item.id in submitted) {
                                Box(
                                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text("✓ نگارخانه", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        // v13: اعلان ۲۴ ساعته زیر هر تصویر رایگان؛ پولی هیچ اعلانی نمی‌گیرد.
                        // تب لایک in_gallery ندارد (از /generations/mine نمی‌آید)؛ اصلاح
                        // v14 فقط برای تب «تصاویر من» است.
                        if (item.is_free) {
                            Text(
                                "هدیه را دانلود کن؛ فقط ۲۴ ساعت می‌ماند",
                                color = Color(0xFFB7791F),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                val pending = images.filter { it.status == "queued" || it.status == "processing" }
                val done = images.filter { it.status == "done" && it.output_url != null }
                // فهرست سوایپ صفحه‌ی نتیجه: همان ترتیبی که شبکه نشان می‌دهد
                // (در انتظارها اول، بعد آماده‌ها).
                val swipeIds = pending.map { it.id } + done.map { it.id }
                if (images.isEmpty()) {
                    item {
                        Text("هنوز عکسی نساخته‌ای؛ از خانه یک دنیا را انتخاب کن.")
                    }
                }
                items(pending) { item ->
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1A1F2E))
                            .clickable {
                                ResultSwipeContext.set(swipeIds)
                                onImage(item.id)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("در حال ساخت…", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
                items(done) { item ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                ResultSwipeContext.set(swipeIds)
                                onImage(item.id)
                            },
                    ) {
                        AsyncImage(
                            model = item.output_url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        if (item.id in submitted) {
                            Box(
                                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text("✓ نگارخانه", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    // v14: اعلان ۲۴ ساعته فقط زیر تصویر رایگانِ بیرون از نگارخانه؛ پولی و گالری‌دار هیچ اعلانی نمی‌گیرند.
                    if (item.is_free && !item.in_gallery) {
                        Text(
                            "هدیه را دانلود کن؛ فقط ۲۴ ساعت می‌ماند",
                            color = Color(0xFFB7791F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    }
                }
            }
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("+ ساخت عکس جدید")
            }
        }
        }

        // بنر نوتیف شناور، مثل صفحه‌ی نتیجه.
        notice?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
    }
}