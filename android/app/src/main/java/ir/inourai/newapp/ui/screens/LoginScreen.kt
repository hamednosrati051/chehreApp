package ir.inourai.newapp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.farsitel.bazaar.BazaarClientProxy
import com.farsitel.bazaar.core.BazaarSignIn
import com.farsitel.bazaar.core.model.BazaarSignInOptions
import com.farsitel.bazaar.core.model.SignInOption
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.BazaarLoginRequest
import ir.inourai.newapp.data.OtpRequest
import ir.inourai.newapp.data.OtpVerify
import ir.inourai.newapp.data.Session
import java.io.IOException
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** ورود با موبایل — با همان متن‌های شیت اپ مرجع: «شماره موبایلت را وارد
 *  کن؛ با اولین ورود ۱ تصویر هدیه می‌گیری»، «کد ۶ رقمی»، «کد تأیید
 *  ارسال شد.»، «تأیید و ورود». */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    // v13: Bazaar login اول است؛ OTP فقط وقتی نشان داده می‌شود که
    // کاربر «ورود با موبایل» را بزند یا ورود با بازار ناموفق باشد.
    var showOtp by remember { mutableStateOf(false) }
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    // خطا در اسنک‌بار پایین صفحه نشان داده می‌شود (متن کوتاه فارسی،
    // هرگز متن خام)؛ seq کاری می‌کند که دو خطای یکسان پشت سر هم هم
    // هر دو نمایش داده شوند.
    var error by remember { mutableStateOf<String?>(null) }
    var snackSeq by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    fun showError(msg: String) {
        error = msg
        snackSeq++
    }
    LaunchedEffect(snackSeq) {
        val msg = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
        error = null
    }
    val scope = rememberCoroutineScope()
    // v13: ورود با بازار (native in-app flow) — accountId تنها چیزی
    // است که بازار برمی‌گرداند؛ با همان به سرور لاگین می‌کنیم. هر
    // شکستی (بازار نصب نیست، کاربر رد کرد، شبکه) بی‌صدا به OTP می‌رود.
    val bazaarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val account = try {
            result.data?.let { BazaarSignIn.getSignedInAccountFromIntent(it) }
        } catch (_: Exception) {
            null
        }
        if (account == null) {
            showOtp = true
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val res = Api.service.bazaarLogin(BazaarLoginRequest(account.accountId))
                Session.token = res.token
                Session.justRegistered = res.is_new == true
                onDone()
            } catch (_: Exception) {
                showOtp = true
            }
        }
    }
    fun startBazaarSignIn() {
        try {
            if (!BazaarClientProxy.isBazaarInstalledOnDevice(context)) {
                showOtp = true
                return
            }
            val client = BazaarSignIn.getClient(
                context,
                BazaarSignInOptions.Builder(SignInOption.DEFAULT_SIGN_IN).build(),
            )
            bazaarLauncher.launch(client.getSignInIntent())
        } catch (_: Exception) {
            showOtp = true
        }
    }
    // وعده‌ی هدیه فقط وقتی تنظیم روشن است؛ خطا در خواندن یعنی متن فعلی می‌ماند.
    var giftOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try {
            val v = Api.service.publicSettings().entry_gift_enabled
            giftOn = v != "0" && !v.equals("false", ignoreCase = true)
        } catch (_: Exception) { }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    if (!showOtp) {
        // v13: انتخاب روش ورود — دکمه‌ی بازار بزرگ و سبز و اول،
        // «ورود با موبایل» کوچک و ساده زیر آن.
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ورود", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "با حساب کافه‌بازارت وارد شو؛ سریع و امن.",
                color = Color.Gray,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // v14: متن دکمه‌ی بازار — هدیه‌ی ورودی + «یک لمس، بدون پیامک».
            // هدیه دست نخورده است؛ فقط جمله‌ی روی دکمه عوض شده.
            Button(
                onClick = { startBazaarSignIn() },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0EA960),
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("با بازار وارد شو، ۱ عکس رایگان اضافه بگیر", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("یک لمس، بدون پیامک", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
                }
            }
            TextButton(
                onClick = { showOtp = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("ورود با موبایل", fontSize = 14.sp)
            }
        }
    } else {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ورود با موبایل", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            if (giftOn) "شماره موبایلت را وارد کن؛ با اولین ورود ۱ تصویر هدیه می‌گیری."
            else "شماره موبایلت را وارد کن.",
            color = Color.Gray,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = mobile,
            onValueChange = { mobile = it },
            label = { Text("شماره موبایل") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = Color(0xFF1D6FE0),
                unfocusedBorderColor = Color(0xFFD4DCE8),
            ),
        )
        if (!sent) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val resp = Api.service.otpRequest(OtpRequest(mobile))
                                if (resp.isSuccessful) sent = true
                                else showError(
                                    when (resp.code()) {
                                        429 -> "تعداد درخواست زیاد است؛ کمی بعد دوباره تلاش کن"
                                        400 -> "شماره موبایل معتبر نیست"
                                        else -> "ارسال کد ناموفق بود"
                                    }
                                )
                            } catch (e: Exception) {
                                showError(
                                    if (e is IOException) "مشکل شبکه؛ دوباره تلاش کن"
                                    else "ارسال کد ناموفق بود"
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("ارسال کد")
                }
            }
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("کد ۶ رقمی") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = Color(0xFF1D6FE0),
                    unfocusedBorderColor = Color(0xFFD4DCE8),
                ),
            )
            Text("کد ۶ رقمی پیامک‌شده را وارد کن.", color = Color.Gray, fontSize = 13.sp)
            Text(
                "کد تأیید ارسال شد.",
                color = Color(0xFF1B7A3D),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Start),
            )
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val res = Api.service.otpVerify(OtpVerify(mobile, code))
                            Session.token = res.token
                            Session.justRegistered = res.is_new == true
                            onDone()
                        } catch (e: Exception) {
                            // سرور برای «کد نیست»، «تلاش‌ها تمام شده»،
                            // «منقضی» و «اشتباه» همه ۴۰۱ می‌دهد؛ پس
                            // پیام، هر دو حالت محتمل را پوشش می‌دهد.
                            showError(
                                when {
                                    e is HttpException && e.code() == 429 ->
                                        "تعداد درخواست زیاد است؛ کمی بعد دوباره تلاش کن"
                                    e is HttpException && e.code() == 401 ->
                                        "کد اشتباه است یا منقضی شده است"
                                    e is IOException ->
                                        "مشکل شبکه؛ دوباره تلاش کن"
                                    else -> "کد اشتباه است"
                                }
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("تأیید و ورود")
            }
        }
    }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
    )
    }
}
