package ir.inourai.newapp.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Category
import ir.inourai.newapp.data.World

/** خانه، هم‌شکل طرح مرجع: سربرگ + چیپ دسته‌ها + بنر + دنیاهای محبوب. */
@Composable
fun HomeScreen(
    onPhoto: () -> Unit,
    onWorld: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    var worlds by remember { mutableStateOf<List<World>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    // null یعنی «پیشنهادی» (دنیاهای featured).
    var selected by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        try {
            worlds = Api.service.worlds()
        } catch (_: Exception) {
        }
        try {
            categories = Api.service.categories()
        } catch (_: Exception) {
        }
    }

    val shown = if (selected == null) {
        worlds.filter { it.is_featured }
    } else {
        worlds.filter { it.category_id == selected }
    }
    val heroSamples = worlds.mapNotNull { it.sample_image_url }.take(3)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✦ چهره‌ساز",
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                }
            }
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
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FE)),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "✨ چهره‌ساز!",
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Text(
                                "با هوش مصنوعی به سبک‌های مختلف عکس بساز",
                                fontSize = 14.sp,
                                color = Color(0xFF44546A),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        // کولاژ سه نمونه، کمی چرخیده.
                        Box(modifier = Modifier.width(110.dp).height(96.dp)) {
                            val aligns = listOf(
                                Alignment.CenterStart,
                                Alignment.TopEnd,
                                Alignment.BottomEnd,
                            )
                            for ((i, url) in heroSamples.withIndex()) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .align(aligns[i])
                                        .graphicsLayer(rotationZ = (i - 1) * 8f)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                    // دکمه‌ی پیکان، سمت چپ بنر (مثل طرح مرجع).
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        IconButton(
                            onClick = onPhoto,
                            modifier = Modifier
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        ) {
                            Icon(
                                Icons.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text("دنیاهای محبوب", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        if (shown.isEmpty()) {
            item {
                Text("دنیایی اینجا نیست؛ دسته‌ی دیگری را انتخاب کن.", color = Color.Gray)
            }
        } else {
            items(shown.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { w ->
                        WorldCard(w, { onWorld(w.id) }, Modifier.weight(1f))
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
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
                RoundedCornerShape(20.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else Color(0xFF334155),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp,
        )
    }
}
