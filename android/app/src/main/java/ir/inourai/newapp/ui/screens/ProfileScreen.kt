package ir.inourai.newapp.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import android.content.Context
import ir.inourai.newapp.readCrashLog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.Balance
import ir.inourai.newapp.data.PaymentItem
import ir.inourai.newapp.data.PublicSettings
import ir.inourai.newapp.data.Session
import kotlinx.coroutines.launch

private val ScreenBg = Color(0xFFF4F5FB)
private val RoyalBlue = Color(0xFF2E5BFF)
private val IconBg = Color(0xFFE8EEFF)
private val GoldText = Color(0xFF8A5A00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onLogin: () -> Unit, onGoHome: () -> Unit) {
    val context = LocalContext.current
    var balance by remember { mutableStateOf<Balance?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var payments by remember { mutableStateOf<List<PaymentItem>>(emptyList()) }
    var showPacks by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showPayments by remember { mutableStateOf(false) }
    var maskedMobile by remember { mutableStateOf<String?>(null) }
    var pubSettings by remember { mutableStateOf(PublicSettings()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val loggedIn = Session.token != null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val paymentsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        pubSettings = try { Api.service.publicSettings() } catch (_: Exception) { PublicSettings() }
    }

    // سابقه‌ی پرداخت: بازیابی دستی حذف شد؛ خرید ناتمام موقع شروع اپ
    // خودکار بازیابی می‌شود و اینجا فقط تاریخچه‌ی پرداخت‌ها دیده می‌شود.
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            payments = try {
                Api.service.myPurchases()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun refreshBalance() {
        scope.launch {
            balance = try {
                Api.service.balance()
            } catch (_: Exception) {
                null
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
    }

    // موجودی در هر بار ورود به پروفایل (و بازگشت به اپ) تازه شود،
    // نه فقط موقع ورود؛ وگرنه تغییر سروریِ موجودی کهنه می‌ماند.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && Session.token != null) {
                refreshBalance()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PullRefreshLayout(onRefresh = { RefreshBus.refresh(); onGoHome() }) {
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
                    Text("سکه", color = Color.White, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${balance?.coins ?: 0}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                    )
                    if ((balance?.free_image ?: 0) > 0) {
                        Text(
                            "عکس رایگان باقی‌مانده: ${balance!!.free_image}",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // مثل رقیب: با سکه، «معادل تولید N تصویر»؛ بدون سکه،
                    // دعوت به اضافه‌کردن.
                    val coins = balance?.coins ?: 0
                    Text(
                        if (coins >= 10) "معادل تولید ${coins / 10} تصویر"
                        else "برای ساخت تصویر جدید سکه اضافه کن.",
                        color = Color.White,
                        fontSize = 15.sp,
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
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("افزایش سکه", fontWeight = FontWeight.Bold, fontSize = 17.sp)
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
                        icon = Icons.Rounded.Person,
                        title = "ورود / ساخت حساب",
                        subtitle = "با اولین ورود، ۱ تصویر هدیه بگیر",
                        onClick = onLogin,
                    )
                } else {
                    LinkRow(
                        icon = Icons.Rounded.Phone,
                        title = maskedMobile ?: "حساب شما",
                        titleDirection = if (maskedMobile != null) TextDirection.Ltr else null,
                        onClick = { showAccount = true },
                    )
                }
            }
        }
        if (loggedIn) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(28.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    LinkRow(
                        icon = Icons.Rounded.Receipt,
                        title = "سابقه پرداخت",
                        iconBg = Color(0xFFE6F4EA), iconTint = Color(0xFF1B7A3D),
                        onClick = { showPayments = true },
                    )
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
                        icon = Icons.Rounded.HelpOutline,
                        title = "پشتیبانی",
                        iconBg = Color(0xFFE6F7EC), iconTint = Color(0xFF1B7A3D),
                        onClick = { dialog = "support" },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    LinkRow(
                        icon = Icons.Rounded.BugReport,
                        title = "گزارش خطا",
                        iconBg = Color(0xFFFDE8E8), iconTint = Color(0xFFC0392B),
                        onClick = { dialog = "bug" },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    LinkRow(
                        icon = Icons.Rounded.Star,
                        title = "امتیاز به چهره‌ساز در بازار",
                        iconBg = Color(0xFFFFF3D6), iconTint = Color(0xFFB77900),
                        external = true,
                        onClick = {
                            val url = pubSettings.bazaar_url.ifBlank { "https://cafebazaar.ir/app/" + context.packageName }
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                            )
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    LinkRow(
                        icon = Icons.Rounded.Info,
                        title = "درباره ما",
                        iconBg = Color(0xFFE6F4F1), iconTint = Color(0xFF0E7C6B),
                        onClick = { dialog = "about" },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    LinkRow(
                        icon = Icons.Rounded.Shield,
                        title = "قوانین و حریم خصوصی",
                        iconBg = Color(0xFFEDE9FE), iconTint = Color(0xFF5B4DBC),
                        onClick = { dialog = "rules" },
                    )
                }
            }
        }
        notice?.let {
            // خطای فنی سرویس بازار (bazaar billing service not available)
            // دیگر پایین پروفایل نشان داده نمی‌شود.
            if (!it.contains("billing service", ignoreCase = true)) {
                item { Text(it) }
            }
        }
    }
    }

    // شیت جزئیات حساب (مثل رقیب): موبایل ماسک‌شده، عضویت، سکه، خروج.
    if (showAccount) {
        ModalBottomSheet(
            onDismissRequest = { showAccount = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("حساب کاربری", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("جزئیات حسابت", color = Color.Gray, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("شماره موبایل", color = Color.Gray)
                    Text(
                        maskedMobile ?: "—",
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(textDirection = TextDirection.Ltr),
                        textAlign = TextAlign.End,
                    )
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
        PacksSheet(
            onDismiss = { showPacks = false },
            onPurchased = { refreshBalance(); notice = "سکه‌ها اضافه شد" },
            onError = { notice = it },
            onLogin = onLogin,
        )
    }

    if (showPayments) {
        ModalBottomSheet(
            onDismissRequest = { showPayments = false },
            sheetState = paymentsSheetState,
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("سابقه پرداخت", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    if (payments.isEmpty()) {
                        Text("هنوز پرداختی ثبت نشده است.", color = Color.Gray)
                    } else {
                        // سقف ارتفاع تا وقتی پرداخت‌ها زیاد شدند، فهرست اسکرول بخورد
                        // (ستون اسکرولیِ بدون سقف، در باتم‌شیت تا تهِ صفحه کش می‌آمد و نمی‌چرخید).
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                            itemsIndexed(payments) { i, pay ->
                                if (i > 0) HorizontalDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(pay.pack_name, fontWeight = FontWeight.Bold)
                                        Text("${pay.coins} سکه", color = Color.Gray, fontSize = 14.sp)
                                        Text(pay.created_at?.take(10) ?: "", color = Color.Gray, fontSize = 13.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("${pay.amount_irr / 10} تومان", fontWeight = FontWeight.Bold)
                                        Text(
                                            if (pay.status == "pending") "در انتظار" else "پرداخت شد",
                                            color = if (pay.status == "pending") Color(0xFFB7791F) else Color(0xFF1B7A3D),
                                            fontSize = 14.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "about" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("درباره ما") },
            text = { Text(pubSettings.about_us.ifBlank { "چهره‌ساز — سلفی بده، یه چهره‌ی تازه بگیر." }) },
            confirmButton = {
                TextButton(onClick = { dialog = null }) { Text("باشه") }
            },
        )
        "support" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("پشتیبانی") },
            text = { Text(pubSettings.support_info.ifBlank { "پشتیبانی به‌زودی فعال می‌شود." }) },
            confirmButton = {
                TextButton(onClick = { dialog = null }) { Text("باشه") }
            },
        )
        "bug" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("گزارش خطا") },
            text = {
                val crash = readCrashLog(context)
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(pubSettings.bug_report_info.ifBlank { "برای گزارش خطا، متن خطا را همین‌جا بنویس و برای پشتیبانی بفرست." })
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (crash != null) "گزارش کرش اخیر (برای ارسال به پشتیبانی، «کپی گزارش» را بزن):\n$crash"
                        else "هنوز گزارش کرشی ثبت نشده است.",
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                val crash = readCrashLog(context)
                if (crash != null) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash))
                        notice = "گزارش کرش کپی شد"
                        dialog = null
                    }) { Text("کپی گزارش") }
                }
                TextButton(onClick = { dialog = null }) { Text("باشه") }
            },
        )
        "rules" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("قوانین و حریم خصوصی") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        pubSettings.rules_privacy.ifBlank {
                            "عکس‌های شما فقط برای ساخت تصویر ویرایش‌شده استفاده می‌شود " +
                                "و در حساب شما ذخیره می‌شود. سکه‌ها فقط داخل اپ مصرف می‌شوند."
                        },
                    )
                }
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
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    external: Boolean = false,
    titleDirection: TextDirection? = null,
    iconBg: Color = IconBg,
    iconTint: Color = RoyalBlue,
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
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                style = TextStyle(textDirection = titleDirection ?: TextDirection.Unspecified),
            )
            if (subtitle != null) {
                Text(subtitle, color = Color.Gray, fontSize = 14.sp)
            }
        }
        Icon(
            if (external) Icons.Rounded.OpenInNew else Icons.Rounded.KeyboardArrowLeft,
            contentDescription = null,
            tint = Color.Gray,
        )
    }
}
