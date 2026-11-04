package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

/** جستجو: فیلد متن + چیپ دسته‌ها + شبکه‌ی دنیا‌ها (مثل طرح مرجع). */
@Composable
fun SearchScreen(onWorld: (Long) -> Unit, onGoHome: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var worlds by remember { mutableStateOf<List<World>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selected by remember { mutableStateOf<Long?>(null) }
    // «همه»: جستجو بدون فیلتر دسته (برخلاف «پیشنهادی» که فقط featuredهاست).
    // پیش‌فرض «همه» است تا جستجو از ابتدا در همه‌ی دسته‌ها باشد.
    var showAll by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // رفرش با کشیدن پایین، همان جستجو را تازه می‌کند (فهرست دنیا‌ها و
    // دسته‌ها، با همان پرسمان و فیلتر فعلی) و هرگز به خانه برنمی‌گردد.
    fun refreshSearch() {
        scope.launch {
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
    }

    LaunchedEffect(Unit) {
        refreshSearch()
    }

    val shown = worlds.filter { w ->
        (showAll || selected == null && w.is_featured || selected != null && w.category_id == selected) &&
            (query.isBlank() || w.name.contains(query))
    }

    PullRefreshLayout(onRefresh = { RefreshBus.refresh(); refreshSearch() }) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("جستجوی دنیا") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = Color(0xFF1D6FE0),
                unfocusedBorderColor = Color(0xFFD4DCE8),
            ),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            item {
                SearchChip("همه", showAll) { showAll = true }
            }
            item {
                SearchChip("پیشنهادی", !showAll && selected == null) { showAll = false; selected = null }
            }
            items(categories) { c ->
                SearchChip(c.name, !showAll && selected == c.id) { showAll = false; selected = c.id }
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
                    Text("چیزی پیدا نشد.", color = Color.Gray)
                }
            }
            items(shown) { w ->
                WorldCard(w, { onWorld(w.id) })
            }
        }
    }
    }
}

@Composable
private fun SearchChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color(0xFFF1F3F5),
                RoundedCornerShape(28.dp),
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
