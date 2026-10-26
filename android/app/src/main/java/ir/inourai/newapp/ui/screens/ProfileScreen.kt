package ir.inourai.newapp.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ir.inourai.newapp.billing.BazaarBilling
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Balance
import ir.inourai.newapp.data.Pack
import ir.inourai.newapp.data.Session
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(onLogin: () -> Unit) {
    val context = LocalContext.current
    var balance by remember { mutableStateOf<Balance?>(null) }
    var packs by remember { mutableStateOf<List<Pack>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val loggedIn = Session.token != null

    fun refreshBalance() {
        scope.launch {
            balance = try {
                Api.service.balance()
            } catch (_: Exception) {
                null
            }
        }
    }

    // نتیجه‌ی Intent خرید بازار: وریفای سروری ← consume ← رفرش موجودی.
    val buyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        scope.launch {
            try {
                if (result.resultCode != Activity.RESULT_OK) {
                    notice = "خرید لغو شد"
                    return@launch
                }
                val purchase = BazaarBilling.parsePurchaseResult(result.data)
                    ?: run {
                        notice = "نتیجه‌ی خرید خوانا نبود"
                        return@launch
                    }
                BazaarBilling.verifyAndConsume(context, purchase)
                refreshBalance()
                notice = "سکه‌ها اضافه شد"
            } catch (e: Exception) {
                notice = e.message
            }
        }
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            refreshBalance()
        }
        packs = try {
            Api.service.packs()
        } catch (_: Exception) {
            emptyList()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!loggedIn) {
            Button(onClick = onLogin) { Text("ورود با موبایل") }
        } else {
            balance?.let {
                Text("سکه‌ها: ${it.coins}")
                Text("عکس رایگان باقی‌مانده: ${it.free_image}")
            }
            Button(
                onClick = {
                    Session.logout()
                    onLogin()
                },
            ) {
                Text("خروج")
            }
            Button(
                onClick = {
                    scope.launch {
                        try {
                            BazaarBilling.recoverPurchases(context)
                            refreshBalance()
                            notice = "بازیابی خریدها انجام شد"
                        } catch (e: Exception) {
                            notice = e.message
                        }
                    }
                },
            ) {
                Text("بازیابی خریدهای ناتمام")
            }
        }
        Text("بسته‌های سکه")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(packs) { p ->
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val sender = BazaarBilling.getBuyIntentSender(
                                    context,
                                    p.bazaar_sku,
                                )
                                buyLauncher.launch(
                                    IntentSenderRequest.Builder(sender).build(),
                                )
                            } catch (e: Exception) {
                                notice = e.message
                            }
                        }
                    },
                ) {
                    Text(
                        buildString {
                            if (p.badge != null) append("[${p.badge}] ")
                            if (p.name.isNotBlank()) append("${p.name} — ")
                            append("${p.coins} سکه — ${p.price_toman} تومان")
                        },
                    )
                }
            }
        }
        notice?.let { Text(it) }
    }
}
