package ir.inourai.newapp

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.farsitel.bazaar.core.BazaarSignIn
import com.farsitel.bazaar.core.callback.BazaarSignInCallback
import ir.inourai.newapp.billing.BazaarBilling
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.BazaarLinkRequest
import ir.inourai.newapp.data.DeviceTokenRequest
import ir.inourai.newapp.data.Session
import ir.inourai.newapp.ui.screens.CreateImageScreen
import ir.inourai.newapp.ui.screens.GalleryReset
import ir.inourai.newapp.ui.screens.GalleryScreen
import ir.inourai.newapp.ui.screens.HomeScreen
import ir.inourai.newapp.ui.screens.LoginScreen
import ir.inourai.newapp.ui.screens.MyMediaReset
import ir.inourai.newapp.ui.screens.MyMediaScreen
import ir.inourai.newapp.ui.screens.ProfileScreen
import ir.inourai.newapp.ui.screens.ResultScreen
import ir.inourai.newapp.ui.screens.ResultSwipeContext
import ir.inourai.newapp.ui.screens.SearchScreen
import ir.inourai.newapp.ui.screens.SplashScreen
import ir.inourai.newapp.ui.screens.WorldsScreen
import ir.inourai.newapp.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// نسخه‌ی اول فقط تصویر است؛ بخش صدا پارک شده و هیچ صفحه/آیتم صدایی ندارد.
object Dest {
    const val HOME = "home"
    const val SEARCH = "search"
    const val MY_MEDIA = "my_media"
    const val GALLERY = "gallery"
    const val PROFILE = "profile"
    const val WORLDS = "worlds"
    const val CREATE_IMAGE = "create_image/{worldId}"
    const val LOGIN = "login"
    const val RESULT = "result/{genId}?fresh={fresh}"
}

class MainActivity : ComponentActivity() {
    companion object {
        /** از نوتیفیکیشن «تصویرت آماده شد»: بازکردن مستقیم نتیجه. */
        const val EXTRA_GEN_ID = "gen_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler(this)
        // رابط فارسی است؛ جهت را سراسری راست‌به‌چپ می‌کنیم تا نوار پایین و
        // ردیف‌ها روی گوشی‌هایی با زبان سیستم چپ‌به‌راست هم برعکس نشوند.
        window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        Session.init(this)
        val genId = intent.getLongExtra(EXTRA_GEN_ID, -1L)
        // اگر اجرای قبلی کرش کرده، گزارشش را «قبل» از شروع رابط کاربری نشان
        // بده تا حتی کرشِ موقع بازشدن هم قابل خواندن/کپی باشد. بعد از ادامه،
        // گزارش پاک می‌شود تا اجرای بعدی دوباره نشان داده نشود.
        val crash = readCrashLog(this)
        if (crash != null) {
            val scroll = android.widget.ScrollView(this)
            val tv = android.widget.TextView(this)
            tv.text = crash
            tv.textSize = 12f
            tv.setPadding(32, 24, 32, 24)
            scroll.addView(tv)
            android.app.AlertDialog.Builder(this)
                .setTitle("گزارش کرش اجرای قبلی")
                .setView(scroll)
                .setPositiveButton("کپی گزارش") { _, _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash))
                    clearCrashLog(this)
                    setContent { App(initialGenId = genId) }
                }
                .setNegativeButton("ادامه") { _, _ ->
                    clearCrashLog(this)
                    setContent { App(initialGenId = genId) }
                }
                .setCancelable(false)
                .show()
        } else {
            setContent { App(initialGenId = genId) }
        }
    }
}

