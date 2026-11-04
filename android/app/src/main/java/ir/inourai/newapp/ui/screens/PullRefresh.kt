package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** شمارنده‌ی سراسری تازه‌سازی خانه: هر pull روی هر صفحه یکی اضافه
 *  می‌کند و خانه با LaunchedEffect(tick) داده‌هایش را دوباره می‌خواند. */
object RefreshBus {
    var tick by mutableStateOf(0L)
        private set

    fun refresh() {
        tick++
    }
}

/** سیگنال «تب تصاویر من دوباره زده شد»: MyMediaScreen با شنیدنش نمایشگر
 *  باز را می‌بندد و به شبکه‌ی عکس‌ها برمی‌گردد. لازم است چون ضربه به تبی
 *  که همین حالا بالاست، در ناوبری launchSingleTop هیچ کاری نمی‌کند و
 *  نمایشگر تمام‌صفحه (حالت داخلی صفحه، نه مقصد ناوبری) باز می‌ماند. */
object MyMediaReset {
    var tick by mutableStateOf(0L)
        private set

    fun request() {
        tick++
    }
}

/** همان سیگنال برای تب نگارخانه: نمایشگر باز نگارخانه‌ی کاربران را می‌بندد
 *  (همان گیرکردن تب وقتی نمایشگر باز است). */
object GalleryReset {
    var tick by mutableStateOf(0L)
        private set

    fun request() {
        tick++
    }
}

/** کشیدن به پایین روی هر صفحه: وقتی به آستانه رسید، onRefresh اجرا
 *  می‌شود (در نگارخانه همان onRefresh به خانه برمی‌گردد؛ صفحه‌ی دنیا‌ها
 *  خودش را تازه می‌کند).
 *  بدون وابستگی جدید کار می‌کند چون Material3 نسخه‌ی ۱٫۲٫۱ کامپوننت
 *  آماده‌ی pull-to-refresh ندارد. */
@Composable
fun PullRefreshLayout(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var pull by remember { mutableStateOf(0f) }
    var refreshing by remember { mutableStateOf(false) }
    val latestRefresh by rememberUpdatedState(onRefresh)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 110.dp.toPx() } }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // کشیدن به بالا در حالی که pull باز است: اول pull را جمع کن.
                if (pull > 0f && available.y < 0f) {
                    val take = minOf(pull, -available.y)
                    pull -= take
                    return Offset(0f, -take)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (refreshing) return Offset.Zero
                if (available.y > 0f) {
                    // اضافه‌ی اسکرول رو به پایین یعنی لیست به سقف رسیده است.
                    pull += available.y
                    if (pull >= thresholdPx) {
                        pull = 0f
                        refreshing = true
                        latestRefresh()
                        scope.launch {
                            delay(1500)
                            refreshing = false
                        }
                    }
                    return Offset(available.x, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pull = 0f
                return Velocity.Zero
            }
        }
    }
    Column(modifier = modifier.fillMaxSize().nestedScroll(connection)) {
        if (pull > 0f || refreshing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { (if (refreshing) 56f else pull).toDp() }),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        CircularProgressIndicator(
                            progress = (pull / thresholdPx).coerceIn(0f, 1f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        if (refreshing) "در حال تازه‌سازی…" else "رها کنید تا تازه شود",
                        fontSize = 13.sp,
                        color = Color.Gray,
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}
