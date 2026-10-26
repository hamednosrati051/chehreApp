package ir.inourai.newapp.billing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.IntentSender
import android.os.IBinder
import com.android.vending.billing.IInAppBillingService
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.PurchaseVerifyRequest
import kotlinx.coroutines.suspendCancellableCoroutine
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

object BazaarBilling {
    private const val BAZAAR_PACKAGE = "com.farsitel.bazaar"
    private const val API_VERSION = 3
    private const val TYPE_INAPP = "inapp"

    private suspend fun <T> withService(
        context: Context,
        block: (IInAppBillingService) -> T,
    ): T = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val service = IInAppBillingService.Stub.asInterface(binder)
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
        val intent = Intent("com.android.vending.billing.InAppBillingService.BIND")
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

    /** ساخت Intent خرید برای یک SKU؛ لانچ آن با StartIntentSenderForResult است. */
    suspend fun getBuyIntentSender(context: Context, sku: String): IntentSender {
        return withService(context) { service ->
            @Suppress("DEPRECATION")
            val result = service.getBuyIntent(
                API_VERSION,
                context.packageName,
                sku,
                TYPE_INAPP,
                "",
            )
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
                )
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
     *  (توکن تکراری دوباره شارژ نمی‌شود). */
    suspend fun verifyAndConsume(context: Context, purchase: OwnedPurchase) {
        val packs = Api.service.packs()
        val pack = packs.firstOrNull { it.bazaar_sku == purchase.sku }
            ?: throw RuntimeException("unknown sku: ${purchase.sku}")
        Api.service.verifyPurchase(
            PurchaseVerifyRequest(
                pack_id = pack.id,
                purchase_token = purchase.purchaseToken,
                purchase_data = purchase.purchaseData,
                signature = purchase.signature,
            ),
        )
        consume(context, purchase.purchaseToken)
    }

    /** بازیابی خریدهای ناتمام: همه‌ی ownedها وریفای و consume می‌شوند. */
    suspend fun recoverPurchases(context: Context) {
        for (purchase in getOwnedPurchases(context)) {
            try {
                verifyAndConsume(context, purchase)
            } catch (_: Exception) {
            }
        }
    }
}
