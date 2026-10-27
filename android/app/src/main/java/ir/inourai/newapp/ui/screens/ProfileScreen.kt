package ir.inourai.newapp.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

private val ScreenBg = Color(0xFFF4F5FB)
private val RoyalBlue = Color(0xFF2E5BFF)
private val IconBg = Color(0xFFE8EEFF)
private val GoldText = Color(0xFF8A5A00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onLogin: () -> Unit) {
    val context = LocalContext.current
    var balance by remember { mutableStateOf<Balance?>(null) }
    var packs by remember { mutableStateOf<List<Pack>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showPacks by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var maskedMobile by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val loggedIn = Session.token != null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                showPacks = false
                notice = "سکه‌ها اضافه شد"
            } catch (e: Exception) {
                notice = e.message
            }
        }
    }

    fun buy(p: Pack) {
        if (!loggedIn) {
            showPacks = false
            notice = "برای خرید اول وارد شوید"
            onLogin()
            return
        }
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
            scope.launch {
                try {
                    maskedMobile = Api.service.me().mobile_masked
                } catch (_: Exception) {
                }
            }
        }
        packs = try {
            Api.service.packs()
        } catch (_: Exception) {
            emptyList()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ScreenBg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // کارت آبی سکه — مثل طرح مرجع.
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = RoyalBlue),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("سکه", color = Color.White, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${balance?.coins ?: 0}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                    )
                    if ((balance?.free_image ?: 0) > 0) {
                        Text(
                            "عکس رایگان باقی‌مانده: ${balance!!.free_image}",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // مثل رقیب: با سکه، «معادل تولید N تصویر»؛ بدون سکه،
                    // دعوت به اضافه‌کردن.
                    val coins = balance?.coins ?: 0
                    Text(
                        if (coins >= 8) "معادل تولید ${coins / 8} تصویر"
                        else "برای ساخت تصویر جدید سکه اضافه کن.",
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showPacks = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = RoyalBlue,
                        ),
                        shape = RoundedCornerShape(50),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("افزایش سکه", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }

        item { SectionLabel("حساب کاربری") }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                if (!loggedIn) {
                    LinkRow(
                        icon = Icons.Filled.Person,
                        title = "ورود / ساخت حساب",
                        subtitle = "با اولین ورود، ۱ تصویر هدیه بگیر",
                        onClick = onLogin,
                    )
                } else {
                    LinkRow(
                        icon = Icons.Filled.Phone,
                        title = maskedMobile ?: "حساب شما",
                        subtitle = "عضویت رایگان",
                        onClick = { showAccount = true },
                    )
                }
            }
        }
        if (loggedIn) {
            item {
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("بازیابی خریدها")
                }
            }
        }

        item { SectionLabel("پشتیبانی و لینک‌ها") }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column {
                    LinkRow(
                        icon = Icons.Filled.HelpOutline,
                        title = "پشتیبانی و گزارش خطا",
                        onClick = { notice = "پشتیبانی به‌زودی فعال می‌شود" },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    LinkRow(
                        icon = Icons.Filled.Star,
                        title = "امتیاز به چهره‌ساز در بازار",
                        external = true,
                        onClick = {
                            val url = "https://cafebazaar.ir/app/" + context.packageName
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                            )
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    LinkRow(
                        icon = Icons.Filled.CameraAlt,
                        title = "اینستاگرام",
                        external = true,
                        onClick = { notice = "صفحه‌ی اینستاگرام به‌زودی معرفی می‌شود" },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    LinkRow(
                        icon = Icons.Filled.Info,
                        title = "درباره ما",
                        onClick = { dialog = "about" },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    LinkRow(
                        icon = Icons.Filled.Shield,
                        title = "قوانین و حریم خصوصی",
                        onClick = { dialog = "rules" },
                    )
                }
            }
        }
        notice?.let {
            item { Text(it) }
        }
    }

    // شیت جزئیات حساب (مثل رقیب): موبایل ماسک‌شده، عضویت، سکه، خروج.
    if (showAccount) {
        ModalBottomSheet(
            onDismissRequest = { showAccount = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("حساب کاربری", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("جزئیات حسابت", color = Color.Gray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("شماره موبایل", color = Color.Gray)
                    Text(maskedMobile ?: "—", fontWeight = FontWeight.Bold)
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("عضویت", color = Color.Gray)
                    Text("رایگان", fontWeight = FontWeight.Bold)
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("سکه", color = Color.Gray)
                    Text("${balance?.coins ?: 0}", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        showAccount = false
                        Session.logout()
                        onLogin()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("خروج")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showPacks) {
        ModalBottomSheet(
            onDismissRequest = { showPacks = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("بسته‌های سکه", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))
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
                                        color = GoldText,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                if (p.pack_type != null) {
                                    Text(
                                        p.pack_type,
                                        color = RoyalBlue,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Text(
                                "${p.coins} سکه — ${p.price_toman} تومان",
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Button(onClick = { buy(p) }) {
                            Text("خرید")
                        }
                    }
                    HorizontalDivider()
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    when (dialog) {
        "about" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("درباره ما") },
            text = { Text("چهره‌ساز — سلفی بده، یه چهره‌ی تازه بگیر.") },
            confirmButton = {
                TextButton(onClick = { dialog = null }) { Text("باشه") }
            },
        )
        "rules" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("قوانین و حریم خصوصی") },
            text = {
                Text(
                    "عکس‌های شما فقط برای ساخت تصویر ویرایش‌شده استفاده می‌شود " +
                        "و در حساب شما ذخیره می‌شود. سکه‌ها فقط داخل اپ مصرف می‌شوند.",
                )
            },
            confirmButton = {
                TextButton(onClick = { dialog = null }) { Text("باشه") }
            },
        )
        else -> Unit
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color.Gray,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    external: Boolean = false,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(IconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = RoyalBlue)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, color = Color.Gray, fontSize = 13.sp)
            }
        }
        Icon(
            if (external) Icons.Filled.OpenInNew else Icons.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = Color.Gray,
        )
    }
}
