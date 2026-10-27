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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.GallerySubmitRequest
import ir.inourai.newapp.data.GenerationStatus
import ir.inourai.newapp.data.Session
import ir.inourai.newapp.data.World
import kotlinx.coroutines.launch

/** تصاویر من (مثل رقیب): سگمنت قلب | عکس؛ تب قلب دنیاهای نشان‌شده را
 *  با همان خالی‌نما نشان می‌دهد، تب عکس ساخته‌های خود کاربر را. */
@Composable
fun MyMediaScreen(
    onCreate: () -> Unit,
    onWorld: (Long) -> Unit,
    onImage: (Long) -> Unit,
    onLogin: () -> Unit,
) {
    var tab by remember { mutableStateOf(1) } // 0 = قلب، 1 = عکس
    var images by remember { mutableStateOf<List<GenerationStatus>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<World>>(emptyList()) }
    var submitted by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val loggedIn = Session.token != null

    fun refresh() {
        scope.launch {
            try {
                images = Api.service.myGenerations()
                    .filter { it.kind == "image" }
            } catch (_: Exception) {
            }
            try {
                favorites = Api.service.favorites()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn) refresh()
    }

    // اگر ساختی در حال انجام است، هر ۴ ثانیه تازه کن تا آماده‌شدنش همین‌جا دیده شود.
    LaunchedEffect(images) {
        if (loggedIn && images.any { it.status == "queued" || it.status == "processing" }) {
            kotlinx.coroutines.delay(4000)
            refresh()
        }
    }

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
                    Icons.Filled.FavoriteBorder,
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
                    Icons.Filled.Image,
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
            if (favorites.isEmpty()) {
                // خالی‌نمای علاقه‌مندی‌ها، با همان متن‌های رقیب.
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = Color.LightGray,
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "هنوز دنیایی را نپسندیده‌ای",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "قلب بالای هر دنیا را بزن تا اینجا بماند و بعداً سراغش بیایی",
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
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    items(favorites) { w ->
                        WorldCard(w, { onWorld(w.id) })
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val pending = images.filter { it.status == "queued" || it.status == "processing" }
                val failed = images.filter { it.status == "failed" }
                val done = images.filter { it.status == "done" && it.output_url != null }
                if (images.isEmpty()) {
                    item {
                        Text("هنوز عکسی نساخته‌ای؛ از خانه یک دنیا را انتخاب کن.")
                    }
                }
                items(pending) { item ->
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onImage(item.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("در حال ساخت…", fontWeight = FontWeight.Bold)
                                Text(item.world_name ?: "", color = Color.Gray, fontSize = 13.sp)
                                Text("برای دیدن نتیجه بزن", color = Color.Gray, fontSize = 13.sp)
                            }
                        }
                    }
                }
                items(failed) { item ->
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onImage(item.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFD32F2F))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("ساخت ناموفق شد", fontWeight = FontWeight.Bold)
                                Text(item.world_name ?: "", color = Color.Gray, fontSize = 13.sp)
                                Text("برای دیدن علت بزن (سکه کم نشده)", color = Color.Gray, fontSize = 13.sp)
                            }
                        }
                    }
                }
                items(done) { item ->
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column {
                            AsyncImage(
                                model = item.output_url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                    .clickable { onImage(item.id) },
                                contentScale = ContentScale.Crop,
                            )
                            if (item.id !in submitted) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                Api.service.gallerySubmit(
                                                    GallerySubmitRequest(item.id),
                                                )
                                                submitted = submitted + item.id
                                                notice = "به صف تایید گالری فرستاده شد"
                                            } catch (e: Exception) {
                                                notice = e.message
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                ) {
                                    Text("ارسال به گالری")
                                }
                            } else {
                                Text(
                                    "در صف تایید گالری",
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                }
                notice?.let { item { Text(it) } }
            }
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("+ ساخت عکس جدید")
            }
        }
    }
}
