package ir.inourai.newapp.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    fun buy(p: Pack) {
        scope.launch {
            try {
                val sender = BazaarBilling.getBuyIntentSender(context, p.bazaar_sku)
                buyLauncher.launch(IntentSenderRequest.Builder(sender).build())
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!loggedIn) {
            item {
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("ورود با موبایل")
                }
            }
        } else {
            balance?.let {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFBF3DC),
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "🪙 سکه‌ها: ${it.coins}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Text(
                                "عکس رایگان باقی‌مانده: ${it.free_image}",
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            Session.logout()
                            onLogin()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("خروج")
                    }
                    OutlinedButton(
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
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("بازیابی خریدها")
                    }
                }
            }
        }
        item {
            Text("بسته‌های سکه", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        items(packs) { p ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { buy(p) },
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.name.isNotBlank()) {
                            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        if (p.badge != null) {
                            Text(
                                p.badge,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        "${p.coins} سکه — ${p.price_toman} تومان",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        notice?.let {
            item { Text(it) }
        }
    }
}
