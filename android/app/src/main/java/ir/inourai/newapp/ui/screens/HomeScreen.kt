package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onPhoto() },
        ) {
            Text("دنیاهای عکس", modifier = Modifier.padding(20.dp))
            Text(
                "سلفی بده، تو دنیای قاجار، انیمه، سایبرپانک و... ببینش",
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp),
            )
        }
        if (featured.isNotEmpty()) {
            Text("پیشنهادی‌ها")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(featured) { w ->
                    Column(modifier = Modifier.clickable { onWorld(w.id) }) {
                        AsyncImage(
                            model = w.sample_image_url,
                            contentDescription = w.name,
                        )
                        Text(w.name)
                    }
                }
            }
        }
        if (gallery.isNotEmpty()) {
            Text("گالری")
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(gallery) { item ->
                    AsyncImage(model = item.image_url, contentDescription = null)
                }
            }
        }
    }
}
