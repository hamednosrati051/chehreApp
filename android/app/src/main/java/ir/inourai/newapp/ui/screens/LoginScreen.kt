package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.OtpRequest
import ir.inourai.newapp.data.OtpVerify
import ir.inourai.newapp.data.Session
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onDone: () -> Unit) {
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = mobile,
            onValueChange = { mobile = it },
            label = { Text("شماره موبایل") },
        )
        if (!sent) {
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
            ) {
                Text("ارسال کد")
            }
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("کد تایید") },
            )
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val res = Api.service.otpVerify(OtpVerify(mobile, code))
                            Session.token = res.token
                            onDone()
                        } catch (e: Exception) {
                            error = "کد اشتباه است"
                        }
                    }
                },
            ) {
                Text("ورود")
            }
        }
        error?.let { Text(it) }
    }
}
