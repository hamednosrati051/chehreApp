package ir.inourai.newapp.billing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.PurchaseVerifyRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** پرداخت بازار (IAB) — فقط با SKUها و کلید RSA خود اپ.
 *  هیچ کلیدی از اپ نورا استفاده نمی‌شود.
 *
 *  چرخه‌ی خرید (پک‌ها consumable هستند):
 *  خرید از بازار ← وریفای سروری (RSA + idempotency) ← consume در بازار
 *  ← رفرش موجودی. خرید ناتمام (وریفای‌شده ولی consumeنشده، یا برعکس)
 *  موقع شروع اپ با recoverPurchases بازیابی می‌شود.
 */
data class OwnedPurchase(
    val purchaseToken: String,
    val sku: String,
    val purchaseData: String,
    val signature: String,
)

private const val DESCRIPTOR = "com.android.vending.billing.IInAppBillingService"

/** معادل دستیِ Proxy تولیدشده توسط AIDL برای
 *  com.android.vending.billing.IInAppBillingService (بازار IAB نسخه‌ی ۳ را
 *  آینه می‌کند). کدهای تراکنش دقیقاً به ترتیب اعلان AIDL هستند و
 *  مارشال‌کردن Parcel هم همان الگوی تولیدشده است.
 *
 *  چرا دستی؟ کامپایل فایل Java تولیدشده‌ی AIDL به Gradle Worker Daemon
 *  نیاز دارد که در سندباکس این VM زنده نمی‌ماند؛ با Proxy دستی، پروژه
 *  کاملاً Kotlin می‌شود و آن مرحله حذف می‌شود.
 */
private class BazaarServiceProxy(private val remote: IBinder) {
    private fun transact(code: Int, data: Parcel, reply: Parcel) {
        remote.transact(code, data, reply, 0)
        reply.readException()
    }

    private fun Parcel.writeBundleOrNull(bundle: Bundle?) {
        if (bundle != null) {
            writeInt(1)
            bundle.writeToParcel(this, 0)
        } else {
            writeInt(0)
        }
    }

    private fun Parcel.readBundleOrNull(): Bundle? {
        return if (readInt() != 0) Bundle.CREATOR.createFromParcel(this) else null
    }

    fun isBillingSupported(apiVersion: Int, packageName: String, type: String): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(apiVersion)
            data.writeString(packageName)
            data.writeString(type)
            transact(1, data, reply)
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun getSkuDetails(
        apiVersion: Int,
        packageName: String,
        type: String,
        skusBundle: Bundle?,
    ): Bundle? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(apiVersion)
            data.writeString(packageName)
            data.writeString(type)
            data.writeBundleOrNull(skusBundle)
            transact(2, data, reply)
            return reply.readBundleOrNull()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun getBuyIntent(
        apiVersion: Int,
        packageName: String,
        sku: String,
        type: String,
        developerPayload: String,
    ): Bundle? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(apiVersion)
            data.writeString(packageName)
            data.writeString(sku)
            data.writeString(type)
            data.writeString(developerPayload)
            transact(3, data, reply)
            return reply.readBundleOrNull()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun getPurchases(
        apiVersion: Int,
        packageName: String,
        type: String,
        continuationToken: String?,
    ): Bundle? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(apiVersion)
            data.writeString(packageName)
            data.writeString(type)
            data.writeString(continuationToken)
            transact(4, data, reply)
            return reply.readBundleOrNull()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun consumePurchase(apiVersion: Int, packageName: String, purchaseToken: String): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(apiVersion)
            data.writeString(packageName)
            data.writeString(purchaseToken)
            transact(5, data, reply)
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun getBuyIntentToReplaceSkus(
        apiVersion: Int,
        packageName: String,
        oldSkus: List<String>,
        newSku: String,
        type: String,
        developerPayload: String,
    ): Bundle? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(apiVersion)
            data.writeString(packageName)
            data.writeStringList(oldSkus)
            data.writeString(newSku)
            data.writeString(type)
            data.writeString(developerPayload)
            transact(6, data, reply)
            return reply.readBundleOrNull()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun getPurchaseHistory(
        apiVersion: Int,
        packageName: String,
        type: String,
        continuationToken: String?,
        extraParams: Bundle?,
    ): Bundle? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(apiVersion)
            data.writeString(packageName)
            data.writeString(type)
            data.writeString(continuationToken)
            data.writeBundleOrNull(extraParams)
            transact(7, data, reply)
            return reply.readBundleOrNull()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

object BazaarBilling {
    private const val BAZAAR_PACKAGE = "com.farsitel.bazaar"
    private const val API_VERSION = 3
    private const val TYPE_INAPP = "inapp"

    /** سقف انتظار برای اتصال به سرویس بازار؛ بیشتر از این یعنی بازار
     *  جواب نمی‌دهد و به‌جای سکوتِ بی‌پایان، خطای قابل‌نمایش برمی‌گردد. */
    private const val BIND_TIMEOUT_MS = 15_000L

