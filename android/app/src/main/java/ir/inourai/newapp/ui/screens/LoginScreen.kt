package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.OtpRequest
import ir.inourai.newapp.data.OtpVerify
import ir.inourai.newapp.data.Session
import kotlinx.coroutines.launch

/** ورود با موبایل — با همان متن‌های شیت اپ مرجع: «شماره موبایلت را وارد
 *  کن؛ با اولین ورود ۱ تصویر هدیه می‌گیری»، «کد ۶ رقمی»، «کد تأیید
 *  ارسال شد.»، «تأیید و ورود». */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ورود با موبایل", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            "شماره موبایلت را وارد کن؛ با اولین ورود ۱ تصویر هدیه می‌گیری.",
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
                                else error = "ارسال کد ناموفق بود"
                            } catch (e: Exception) {
                                error = e.message
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("ارسال کد")
                }
                }
            }
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("کد ۶ رقمی") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
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
                            error = "کد اشتباه است"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("تأیید و ورود")
            }
        }
        error?.let { Text(it) }
    }
}
