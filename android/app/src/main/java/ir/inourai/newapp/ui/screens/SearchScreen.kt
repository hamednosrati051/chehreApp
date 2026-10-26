package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.OutlinedTextField
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
import ir.inourai.newapp.data.World

@Composable
fun SearchScreen(onWorld: (Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    var worlds by remember { mutableStateOf<List<World>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            worlds = Api.service.worlds()
        } catch (_: Exception) {
        }
    }

    val results = if (query.isBlank()) {
        emptyList()
    } else {
        worlds.filter { it.name.contains(query) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("جستجوی دنیا") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            if (query.isBlank()) {
                item(span = { GridItemSpan(2) }) {
                    Text("نام دنیا را بنویس؛ مثلا قاجار یا انیمه.", color = Color.Gray)
                }
            } else if (results.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Text("چیزی پیدا نشد.", color = Color.Gray)
                }
            }
            items(results) { w ->
                WorldCard(w, { onWorld(w.id) })
            }
        }
    }
}
