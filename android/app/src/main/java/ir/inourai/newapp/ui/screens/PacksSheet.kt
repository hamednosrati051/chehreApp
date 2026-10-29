package ir.inourai.newapp.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import ir.inourai.newapp.billing.BazaarBilling
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Pack
import ir.inourai.newapp.data.Session
import kotlinx.coroutines.launch

private val PacksGold = Color(0xFF8A5A00)
private val PacksBlue = Color(0xFF2E5BFF)

/** شیت مشترک خرید بسته‌های سکه (پروفایل و صفحه‌ی ساخت هر دو همین را
 *  باز می‌کنند): فهرست پک‌ها + خرید بازار ← وریفای سروری ← consume. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacksSheet(
    onDismiss: () -> Unit,
    onPurchased: () -> Unit,
    onError: (String) -> Unit,
    onLogin: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var packs by remember { mutableStateOf<List<Pack>>(emptyList()) }
    var sheetError by remember { mutableStateOf<String?>(null) }
    var buyingSku by remember { mutableStateOf<String?>(null) }
    val loggedIn = Session.token != null

    LaunchedEffect(Unit) {
        packs = try {
            Api.service.packs()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // نتیجه‌ی Intent خرید بازار: وریفای سروری ← consume ← onPurchased.
    val buyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        scope.launch {
            try {
                if (result.resultCode != Activity.RESULT_OK) {
                    sheetError = "خرید لغو شد"
                    onError("خرید لغو شد")
                    return@launch
                }
                val purchase = BazaarBilling.parsePurchaseResult(result.data)
                    ?: run {
                        sheetError = "نتیجه‌ی خرید خوانا نبود"
                        onError("نتیجه‌ی خرید خوانا نبود")
                        return@launch
                    }
                BazaarBilling.verifyAndConsume(context, purchase)
                onPurchased()
                onDismiss()
            } catch (e: Exception) {
                val msg = e.message ?: "خطایی پیش آمد"
                sheetError = msg
                onError(msg)
            }
        }
    }

    fun buy(p: Pack) {
        if (!loggedIn) {
            onDismiss()
            onError("برای خرید اول وارد شوید")
            onLogin()
            return
        }
        scope.launch {
            sheetError = null
            buyingSku = p.bazaar_sku
            try {
                val sender = BazaarBilling.getBuyIntentSender(context, p.bazaar_sku)
                buyLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } catch (e: Exception) {
                val msg = e.message ?: "خطایی پیش آمد"
                sheetError = msg
                onError(msg)
            } finally {
                buyingSku = null
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("بسته‌های سکه", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))
                sheetError?.let {
                    Text(it, color = Color.Red, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (packs.isEmpty()) {
                    Text("بسته‌ای نیست.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                packs.forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (p.name.isNotBlank()) {
                                    Text(p.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                if (p.badge != null) {
                                    Text(
                                        p.badge,
                                        color = PacksGold,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                if (p.pack_type != null) {
                                    Text(
                                        p.pack_type,
                                        color = PacksBlue,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Text(
                                "${p.coins} سکه — ${p.price_toman} تومان",
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Button(
                            onClick = { buy(p) },
                            enabled = buyingSku == null,
                        ) {
                            Text(if (buyingSku == p.bazaar_sku) "در حال اتصال…" else "خرید")
                        }
                    }
                    HorizontalDivider()
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
