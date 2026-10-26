package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.GalleryItem
import ir.inourai.newapp.data.World

@Composable
fun HomeScreen(onPhoto: () -> Unit, onWorld: (Long) -> Unit) {
    var featured by remember { mutableStateOf<List<World>>(emptyList()) }
    var gallery by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        featured = try {
            Api.service.worlds().filter { it.is_featured }
        } catch (_: Exception) {
            emptyList()
        }
        // گالری خود اپ: فقط عکس‌های تاییدشده‌ی کاربران (هرگز گالری نورا نیست).
        gallery = try {
            Api.service.publicGallery()
        } catch (_: Exception) {
            emptyList()
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                ),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "🎭 چهره‌ساز",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                    Text(
                        "سلفی بده، تو دنیای قاجار، انیمه، سایبرپانک و... ببینش",
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(modifier = Modifier.padding(top = 12.dp))
                    Button(
                        onClick = onPhoto,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                        ),
                    ) {
                        Text(
                            "شروع کن",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        if (featured.isNotEmpty()) {
            item {
                Text("پیشنهادی‌ها", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(featured) { w ->
                        WorldCard(w, { onWorld(w.id) }, Modifier.width(160.dp))
                    }
                }
            }
        }
        item {
            Text("گالری", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        if (gallery.isEmpty()) {
            item {
                Text(
                    "هنوز عکسی در گالری تأیید نشده؛ اولین نفر باش!",
                    color = Color.Gray,
                )
            }
        } else {
            items(gallery.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item ->
                        AsyncImage(
                            model = item.image_url,
                            contentDescription = null,
                            modifier = Modifier.weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
