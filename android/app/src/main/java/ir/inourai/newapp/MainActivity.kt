package ir.inourai.newapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ir.inourai.newapp.billing.BazaarBilling
import ir.inourai.newapp.data.Session
import ir.inourai.newapp.ui.screens.CreateImageScreen
import ir.inourai.newapp.ui.screens.GalleryScreen
import ir.inourai.newapp.ui.screens.HomeScreen
import ir.inourai.newapp.ui.screens.LoginScreen
import ir.inourai.newapp.ui.screens.MyMediaScreen
import ir.inourai.newapp.ui.screens.ProfileScreen
import ir.inourai.newapp.ui.screens.ResultScreen
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
        setContent { App(initialGenId = genId) }
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
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var showSplash by rememberSaveable { mutableStateOf(true) }
    val showNav = route != Dest.LOGIN && route != Dest.RESULT && !showSplash
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
                                NavTab(Icons.Rounded.Home, "خانه", route == Dest.HOME) { navToTab(Dest.HOME) }
                                NavTab(Icons.Rounded.Search, "جستجو", route == Dest.SEARCH) { navToTab(Dest.SEARCH) }
                                NavTab(Icons.Rounded.PhotoLibrary, "گالری", route == Dest.GALLERY) { navToTab(Dest.GALLERY) }
                                NavTab(Icons.Rounded.Image, "تصاویر من", route == Dest.MY_MEDIA) { navToTab(Dest.MY_MEDIA) }
                                NavTab(Icons.Rounded.Person, "پروفایل", route == Dest.PROFILE) { navToTab(Dest.PROFILE) }
                            }
                        }
                    }
                }
            },
        ) { pad ->
            NavHost(
                navController = nav,
                startDestination = if (initialGenId > 0) "result/$initialGenId" else Dest.HOME,
                modifier = Modifier.padding(pad),
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
                    )
                }
                composable(Dest.GALLERY) {
                    GalleryScreen()
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
                    )
                }
                composable(Dest.PROFILE) {
                    ProfileScreen(onLogin = { nav.navigate(Dest.LOGIN) })
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
                        onBack = { nav.popBackStack() },
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
                    ResultScreen(
                        genId = genId,
                        fresh = fresh,
                        onBack = {
                            if (!nav.popBackStack()) nav.navigate(Dest.HOME)
                        },
                    )
                }
                composable(Dest.LOGIN) {
                    LoginScreen(onDone = { nav.navigate(Dest.HOME) })
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
