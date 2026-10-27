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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Category
import ir.inourai.newapp.data.Favorites
import ir.inourai.newapp.data.World

@Composable
fun WorldsScreen(onWorld: (Long) -> Unit, onGoHome: () -> Unit) {
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var worlds by remember { mutableStateOf<List<World>>(emptyList()) }
    // null = تب «پیشنهادی» (برچسب، نه دسته‌ی واقعی).
    var selectedCategory by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        try {
            categories = Api.service.categories()
            worlds = Api.service.worlds()
        } catch (_: Exception) {
        }
        Favorites.refresh()
    }

    val shown = if (selectedCategory == null) {
        worlds.filter { it.is_featured }
    } else {
        worlds.filter { it.category_id == selectedCategory }
    }

    PullRefreshLayout(onRefresh = { RefreshBus.refresh(); onGoHome() }) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("پیشنهادی") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
            items(categories) { c ->
                FilterChip(
                    selected = selectedCategory == c.id,
                    onClick = { selectedCategory = c.id },
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