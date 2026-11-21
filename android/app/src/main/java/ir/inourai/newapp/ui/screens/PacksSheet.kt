package ir.inourai.newapp.ui.screens
import ir.inourai.newapp.ui.theme.themed

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ir.inourai.newapp.billing.Billing
import ir.inourai.newapp.billing.BillingLaunchException
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Pack
import ir.inourai.newapp.data.Session
import ir.inourai.newapp.data.ZibalPurchaseRequest
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
    // v16: شناسه‌ی ردیف pending ساخته‌شده موقع tap-buy؛ موقع وریفای
    // همراه توکن فرستاده می‌شود تا سرور همان ردیف را وصل کند.
    var pendingPurchaseId by remember { mutableStateOf<Long?>(null) }
    // v17 فالبک یک‌باره: بعد از خطای واقعی یک درگاه، دکمه‌ی درگاه دیگر
    // فقط در همین باز شدن شیت یک بار نشان داده می‌شود؛ با بستن شیت
    // می‌رود (remember همین کامپوزابل است). fallbackUsed جلوی پیشنهاد
    // دوباره در همان باز شدن را می‌گیرد.
    var fallbackOffer by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var fallbackUsed by remember { mutableStateOf(false) }
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

    // v17 زیبال: موقع برگشت به اپ، خریدهای زیبالِ در انتظار را از سرور
    // بپرس (G1)؛ اگر تسویه شده باشند، onPurchased صدا زده می‌شود و
    // موجودی به‌روز می‌شود. ردیفِ هنوز pending دوباره recheck می‌شود —
    // اگر کاربر پرداخت نکرده باشد، failed می‌شود (مثل recheck نورا).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    try {
                        val pendings = Api.service.pendingPurchases()
                        for (pp in pendings) {
                            if (pp.gateway == "zibal") {
                                val res = Api.service.recheckZibalPurchase(pp.id)
                                if (res.status == "consumed") {
                                    onPurchased()
                                    if (res.gift_coins > 0) onGift(res.gift_coins)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                val gift = Billing.verifyAndConsume(context, purchase, pendingPurchaseId)
                pendingPurchaseId = null
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
                if (Billing.isPoolakey) {
                    // v17 بازار با Poolakey: ردیف pending فقط وقتی ساخته
                    // می‌شود که صفحه‌ی پرداخت دارد باز می‌شود
                    // (purchaseFlowBegan) — B2؛ شکستِ بازشدن هیچ ردیفی
                    // جا نمی‌گذارد.
                    val purchase = Billing.purchaseProduct(context, p.bazaar_sku) {
                        scope.launch { pendingPurchaseId = Billing.createPendingPurchase(p.id) }
                    } ?: run {
                        onError("خرید لغو شد")
                        return@launch
                    }
                    val gift = Billing.verifyAndConsume(context, purchase, pendingPurchaseId)
                    pendingPurchaseId = null
                    onPurchased()
                    if (gift > 0) onGift(gift)
                    onDismiss()
                } else {
                    pendingPurchaseId = Billing.createPendingPurchase(p.id)
                    val sender = Billing.getBuyIntentSender(context, p.bazaar_sku)
                    buyLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
            } catch (e: Exception) {
                // v17 فالبک: فقط خطای راه‌اندازی خرید (BillingLaunchException) —
                // نه لغو کاربر (null برمی‌گردد، نه استثنا) و نه خطای وریفای
                // بعد از پرداخت — پیشنهاد یک‌باره‌ی درگاه دیگر را می‌آورد.
                if (e is BillingLaunchException && p.show_fallback_button && !fallbackUsed) {
                    fallbackOffer = p.id to "zibal"
                }
                onError(billingErrorMessage(e))
            } finally {
                buyingSku = null
            }
        }
    }

    // v17 زیبال: ساخت پرداخت در سرور + بازکردن صفحه‌ی پرداخت در مرورگر
    // خارجی (G1)؛ نتیجه موقع برگشت به اپ، با recheck از سرور خوانده
    // می‌شود (اثر ON_RESUME همین فایل). دکمه‌ی زیبال فقط وقتی هست که
    // پک zibal_enabled باشد؛ سرور هم نبودنش را با ۴۰۳ رد می‌کند.
    fun buyZibal(p: Pack, fallback: Boolean = false) {
        if (!loggedIn) {
            onDismiss()
            onError("برای خرید اول وارد شوید")
            onLogin()
            return
        }
        scope.launch {
            buyingSku = p.bazaar_sku
            try {
                val created = Api.service.createZibalPurchase(ZibalPurchaseRequest(pack_id = p.id, fallback = fallback))
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(created.payment_url)))
            } catch (e: Exception) {
                // v17 فالبک: خطای ساخت زیبال (قبل از باز شدن مرورگر) —
                // پیشنهاد یک‌باره‌ی بازار را می‌آورد.
                if (p.show_fallback_button && !fallbackUsed) {
                    fallbackOffer = p.id to "bazaar"
                }
                onError("خرید ناموفق بود؛ دوباره تلاش کن")
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
            colors = CardDefaults.cardColors(containerColor = themed(Color.White, Color(0xFF1E293B))),
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
                                        color = themed(PacksGold, Color(0xFFE8B84B)),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                if (p.pack_type != null) {
                                    Text(
                                        p.pack_type,
                                        color = themed(PacksBlue, Color(0xFF7AA8F0)),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Text(
                                "${p.coins} سکه",
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            // v18 (10): قیمت اصلی خط‌خورده + قیمت پرداختی
                            // پررنگ، فقط وقتی قیمت اصلی بزرگ‌تر است؛
                            // وگرنه همان یک قیمتِ امروز. دکمه‌ها بی‌تغییرند.
                            val original = p.original_price_toman
                            if (original != null && original > p.price_toman) {
                                Text(
                                    "$original تومان",
                                    color = themed(Color(0xFF9AA3AF), Color(0xFFAAB4C4)),
                                    fontSize = 13.sp,
                                    style = TextStyle(textDecoration = TextDecoration.LineThrough),
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                Text(
                                    "${p.price_toman} تومان",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            } else {
                                Text(
                                    "${p.price_toman} تومان",
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            // v11 هدیه‌ی خرید: فقط وقتی پک هدیه دارد.
                            if (p.gift_coins > 0) {
                                Text(
                                    "+${p.gift_coins} سکه هدیه" + if (p.gift_scope == "first") " (فقط خرید اول)" else "",
                                    color = themed(PacksGold, Color(0xFFE8B84B)),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        // v17: هر پک دو دکمه دارد — بازار بالا، زیبال زیر آن؛
                        // هر کدام فقط وقتی هست که پرچم همان پک روشن باشد.
                        Column(horizontalAlignment = Alignment.End) {
                            if (p.bazaar_enabled) {
                                Button(
                                    onClick = { buy(p) },
                                    enabled = buyingSku == null,
                                ) {
                                    Text(if (buyingSku == p.bazaar_sku) "در حال اتصال…" else "خرید")
                                }
                            }
                            if (p.zibal_enabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                // v17: دکمه‌ی زیبال دقیقاً همان دکمه‌ی بازار است
                                // (Button پر، نه Outlined) — فقط متنش فرق می‌کند.
                                Button(
                                    onClick = { buyZibal(p) },
                                    enabled = buyingSku == null,
                                ) {
                                    Text(if (buyingSku == p.bazaar_sku) "در حال انتقال…" else "پرداخت با زیبال")
                                }
                            }
                            // v17 فالبک یک‌باره: بعد از خطای واقعی یک درگاه،
                            // دکمه‌ی درگاه دیگر فقط در همین باز شدن شیت می‌آید —
                            // حتی اگر پرچم آن درگاه خاموش باشد.
                            if (fallbackOffer?.first == p.id && fallbackOffer?.second == "zibal") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "پرداخت با بازار نشد؛ با زیبال امتحان کن؟",
                                    fontSize = 12.sp,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = { fallbackUsed = true; fallbackOffer = null; buyZibal(p, fallback = true) },
                                    enabled = buyingSku == null,
                                ) {
                                    Text(if (buyingSku == p.bazaar_sku) "در حال انتقال…" else "پرداخت با زیبال")
                                }
                            }
                            if (fallbackOffer?.first == p.id && fallbackOffer?.second == "bazaar") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "پرداخت با زیبال نشد؛ با بازار امتحان کن؟",
                                    fontSize = 12.sp,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = { fallbackUsed = true; fallbackOffer = null; buy(p) },
                                    enabled = buyingSku == null,
                                ) {
                                    Text(if (buyingSku == p.bazaar_sku) "در حال اتصال…" else "خرید")
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
