package ir.inourai.newapp.ui.screens
import ir.inourai.newapp.ui.theme.themed

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.TicketCreateRequest
import ir.inourai.newapp.data.TicketMessageRequest
import kotlinx.coroutines.launch

// v18 (2): تیکت‌ها — سه صفحه‌ی ساده، هم‌شکل بقیه‌ی صفحه‌ها (همان
// کارت‌ها و تایپوگرافی Samim/Lalezar از طریق MaterialTheme).
// انتخاب‌های طراحی (در گزارش هم آمده): فهرست/جزئیات/جدید صفحه‌های
// جدا هستند، نه باتم‌شیت؛ وضعیت «باز»/«بسته» است؛ تاریخ همان created_at
// سرور است (بدون تبدیل شمسی — قرارداد تیکت created_at_fa ندارد)؛
// پیام‌های کاربر «شما» و پیام‌های ادمین «پشتیبانی» نامیده می‌شوند؛
// تیکت بسته قابل پاسخ نیست و فقط خوانده می‌شود.

@Composable
private fun TicketsHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowForward, contentDescription = null)
        }
        Text(
            title,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
    }
}

private fun ticketStatusFa(status: String): String =
    if (status == "closed") "بسته" else "باز"

/** فهرست تیکت‌ها: موضوع + وضعیت + تاریخ؛ دکمه‌ی «تیکت جدید» بالا. */
@Composable
fun TicketsListScreen(
    onBack: () -> Unit,
    onTicket: (Long) -> Unit,
    onNew: () -> Unit,
) {
    var tickets by remember { mutableStateOf<List<ir.inourai.newapp.data.TicketSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            try {
                tickets = Api.service.tickets()
                loadFailed = false
            } catch (_: Exception) {
                loadFailed = true
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        TicketsHeader("تیکت‌ها", onBack)
        Button(
            onClick = onNew,
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text("تیکت جدید", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            loadFailed -> Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("دریافت تیکت‌ها ناموفق بود؛ دوباره تلاش کن", color = themed(Color.Gray, Color(0xFFAAB4C4)))
            }
            tickets.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "هنوز تیکتی نساخته‌ای؛ اگر مشکلی داری، تیکت جدید بساز.",
                    color = themed(Color.Gray, Color(0xFFAAB4C4)),
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            ) {
                items(tickets) { t ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = themed(Color.White, Color(0xFF1E293B))),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onTicket(t.id) },
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    t.subject,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (t.status == "closed") themed(Color(0xFFF1F3F5), Color(0xFF243247)) else Color(0xFFE6F4EA),
                                            RoundedCornerShape(20.dp),
                                        )
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        ticketStatusFa(t.status),
                                        color = if (t.status == "closed") themed(Color.Gray, Color(0xFFAAB4C4)) else Color(0xFF1B7A3D),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                t.created_at?.take(10) ?: "",
                                color = themed(Color.Gray, Color(0xFFAAB4C4)),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

/** جزئیات تیکت: رشته‌ی پیام‌ها + جعبه‌ی پاسخ (فقط وقتی تیکت باز است). */
@Composable
fun TicketDetailScreen(ticketId: Long, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<ir.inourai.newapp.data.TicketDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            try {
                detail = Api.service.ticket(ticketId)
                loadFailed = false
            } catch (_: Exception) {
                loadFailed = true
            }
            loading = false
        }
    }

    LaunchedEffect(ticketId) { refresh() }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2500)
            notice = null
        }
    }

    fun sendReply() {
        val body = reply.trim()
        if (body.isEmpty() || sending) return
        sending = true
        scope.launch {
            try {
                Api.service.sendTicketMessage(ticketId, TicketMessageRequest(body))
                reply = ""
                refresh()
            } catch (_: Exception) {
                notice = "ارسال پیام ناموفق بود؛ دوباره تلاش کن"
            }
            sending = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TicketsHeader(detail?.subject ?: "تیکت", onBack)
        when {
            loading -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            loadFailed || detail == null -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("دریافت تیکت ناموفق بود؛ دوباره تلاش کن", color = themed(Color.Gray, Color(0xFFAAB4C4)))
            }
            else -> {
                val d = detail!!
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "وضعیت: ${ticketStatusFa(d.status)}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (d.status == "closed") themed(Color.Gray, Color(0xFFAAB4C4)) else themed(Color(0xFF1B7A3D), Color(0xFF6FCE8F)),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    items(d.messages) { m ->
                        val mine = m.sender == "user"
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                        ) {
                            Text(
                                if (mine) "شما" else "پشتیبانی",
                                color = themed(Color.Gray, Color(0xFFAAB4C4)),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (mine) themed(Color(0xFFE8EEFF), Color(0xFF2B4A7A)) else themed(Color.White, Color(0xFF1E293B)),
                                ),
                                shape = RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier.fillMaxWidth(0.85f),
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(m.body, fontSize = 15.sp)
                                    if (m.created_at != null) {
                                        Text(
                                            m.created_at.take(10),
                                            color = themed(Color.Gray, Color(0xFFAAB4C4)),
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
                if (d.status == "closed") {
                    Text(
                        "این تیکت بسته شده است؛ برای ادامه، تیکت جدید بساز.",
                        color = themed(Color.Gray, Color(0xFFAAB4C4)),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = reply,
                            onValueChange = { reply = it },
                            label = { Text("پیامت را بنویس") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            singleLine = false,
                            maxLines = 4,
                        )
                        Spacer(modifier = Modifier.padding(4.dp))
                        Button(
                            onClick = { sendReply() },
                            enabled = !sending && reply.isNotBlank(),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("ارسال", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        notice?.let {
            Text(
                it,
                color = themed(Color(0xFFC0392B), Color(0xFFFF8A80)),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** تیکت جدید: موضوع + پیام؛ بعد از ساخت، به جزئیات همان تیکت می‌رود. */
@Composable
fun NewTicketScreen(onBack: () -> Unit, onCreated: (Long) -> Unit) {
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        TicketsHeader("تیکت جدید", onBack)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("موضوع") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("پیامت را بنویس") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                minLines = 4,
                maxLines = 8,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    if (subject.isBlank() || message.isBlank() || sending) return@Button
                    sending = true
                    scope.launch {
                        try {
                            val created = Api.service.createTicket(
                                TicketCreateRequest(subject.trim(), message.trim()),
                            )
                            onCreated(created.id)
                        } catch (_: Exception) {
                            notice = "ساخت تیکت ناموفق بود؛ دوباره تلاش کن"
                            sending = false
                        }
                    }
                },
                enabled = !sending && subject.isNotBlank() && message.isNotBlank(),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (sending) "در حال ارسال…" else "ارسال تیکت", fontWeight = FontWeight.Bold)
            }
            notice?.let {
                Text(
                    it,
                    color = themed(Color(0xFFC0392B), Color(0xFFFF8A80)),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }
}
