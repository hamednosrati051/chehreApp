package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("جستجوی دنیا") },
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(worlds.filter { it.name.contains(query) && query.isNotBlank() }) {
                Text(
                    text = "دنیا: ${it.name}",
                    modifier = Modifier.clickable { onWorld(it.id) },
                )
            }
        }
    }
}
