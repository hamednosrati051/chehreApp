package ir.inourai.newapp.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.GallerySubmitRequest
import ir.inourai.newapp.data.GenerationRequest
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** سلفی انتخاب‌شده را به فایل واقعی در کش تبدیل می‌کند تا آپلود شود.
 *  (نسخه‌ی قبلی File("") خالی می‌فرستاد و آپلود همیشه خراب بود.) */
private fun uriToFile(context: Context, uri: Uri): File {
    val out = File(context.cacheDir, "selfie_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    }
    return out
}

@Composable
fun CreateImageScreen(worldId: Long) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Uri?>(null) }
    var jobId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf("idle") }
    var resultUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> picked = uri }

    // نظرسنجی وضعیت job تا آماده‌شدن.
    LaunchedEffect(jobId) {
        val id = jobId ?: return@LaunchedEffect
        while (true) {
            delay(3000)
            try {
                val st = Api.service.generation(id)
                status = st.status
                if (st.status == "done") {
                    resultUrl = st.output_url
                    break
                }
                if (st.status == "failed") {
                    error = st.error ?: "ساخت ناموفق بود"
                    break
                }
            } catch (_: Exception) {
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = { picker.launch("image/*") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("انتخاب سلفی")
        }
        picked?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Button(
            onClick = {
                scope.launch {
                    try {
                        val uri = picked ?: return@launch
                        val inputUrl = Api.uploadFile(uriToFile(context, uri))
                        val created = Api.service.createGeneration(
                            GenerationRequest(
                                kind = "image",
                                world_id = worldId,
                                input_url = inputUrl,
                            ),
                        )
                        jobId = created.id
                        status = "queued"
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
            },
            enabled = picked != null && jobId == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("بساز")
        }
        if (jobId != null && resultUrl == null && error == null) {
            GenerationLoading()
        }
        resultUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
            if (!submitted) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val id = jobId ?: return@launch
                                Api.service.gallerySubmit(GallerySubmitRequest(id))
                                submitted = true
                                notice = "به صف تایید گالری فرستاده شد"
                            } catch (e: Exception) {
                                notice = e.message
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("ارسال به گالری")
                }
            } else {
                Text("در صف تایید گالری")
            }
        }
        error?.let { Text(it) }
        notice?.let { Text(it) }
    }
}
