package ir.inourai.newapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.Modifier
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
                    NavigationBar {
                        NavigationBarItem(
                            selected = route == Dest.HOME,
                            onClick = { nav.navigate(Dest.HOME) },
                            icon = { Icon(Icons.Filled.Home, null) },
                            label = { Text("خانه") },
                        )
                        NavigationBarItem(
                            selected = route == Dest.SEARCH,
                            onClick = { nav.navigate(Dest.SEARCH) },
                            icon = { Icon(Icons.Filled.Search, null) },
                            label = { Text("جستجو") },
                        )
                        NavigationBarItem(
                            selected = route == Dest.MY_MEDIA,
                            onClick = { nav.navigate(Dest.MY_MEDIA) },
                            icon = { Icon(Icons.Filled.Image, null) },
                            label = { Text("تصاویر من") },
                        )
                        NavigationBarItem(
                            selected = route == Dest.PROFILE,
                            onClick = { nav.navigate(Dest.PROFILE) },
                            icon = { Icon(Icons.Filled.Person, null) },
                            label = { Text("پروفایل") },
                        )
                    }
                }
            },
        ) { pad ->
            NavHost(
                navController = nav,
                startDestination = if (initialGenId > 0) "result/$initialGenId" else Dest.HOME,
                modifier = Modifier.padding(pad),
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
