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
import ir.inourai.newapp.billing.Billing
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Pack
import ir.inourai.newapp.data.Session
import ir.inourai.newapp.data.billingErrorMessage
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
    // v11: بعد از خریدِ همراه با هدیه، با مقدار سکه‌ی هدیه صدا زده می‌شود
    // (۰ یعنی هدیه‌ای نبود). پیش‌فرض خالی تا فراخوان‌های قدیمی بی‌تغییر بمانند.
    onGift: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var packs by remember { mutableStateOf<List<Pack>>(emptyList()) }
    // خطا در دریافت فهرست بسته‌ها: به‌جای «بسته‌ای نیست» گمراه‌کننده،
    // پیام دریافت ناموفق نشان داده می‌شود.
    var packsLoadFailed by remember { mutableStateOf(false) }
    var buyingSku by remember { mutableStateOf<String?>(null) }
    val loggedIn = Session.token != null

    LaunchedEffect(Unit) {
        packs = try {
            packsLoadFailed = false
            Api.service.packs()
        } catch (_: Exception) {
            packsLoadFailed = true
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
                    onError("خرید لغو شد")
                    return@launch
                }
                val purchase = Billing.parsePurchaseResult(result.data)
                    ?: run {
                        onError("نتیجه‌ی خرید خوانا نبود")
                        return@launch
                    }
                val gift = Billing.verifyAndConsume(context, purchase)
                onPurchased()
                if (gift > 0) onGift(gift)
                onDismiss()
            } catch (e: Exception) {
                onError(billingErrorMessage(e))
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
            buyingSku = p.bazaar_sku
            try {
                val sender = Billing.getBuyIntentSender(context, p.bazaar_sku)
                buyLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } catch (e: Exception) {
                onError(billingErrorMessage(e))
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
                if (packs.isEmpty()) {
                    Text(
                        if (packsLoadFailed) "دریافت بسته‌ها ناموفق بود؛ دوباره تلاش کن"
                        else "بسته‌ای نیست.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            // v11 هدیه‌ی خرید: فقط وقتی پک هدیه دارد.
                            if (p.gift_coins > 0) {
                                Text(
                                    "+${p.gift_coins} سکه هدیه" + if (p.gift_scope == "first") " (فقط خرید اول)" else "",
                                    color = PacksGold,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
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
