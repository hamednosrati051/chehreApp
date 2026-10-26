package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.GallerySubmitRequest
import ir.inourai.newapp.data.GenerationStatus
import kotlinx.coroutines.launch

/** تصاویر من: همه‌ی عکس‌های ساخته‌شده‌ی خود کاربر، مستقل از وضعیت گالری.
 *  هر عکس را می‌شود به صف تایید گالری خود اپ فرستاد. */
@Composable
fun MyMediaScreen() {
    var images by remember { mutableStateOf<List<GenerationStatus>>(emptyList()) }
    var submitted by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            images = Api.service.myGenerations()
                .filter { it.kind == "image" && it.status == "done" && it.output_url != null }
        } catch (_: Exception) {
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (images.isEmpty()) {
            item {
                Text("هنوز عکسی نساخته‌ای؛ از خانه یک دنیا را انتخاب کن.")
            }
        }
        items(images) { item ->
            Card(shape = RoundedCornerShape(16.dp)) {
                Column {
                    AsyncImage(
                        model = item.output_url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
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
}
