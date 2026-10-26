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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ir.inourai.newapp.billing.BazaarBilling
import ir.inourai.newapp.data.Session
import ir.inourai.newapp.ui.screens.CreateImageScreen
import ir.inourai.newapp.ui.screens.HomeScreen
import ir.inourai.newapp.ui.screens.LoginScreen
import ir.inourai.newapp.ui.screens.MyMediaScreen
import ir.inourai.newapp.ui.screens.ProfileScreen
import ir.inourai.newapp.ui.screens.SearchScreen
import ir.inourai.newapp.ui.screens.WorldsScreen
import ir.inourai.newapp.ui.theme.AppTheme
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
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        setContent { App() }
    }
}

@Composable
fun App() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showNav = route != Dest.LOGIN
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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

    AppTheme {
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
                startDestination = Dest.HOME,
                modifier = Modifier.padding(pad),
            ) {
                composable(Dest.HOME) {
                    HomeScreen(
                        onPhoto = { nav.navigate(Dest.WORLDS) },
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
                composable(Dest.MY_MEDIA) { MyMediaScreen() }
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
                    CreateImageScreen(worldId = worldId)
                }
                composable(Dest.LOGIN) {
                    LoginScreen(onDone = { nav.navigate(Dest.HOME) })
                }
            }
        }
    }
}