    private suspend fun <T> withService(
        context: Context,
        block: (BazaarServiceProxy) -> T,
    ): T {
        try {
            return withTimeout(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val service = binder?.let { BazaarServiceProxy(it) }
                        ?: throw IllegalStateException("no binder from bazaar")
                    val result = block(service)
                    if (cont.isActive) cont.resume(result)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                } finally {
                    try {
                        context.unbindService(this)
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        // اکشن مخصوص خود بازار (مثل نورا)؛ اکشن گوگل‌پلی روی سرویس
        // بازار این گوشی جواب نمی‌دهد و bindService همان اول false می‌دهد.
        val intent = Intent("ir.cafebazaar.pardakht.InAppBillingService.BIND")
        intent.setPackage(BAZAAR_PACKAGE)
        val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!bound) {
            if (cont.isActive) {
                cont.resumeWithException(
                    IllegalStateException("bazaar billing service not available"),
                )
            }
        }
        cont.invokeOnCancellation {
            try {
                context.unbindService(conn)
            } catch (_: Exception) {
            }
        }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("bazaar service timeout")
        }
    }

    /** ساخت Intent خرید برای یک SKU؛ لانچ آن با StartIntentSenderForResult است. */
    suspend fun getBuyIntentSender(context: Context, sku: String): IntentSender {
        return withService(context) { service ->
            val result = service.getBuyIntent(
                API_VERSION,
                context.packageName,
                sku,
                TYPE_INAPP,
                "",
            ) ?: throw RuntimeException("no buy intent from bazaar")
            if (result.getInt("RESPONSE_CODE") != 0) {
                throw RuntimeException(
                    "bazaar buy intent failed: ${result.getInt("RESPONSE_CODE")}",
                )
            }
            @Suppress("DEPRECATION")
            val pendingIntent = result.getParcelable<android.app.PendingIntent>("BUY_INTENT")
            pendingIntent?.intentSender
                ?: throw RuntimeException("no buy intent from bazaar")
        }
    }

    /** نتیجه‌ی Intent خرید را به خرید owned تبدیل می‌کند (یا null اگر لغو شده). */
    fun parsePurchaseResult(data: Intent?): OwnedPurchase? {
        if (data == null) return null
        if (data.getIntExtra("RESPONSE_CODE", 1) != 0) return null
        val purchaseData = data.getStringExtra("INAPP_PURCHASE_DATA") ?: return null
        val signature = data.getStringExtra("INAPP_DATA_SIGNATURE") ?: return null
        val json = JSONObject(purchaseData)
        return OwnedPurchase(
            purchaseToken = json.getString("purchaseToken"),
            sku = json.getString("productId"),
            purchaseData = purchaseData,
            signature = signature,
        )
    }

    /** خریدهای مصرف‌نشده‌ی بازار (برای بازیابی خریدهای ناتمام). */
    suspend fun getOwnedPurchases(context: Context): List<OwnedPurchase> {
        return withService(context) { service ->
            val out = mutableListOf<OwnedPurchase>()
            var continuation: String? = null
            do {
                val bundle = service.getPurchases(
                    API_VERSION,
                    context.packageName,
                    TYPE_INAPP,
                    continuation,
                ) ?: break
                if (bundle.getInt("RESPONSE_CODE") != 0) break
                val datas =
                    bundle.getStringArrayList("INAPP_PURCHASE_DATA_LIST") ?: arrayListOf()
                val sigs =
                    bundle.getStringArrayList("INAPP_DATA_SIGNATURE_LIST") ?: arrayListOf()
                for (i in datas.indices) {
                    val json = JSONObject(datas[i])
                    out.add(
                        OwnedPurchase(
                            purchaseToken = json.getString("purchaseToken"),
                            sku = json.getString("productId"),
                            purchaseData = datas[i],
                            signature = sigs.getOrNull(i) ?: "",
                        ),
                    )
                }
                continuation = bundle.getString("INAPP_CONTINUATION_TOKEN")
            } while (continuation != null)
            out
        }
    }

    /** مصرف خرید در بازار بعد از وریفای موفق سرور. */
    suspend fun consume(context: Context, purchaseToken: String) {
        withService(context) { service ->
            val code = service.consumePurchase(
                API_VERSION,
                context.packageName,
                purchaseToken,
            )
            if (code != 0) throw RuntimeException("consume failed: $code")
        }
    }

    /** وریفای سروری + consume؛ سرور idempotency را تضمین می‌کند
     *  (توکن تکراری دوباره شارژ نمی‌شود). برمی‌گرداند سکه‌ی هدیه‌ی
     *  اعطاشده در این خرید (v11؛ ۰ اگر هدیه‌ای نبود). */
    suspend fun verifyAndConsume(context: Context, purchase: OwnedPurchase): Int {
        val packs = Api.service.packs()
        val pack = packs.firstOrNull { it.bazaar_sku == purchase.sku }
            ?: throw RuntimeException("unknown sku: ${purchase.sku}")
        val resp = Api.service.verifyPurchase(
            PurchaseVerifyRequest(
                pack_id = pack.id,
                purchase_token = purchase.purchaseToken,
                purchase_data = purchase.purchaseData,
                signature = purchase.signature,
            ),
        )
        consume(context, purchase.purchaseToken)
        return resp.gift_coins
    }

    /** بازیابی خریدهای ناتمام: همه‌ی ownedها وریفای و consume می‌شوند.
     *  تعداد بازیابی‌شده را برمی‌گرداند تا پیام دکمه دروغ نگوید. */
    suspend fun recoverPurchases(context: Context): Int {
        var recovered = 0
        for (purchase in getOwnedPurchases(context)) {
            try {
                verifyAndConsume(context, purchase)
                recovered++
            } catch (_: Exception) {
            }
        }
        return recovered
    }
}
