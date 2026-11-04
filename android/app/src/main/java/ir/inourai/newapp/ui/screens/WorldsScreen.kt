package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Category
import ir.inourai.newapp.data.Favorites
import ir.inourai.newapp.data.World
import kotlinx.coroutines.launch

/** صفحه‌ی انتخاب دنیا برای ساخت عکس (فقط از «تصاویر من» با دکمه‌ی
 *  «+ ساخت عکس جدید» باز می‌شود): پیش‌فرض «همه» است تا همه‌ی دنیا‌ها
 *  همان اول دیده شوند، نه فقط ویژه‌ها؛ کشیدن به پایین هم همین فهرست را
 *  تازه می‌کند و دیگر کاربر را به خانه پرت نمی‌کند. */
@Composable
fun WorldsScreen(onWorld: (Long) -> Unit) {
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var worlds by remember { mutableStateOf<List<World>>(emptyList()) }
    // انتخاب بخش: 0 = «همه» (پیش‌فرض: همه‌ی دنیا‌ها)، ‎-1‎ = «پیشنهادی»
    // (فقط ویژه‌ها)، جز آن = شناسه‌ی دسته.
    var selected by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            try {
                categories = Api.service.categories()
                worlds = Api.service.worlds()
            } catch (_: Exception) {
            }
            Favorites.refresh()
        }
    }

    LaunchedEffect(Unit) { load() }

    val shown = when (selected) {
        0L -> worlds
        -1L -> worlds.filter { it.is_featured }
        else -> worlds.filter { it.category_id == selected }
    }

    PullRefreshLayout(onRefresh = { load(); RefreshBus.refresh() }) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "انتخاب دنیا برای ساخت عکس",
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selected == 0L,
                    onClick = { selected = 0L },
                    label = { Text("همه") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
            item {
                FilterChip(
                    selected = selected == -1L,
                    onClick = { selected = -1L },
                    label = { Text("پیشنهادی") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
            items(categories) { c ->
                FilterChip(
                    selected = selected == c.id,
                    onClick = { selected = c.id },
                    label = { Text(c.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Text("در این بخش هنوز دنیایی نیست.", color = Color.Gray)
                }
            }
            items(shown) { w ->
                WorldCard(w, { onWorld(w.id) })
            }
        }
    }
    }
}