@Composable
fun App(initialGenId: Long = -1L) {
    val nav = rememberNavController()
    // جابه‌جایی تب‌ها بدون انباشتن پشته و بدون لگد: تک‌نمونه، بازیابی حالت، گذر fade.
    val navToTab: (String) -> Unit = { dest ->
        nav.navigate(dest) {
            launchSingleTop = true
            restoreState = true
            popUpTo(nav.graph.startDestinationId) { saveState = true }
        }
    }
    // ضربه به زبانه: اگر مقصد همین حالا در پشته هست (مثلاً «تصاویر من»
    // زیر «دنیاها» یا «ساخت عکس»)، اول با popBackStack به همان نمونه‌ی
    // موجود برمی‌گردیم — دقیقاً رفتار دکمه‌ی برگشت سیستم که کار می‌کند —
    // و فقط وقتی در پشته نیست از navToTab استفاده می‌کنیم. مسیر
    // نابود-و-بازسازی navToTab از روی «دنیاها» روی دستگاه صفحه‌ی
    // تصاویر من را نشان نمی‌داد؛ این مسیر آن را دور می‌زند. باس‌های
    // ریست برای حالت «همین حالا روی همان زبانه‌ایم» سر جایشان می‌مانند.
    val goToTab: (String) -> Unit = { dest ->
        if (!nav.popBackStack(dest, inclusive = false)) {
            navToTab(dest)
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var showSplash by rememberSaveable { mutableStateOf(true) }
    val showNav = route != Dest.LOGIN && route != Dest.RESULT && !showSplash
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // فقط نتیجه‌ی درخواست مجوز اعلان؛ ردشدن هیچ کار دیگری نمی‌کند.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // اسپلش اول اپ: ۲ ثانیه، یا با ضربه رد می‌شود.
    LaunchedEffect(Unit) {
        delay(2000)
        showSplash = false
    }

    // بازیابی خریدهای ناتمام موقع شروع اپ (فقط وقتی لاگین کرده باشد):
    // خریدهای owned بازار دوباره به سرور فرستاده و (یک‌بار) شارژ و consume می‌شوند.
    LaunchedEffect(Session.token) {
        if (Session.token != null) {
            scope.launch {
                try {
                    BazaarBilling.recoverPurchases(context)
                } catch (_: Exception) {
                }
            }
        }
    }

    // همگام‌سازی توکن FCM + درخواست یک‌باره‌ی مجوز اعلان (فقط وقتی لاگین کرده باشد).
    // مجوز فقط وقتی درخواست می‌شود که داده نشده باشد و در این نصب هنوز
    // درخواست نشده باشد (پرچم chehre_prefs/notif_perm_asked)؛ ردشدن یعنی هیچ‌کاری.
    // ارسال توکن best-effort است و هرگز UI را مسدود یا کرش نمی‌کند.
    LaunchedEffect(Session.token) {
        if (Session.token == null) return@LaunchedEffect
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                val prefs = context.getSharedPreferences("chehre_prefs", android.content.Context.MODE_PRIVATE)
                if (!granted && !prefs.getBoolean("notif_perm_asked", false)) {
                    prefs.edit().putBoolean("notif_perm_asked", true).apply()
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } catch (_: Exception) {
        }
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) return@addOnCompleteListener
                val fcmToken = task.result ?: return@addOnCompleteListener
                scope.launch {
                    try {
                        Api.service.registerDeviceToken(DeviceTokenRequest(fcmToken))
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // بنر اعلان (نسخه‌ی ۱۲): در هر بار بازگشت به اپ بررسی می‌شود؛ اگر
    // اعلان‌ها خاموش باشند، یک بنر ساده بالای صفحه می‌آید (فقط
    // اطلاع‌رسانی به کاربر، بدون هیچ گزارشی به ادمین). بستن بنر فقط
    // برای همین اجراست؛ در اجرای بعدی دوباره بررسی می‌شود. بازگشت از
    // تنظیمات هم دوباره بررسی می‌کند تا بنر بی‌درنگ محو شود.
    var notificationsEnabled by remember { mutableStateOf(true) }
    var notifBannerDismissed by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // v13 (یک بار در هر اجرا): اتصال خودکار حساب بازار برای کاربرانی
    // که با موبایل وارد شده‌اند + بررسی نسخه‌ی حداقلی برای اعلان
    // به‌روزرسانی. هر دو بی‌صدا و fail-open هستند؛ هیچ‌کدام کاربر را
    // مسدود نمی‌کند و هیچ پیامکی فرستاده نمی‌شود.
    var showUpdateDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (Session.token != null) {
            try {
                val prefs = context.getSharedPreferences("chehre_prefs", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("bazaar_link_attempted", false)) {
                    BazaarSignIn.getLastSignedInAccount(
                        context,
                        lifecycleOwner,
                        BazaarSignInCallback { response ->
                            prefs.edit().putBoolean("bazaar_link_attempted", true).apply()
                            val account = if (response.isSuccessful) response.data else null
                            if (account != null) {
                                scope.launch {
                                    try {
                                        Api.service.linkBazaar(BazaarLinkRequest(account.accountId))
                                    } catch (_: Exception) {
                                        // ۴۰۹ یا خطای شبکه: دیگر تلاش نمی‌شود.
                                    }
                                }
                            }
                        },
                    )
                }
            } catch (_: Exception) {
            }
        }
        try {
            val cfg = Api.service.appConfig()
            // نسخه‌ی نصب‌شده از PackageManager (به‌جای BuildConfig که
            // کامپایل جاوا می‌خواهد و Worker Daemon آن در این محیط بسته است).
            val installedVersionCode = try {
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode
                }
            } catch (_: Exception) {
                0
            }
            if (cfg.min_app_version_code > installedVersionCode) {
                showUpdateDialog = true
            }
        } catch (_: Exception) {
            // خطا در خواندن تنظیمات یعنی هیچ اعلانی (fail-open).
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationsEnabled = nm.areNotificationsEnabled()
                } catch (_: Exception) {
                    notificationsEnabled = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // جهت راست‌به‌چپ برای کل ترکیب: خانه اول (راست) و پروفایل آخر (چپ).
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    AppTheme {
        if (showSplash) {
            SplashScreen(onSkip = { showSplash = false })
        } else {
        Scaffold(
            bottomBar = {
                if (showNav) {
                    // نوار پایین شناور و گرد، مثل اسکرین‌شات مرجع:
                    // باکس سفید جدا با سایه‌ی ملایم و pill برای تب انتخاب‌شده.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Card(
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                NavTab(Icons.Rounded.Home, "خانه", route == Dest.HOME) { goToTab(Dest.HOME) }
                                NavTab(Icons.Rounded.Search, "جستجو", route == Dest.SEARCH) { goToTab(Dest.SEARCH) }
                                NavTab(Icons.Rounded.PhotoLibrary, "نگارخانه", route == Dest.GALLERY) { GalleryReset.request(); goToTab(Dest.GALLERY) }
                                NavTab(Icons.Rounded.Image, "تصاویر من", route == Dest.MY_MEDIA || route == Dest.WORLDS) { MyMediaReset.request(); goToTab(Dest.MY_MEDIA) }
                                NavTab(Icons.Rounded.Person, "پروفایل", route == Dest.PROFILE) { goToTab(Dest.PROFILE) }
                            }
                        }
                    }
                }
            },
        ) { pad ->
            Box(modifier = Modifier.padding(pad)) {
            NavHost(
                navController = nav,
                startDestination = if (initialGenId > 0) "result/$initialGenId" else Dest.HOME,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(animationSpec = tween(300)) },
                exitTransition = { fadeOut(animationSpec = tween(300)) },
                popEnterTransition = { fadeIn(animationSpec = tween(300)) },
                popExitTransition = { fadeOut(animationSpec = tween(300)) },
            ) {
                composable(Dest.HOME) {
                    HomeScreen(
                        onWorld = { worldId ->
                            nav.navigate("create_image/$worldId")
                        },
                    )
                }
                composable(Dest.SEARCH) {
                    SearchScreen(
                        onWorld = { worldId ->
                            nav.navigate("create_image/$worldId")
                        },
                        onGoHome = { navToTab(Dest.HOME) },
                    )
                }
                composable(Dest.GALLERY) {
                    GalleryScreen(onGoHome = { navToTab(Dest.HOME) })
                }
                composable(Dest.MY_MEDIA) {
                    MyMediaScreen(
                        onCreate = { nav.navigate(Dest.WORLDS) },
                        onWorld = { worldId ->
                            nav.navigate("create_image/$worldId")
                        },
                        onImage = { genId ->
                            nav.navigate("result/$genId")
                        },
                        onLogin = { nav.navigate(Dest.LOGIN) },
                        onGoHome = { navToTab(Dest.HOME) },
                    )
                }
                composable(Dest.PROFILE) {
                    ProfileScreen(
                        onLogin = { nav.navigate(Dest.LOGIN) },
                        onGoHome = { navToTab(Dest.HOME) },
                    )
                }
                composable(Dest.WORLDS) {
                    WorldsScreen(
                        onWorld = { worldId ->
                            nav.navigate("create_image/$worldId")
                        },
                    )
                }
                composable(Dest.CREATE_IMAGE) { entry ->
                    val worldId =
                        entry.arguments?.getString("worldId")?.toLongOrNull() ?: 0L
                    CreateImageScreen(
                        worldId = worldId,
                        onBack = { if (!nav.popBackStack()) nav.navigate(Dest.HOME) },
                        onLogin = { nav.navigate(Dest.LOGIN) },
                        onDone = { genId ->
                            nav.navigate("result/$genId?fresh=true")
                        },
                    )
                }
                composable(
                    Dest.RESULT,
                    arguments = listOf(
                        navArgument("genId") { type = NavType.LongType },
                        navArgument("fresh") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) { entry ->
                    val genId = entry.arguments?.getLong("genId") ?: 0L
                    val fresh = entry.arguments?.getBoolean("fresh") ?: false
                    // سوایپ فقط برای صفحه‌ای که از «تصاویر من» آمده باشد؛
                    // صفحه‌ی تازه‌ساخته‌شده (fresh) و هر مسیر دیگری تک‌عکس‌اند.
                    val swipeIds =
                        if (!fresh && ResultSwipeContext.ids.contains(genId)) ResultSwipeContext.ids else null
                    ResultScreen(
                        genId = genId,
                        fresh = fresh,
                        swipeIds = swipeIds,
                        onBack = {
                            if (!nav.popBackStack()) nav.navigate(Dest.HOME)
                        },
                        onLogin = { nav.navigate(Dest.LOGIN) },
                    )
                }
                composable(Dest.LOGIN) {
                    LoginScreen(onDone = {
                        nav.navigate(Dest.HOME) {
                            popUpTo(nav.graph.startDestinationId) { inclusive = true }
                        }
                    })
                }
            }
            // بنر اعلان (نسخه‌ی ۱۲): فقط وقتی اعلان‌ها خاموش‌اند و در
            // همین اجرا بسته نشده باشد؛ روی صفحه شناور می‌نشیند.
            if (!notificationsEnabled && !notifBannerDismissed) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.NotificationsOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "اعلان‌ها خاموش است",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                            )
                            Text(
                                "وقتی تصویرت آماده شد با اعلان خبرت می‌کنیم؛ برای دریافت اعلان، دسترسی را روشن کن.",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        TextButton(onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        }) {
                            Text("تنظیمات", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { notifBannerDismissed = true }) {
                            Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
            // v13: اعلان نرم به‌روزرسانی — فقط هشدار است؛ «بعداً» کاربر
            // را ادامه می‌دهد و نسخه‌ی قدیمی مسدود نمی‌شود.
            if (showUpdateDialog) {
                AlertDialog(
                    onDismissRequest = { showUpdateDialog = false },
                    title = { Text("نسخه‌ی جدید موجود است", fontWeight = FontWeight.Bold) },
                    text = { Text("نسخه‌ی جدید چهره‌ساز در کافه‌بازار منتشر شده است؛ برای تجربه‌ی بهتر، اپ را به‌روز کن.") },
                    confirmButton = {
                        Button(onClick = {
                            showUpdateDialog = false
                            try {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("bazaar://details?id=${context.packageName}"),
                                    ),
                                )
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://cafebazaar.ir/app/${context.packageName}"),
                                        ),
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        }) {
                            Text("به‌روزرسانی")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("بعداً")
                        }
                    },
                )
            }
            }
        }
        }
    }
    }
}

@Composable
private fun NavTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (selected) Color(0xFFE8EEFF) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) Color(0xFF2E5BFF) else Color.Gray, modifier = Modifier.size(24.dp))
        }
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color(0xFF2E5BFF) else Color.Gray,
        )
    }
}
