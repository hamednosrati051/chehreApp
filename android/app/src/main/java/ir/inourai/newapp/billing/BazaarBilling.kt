package ir.inourai.newapp.billing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.activity.ComponentActivity
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.exception.AbortedException
import ir.cafebazaar.poolakey.exception.BazaarNotFoundException
import ir.cafebazaar.poolakey.exception.BazaarNotSupportedException
import ir.cafebazaar.poolakey.exception.DisconnectException
import ir.cafebazaar.poolakey.exception.DynamicPriceNotSupportedException
import ir.cafebazaar.poolakey.exception.IAPNotSupportedException
import ir.cafebazaar.poolakey.exception.PurchaseHijackedException
import ir.cafebazaar.poolakey.exception.ResultNotOkayException
import ir.cafebazaar.poolakey.exception.SubsNotSupportedException
import ir.cafebazaar.poolakey.request.PurchaseRequest
import ir.inourai.newapp.data.Api
import ir.inourai.newapp.data.CreatePendingPurchaseRequest
import ir.inourai.newapp.data.PurchaseVerifyRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** پرداخت درون‌برنامه‌ای استور (v15 مایکت) — بازار یا مایکت، بر اساس فلِیور بیلد.
 *  هر دو استور همان اینترفیس AIDL گوگل‌پلی (IInAppBillingService) را آینه می‌کنند؛
 *  فقط پکیج استور + اکشن bind فرق می‌کند (از BuildConfig هر فلِیور می‌آید).
 *  هیچ کلید RSA در اپ نیست؛ وریفای خرید سروری است (کلید RSA در env بک‌اند).
 *  ورود با بازار + OTP دست نخورده و در هر دو فلِیور همان است.
 *
 *  چرخه‌ی خرید (پک‌ها consumable هستند):
 *  خرید از استور ← وریفای سروری (RSA + idempotency) ← consume در استور
 *  ← رفرش موجودی. خرید ناتمام (وریفای‌شده ولی consumeنشده، یا برعکس)
 *  موقع شروع اپ با recoverPurchases بازیابی می‌شود.
 *
 *  توجه: وریفای سروری امضای بازار و مایکت هر دو را می‌شناسد (مایکت فقط
 *  وقتی امضای بازار نامعتبر بود، امتحان می‌شود). خود bind و شروع خرید
 *  مایکت از همین حالا درست است.
 */
data class OwnedPurchase(
    val purchaseToken: String,
    val sku: String,
    val purchaseData: String,
    val signature: String,
)

/** مرحله‌ی شکستِ بازشدن پرداخت استور — برای پیام دقیق به کاربر و
 *  تشخیص علت (به‌جای پیام کلی «دوباره تلاش کن»). */
enum class BillingLaunchStage {
    BIND_FAILED, // bindService همان اول false داد (سرویس استور در دسترس نیست)
    BIND_TIMEOUT, // اتصال به سرویس استور بیش از ۱۵ ثانیه طول کشید
    BILLING_UNSUPPORTED, // isBillingSupported کد غیرصفر برگرداند
    BUY_INTENT_FAILED, // getBuyIntent کد غیرصفر برگرداند
    NO_BUY_INTENT, // getBuyIntent باندل یا BUY_INTENT نداشت
    NO_BINDER, // سرویس وصل شد ولی binder نداشت
    DISCONNECTED, // اتصال برقرار به استور وسط کار قطع شد (فقط Poolakey)
}

/** تشخیص‌های دستگاه/نصب که علت شکستِ بازشدن پرداخت را مشخص می‌کنند.
 *  مهم‌ترین‌شان installerPackage است: اگر نصب‌کننده‌ی همین اپ پکیج
 *  استور نباشد (مثلاً سایدلود از لینک مستقیم)، استور به آن پرداخت
 *  نمی‌دهد — حتی اگر اپ استور نصب باشد و ورود با آن کار کند. */
data class BillingDiagnostics(
    val storeId: String, // bazaar / myket (فلِیور همین بیلد)
    val marketPackage: String, // پکیج استور همین فلِیور
    val storeAppInstalled: Boolean, // اپ استور روی گوشی نصب است؟
    val storeAppVersion: String?, // نسخه‌ی اپ استور (اگر نصب است)
    val installerPackage: String?, // نصب‌کننده‌ی همین اپ؛ برابر marketPackage یعنی از استور نصب شده، null یا غیر آن یعنی سایدلود/نامشخص
    val isBillingSupportedCode: Int?,
    val buyIntentCode: Int?,
)

/** خطای typed بازشدن پرداخت: مرحله + کد استور + تشخیص‌ها. پیام
 *  exception خودش همه‌ی تشخیص‌ها را دارد تا در Logcat خوانا باشد. */
class BillingLaunchException(
    val stage: BillingLaunchStage,
    val code: Int? = null,
    val diagnostics: BillingDiagnostics,
    val storeName: String,
) : RuntimeException(
    "billing launch failed stage=$stage code=$code store=${diagnostics.storeId} " +
        "storeAppInstalled=${diagnostics.storeAppInstalled} " +
        "storeAppVersion=${diagnostics.storeAppVersion} " +
        "installer=${diagnostics.installerPackage} " +
        "isBillingSupported=${diagnostics.isBillingSupportedCode} " +
        "buyIntent=${diagnostics.buyIntentCode}",
)

private const val DESCRIPTOR = "com.android.vending.billing.IInAppBillingService"

/** معادل دستیِ Proxy تولیدشده توسط AIDL برای
 *  com.android.vending.billing.IInAppBillingService (بازار و مایکت هر دو
 *  IAB نسخه‌ی ۳ را آینه می‌کنند). کدهای تراکنش دقیقاً به ترتیب اعلان AIDL هستند و
 *  مارشال‌کردن Parcel هم همان الگوی تولیدشده است.
 *
 *  چرا دستی؟ کامپایل فایل Java تولیدشده‌ی AIDL به Gradle Worker Daemon
 *  نیاز دارد که در سندباکس این VM زنده نمی‌ماند؛ با Proxy دستی، پروژه
 *  کاملاً Kotlin می‌شود و آن مرحله حذف می‌شود.
 */
private class InAppBillingProxy(private val remote: IBinder) {
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

private const val API_VERSION = 3
private const val TYPE_INAPP = "inapp"

/** سقف انتظار برای اتصال به سرویس استور؛ بیشتر از این یعنی استور
 *  جواب نمی‌دهد و به‌جای سکوتِ بی‌پایان، خطای قابل‌نمایش برمی‌گردد. */
private const val BIND_TIMEOUT_MS = 15_000L

/** پرداخت یک استور (بازار یا مایکت) با همان AIDL خام؛ فقط پکیج استور
 *  و اکشن bind فرق می‌کند. پک‌ها از سرور می‌آیند (GET /coins/packs) و
 *  SKU مایکت همان مقدار bazaar_sku است — در ادمین چیزی اضافه نمی‌شود. */
open class StoreBilling(
    private val marketPackage: String,
    private val bindAction: String,
) {
    /** نام فارسی استور همین فلِیور برای پیام کاربر. */
    private val storeDisplayName: String
        get() = if (StoreConfig.STORE_ID == "myket") "مایکت" else "بازار"

    /** نصب‌کننده‌ی همین اپ: برابر پکیج استور یعنی از استور نصب شده؛
     *  null یا پکیج دیگر یعنی سایدلود/نامشخص. هرگز خطا نمی‌دهد. */
    private fun installerPackage(context: Context): String? {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** تشخیص‌های دستگاه/نصب را جمع می‌کند؛ هرگز خطا نمی‌دهد (هر
     *  فیلد ناموفق null/false می‌شود تا خطای اصلی گم نشود). */
    private fun collectDiagnostics(
        context: Context,
        isBillingSupportedCode: Int? = null,
        buyIntentCode: Int? = null,
    ): BillingDiagnostics {
        var storeInstalled = false
        var storeVersion: String? = null
        try {
            val info = context.packageManager.getPackageInfo(marketPackage, 0)
            storeInstalled = true
            storeVersion = info.versionName
        } catch (_: Exception) {
            // اپ استور نصب نیست (یا دیده نمی‌شود) — همان هم یک تشخیص است.
        }
        return BillingDiagnostics(
            storeId = StoreConfig.STORE_ID,
            marketPackage = marketPackage,
            storeAppInstalled = storeInstalled,
            storeAppVersion = storeVersion,
            installerPackage = installerPackage(context),
            isBillingSupportedCode = isBillingSupportedCode,
            buyIntentCode = buyIntentCode,
        )
    }

    /** خطای typed بازشدن پرداخت را می‌سازد و کامل در Logcat می‌نویسد
     *  (تگ ChehreSazBilling) تا علت دقیق از روی گوشی خوانا باشد. */
    protected fun billingFailure(
        context: Context,
        stage: BillingLaunchStage,
        code: Int? = null,
        isBillingSupportedCode: Int? = null,
        buyIntentCode: Int? = null,
    ): BillingLaunchException {
        val ex = BillingLaunchException(
            stage = stage,
            code = code,
            diagnostics = collectDiagnostics(context, isBillingSupportedCode, buyIntentCode),
            storeName = storeDisplayName,
        )
        Log.e("ChehreSazBilling", "billing launch failed: $ex", ex)
        return ex
    }

    private suspend fun <T> withService(
        context: Context,
        block: (InAppBillingProxy) -> T,
    ): T {
        try {
            return withTimeout(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            try {
                                val service = binder?.let { InAppBillingProxy(it) }
                                    ?: throw billingFailure(context, BillingLaunchStage.NO_BINDER)
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
                    // اکشن bind مخصوص استور؛ اکشن گوگل‌پلی روی سرویس
                    // استور این گوشی جواب نمی‌دهد و bindService همان اول false می‌دهد.
                    val intent = Intent(bindAction)
                    intent.setPackage(marketPackage)
                    val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                    if (!bound) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                billingFailure(context, BillingLaunchStage.BIND_FAILED),
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
            throw billingFailure(context, BillingLaunchStage.BIND_TIMEOUT)
        }
    }

    /** ساخت Intent خرید برای یک SKU؛ لانچ آن با StartIntentSenderForResult است.
     *  پیش از getBuyIntent، isBillingSupported صدا زده می‌شود (جریان
     *  استاندارد IABv3؛ همان کاری که Poolakey موقع اتصال می‌کند) تا
     *  پشتیبانی‌نشدنِ پرداخت پیش از ساخت Intent مشخص شود و کد واقعی‌اش
     *  در خطا بیاید. هر شکست یک BillingLaunchException typed است. */
    suspend fun getBuyIntentSender(context: Context, sku: String): IntentSender {
        return withService(context) { service ->
            val supported = try {
                service.isBillingSupported(API_VERSION, context.packageName, TYPE_INAPP)
            } catch (e: Exception) {
                throw billingFailure(context, BillingLaunchStage.BILLING_UNSUPPORTED)
            }
            if (supported != 0) {
                throw billingFailure(
                    context,
                    BillingLaunchStage.BILLING_UNSUPPORTED,
                    supported,
                    isBillingSupportedCode = supported,
                )
            }
            val result = service.getBuyIntent(
                API_VERSION,
                context.packageName,
                sku,
                TYPE_INAPP,
                "",
            ) ?: throw billingFailure(context, BillingLaunchStage.NO_BUY_INTENT)
            val code = result.getInt("RESPONSE_CODE")
            if (code != 0) {
                throw billingFailure(
                    context,
                    BillingLaunchStage.BUY_INTENT_FAILED,
                    code,
                    isBillingSupportedCode = supported,
                    buyIntentCode = code,
                )
            }
            @Suppress("DEPRECATION")
            val pendingIntent = result.getParcelable<android.app.PendingIntent>("BUY_INTENT")
            pendingIntent?.intentSender
                ?: throw billingFailure(
                    context,
                    BillingLaunchStage.NO_BUY_INTENT,
                    buyIntentCode = code,
                )
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

    /** خریدهای مصرف‌نشده‌ی استور (برای بازیابی خریدهای ناتمام). */
    open suspend fun getOwnedPurchases(context: Context): List<OwnedPurchase> {
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

    /** مصرف خرید در استور بعد از وریفای موفق سرور. */
    open suspend fun consume(context: Context, purchaseToken: String) {
        withService(context) { service ->
            val code = service.consumePurchase(
                API_VERSION,
                context.packageName,
                purchaseToken,
            )
            if (code != 0) throw RuntimeException("consume failed: $code")
        }
    }

    /** قطع اتصال استور (موقع destroy شدن اپ)؛ پیاده‌سازی پایه هیچ‌کاری نمی‌کند. */
    open fun disconnect() {}

    /** وریفای سروری + consume؛ سرور idempotency را تضمین می‌کند
     *  (توکن تکراری دوباره شارژ نمی‌شود). برمی‌گرداند سکه‌ی هدیه‌ی
     *  اعطاشده در این خرید (v11؛ ۰ اگر هدیه‌ای نبود).
     *  v16: pendingId شناسه‌ی ردیف pending ساخته‌شده موقع tap-buy است؛
     *  null یعنی همان جریان قدیمی بدون pending (وریفای بدون تغییر). */
    suspend fun verifyAndConsume(
        context: Context,
        purchase: OwnedPurchase,
        pendingId: Long? = null,
    ): Int {
        val packs = Api.service.packs()
        val pack = packs.firstOrNull { it.bazaar_sku == purchase.sku }
            ?: throw RuntimeException("unknown sku: ${purchase.sku}")
        val resp = Api.service.verifyPurchase(
            PurchaseVerifyRequest(
                pack_id = pack.id,
                purchase_token = purchase.purchaseToken,
                purchase_data = purchase.purchaseData,
                signature = purchase.signature,
                pending_id = pendingId,
            ),
        )
        consume(context, purchase.purchaseToken)
        return resp.gift_coins
    }

    /** v16: ساخت ردیف pending روی سرور پیش از بازشدن پرداخت استور.
     *  شناسه را برمی‌گرداند؛ هر خطایی (شبکه، سرور) null می‌دهد تا خرید
     *  هرگز روی این تماس مسدود نشود — خریدار به جریان قدیمی می‌افتد. */
    suspend fun createPendingPurchase(packId: Long): Long? {
        return try {
            Api.service.createPendingPurchase(CreatePendingPurchaseRequest(pack_id = packId)).id
        } catch (e: Exception) {
            Log.w("ChehreSazBilling", "createPendingPurchase failed (falling back to legacy flow)", e)
            null
        }
    }

    /** بازیابی خریدهای ناتمام: همه‌ی ownedها وریفای و consume می‌شوند.
     *  تعداد بازیابی‌شده را برمی‌گرداند تا پیام دکمه دروغ نگوید.
     *  v16: شکست دیگر بی‌صدا بلعیده نمی‌شود — هر شکست لاگ می‌شود و
     *  اگر دست‌کم یکی شکست بخورد، بعد از تلاش روی همه، خطا پرتاب
     *  می‌شود تا فراخواننده آن را به کاربر نشان دهد. */
    suspend fun recoverPurchases(context: Context): Int {
        var recovered = 0
        var firstFailure: Exception? = null
        var failures = 0
        for (purchase in getOwnedPurchases(context)) {
            try {
                verifyAndConsume(context, purchase)
                recovered++
            } catch (e: Exception) {
                failures++
                if (firstFailure == null) firstFailure = e
                Log.e("ChehreSazBilling", "recoverPurchases failed for sku=${purchase.sku}", e)
            }
        }
        if (firstFailure != null) {
            throw RuntimeException(
                "بازیابی $failures خرید ناتمام ناموفق بود؛ دوباره تلاش کن",
                firstFailure,
            )
        }
        return recovered
    }
}

/** v17: پیاده‌سازی Poolakey برای فلِیور بازار — جایگزین AIDL دستی.
 *  فقط وقتی STORE_ID == "bazaar" استفاده می‌شود؛ مایکت همان StoreBilling
 *  (AIDL دستی) را دارد. اتصال Poolakey یک‌بار برقرار و نگه داشته می‌شود؛
 *  همه‌ی متدهای suspend پیش از کار، اتصال را تضمین می‌کنند.
 *
 *  بررسی امضای محلی (SecurityCheck) خاموش است — هیچ کلید RSA در اپ نیست؛
 *  وریفای خرید فقط سروری است (کلید RSA در env بک‌اند) و همان مرجع نهایی است.
 *  verifyAndConsume / createPendingPurchase / recoverPurchases از کلاس
 *  پایه به ارث می‌رسند و بدون تغییر همان‌اند؛ فقط ترابری (اتصال، خرید،
 *  query، consume) عوض شده است. */
class PoolakeyStoreBilling(
    marketPackage: String,
    bindAction: String,
) : StoreBilling(marketPackage, bindAction) {
    @Volatile
    private var payment: Payment? = null

    @Volatile
    private var connection: Connection? = null
    private val connectMutex = Mutex()

    /** خطای Poolakey را به مرحله‌ی دقیق نگاشت می‌کند (برای پیام کاربر). */
    private fun mapPoolakeyError(context: Context, t: Throwable): BillingLaunchException {
        val stage = when (t) {
            is BazaarNotFoundException -> BillingLaunchStage.BIND_FAILED
            is BazaarNotSupportedException -> BillingLaunchStage.BILLING_UNSUPPORTED
            is IAPNotSupportedException -> BillingLaunchStage.BILLING_UNSUPPORTED
            is SubsNotSupportedException -> BillingLaunchStage.BILLING_UNSUPPORTED
            is DisconnectException -> BillingLaunchStage.DISCONNECTED
            is ResultNotOkayException -> BillingLaunchStage.BUY_INTENT_FAILED
            is PurchaseHijackedException -> BillingLaunchStage.BUY_INTENT_FAILED
            is DynamicPriceNotSupportedException -> BillingLaunchStage.BUY_INTENT_FAILED
            is AbortedException -> BillingLaunchStage.BUY_INTENT_FAILED
            else -> BillingLaunchStage.BUY_INTENT_FAILED
        }
        val code = if (t is IAPNotSupportedException) 3 else null
        return billingFailure(context, stage, code)
    }

    /** اتصال Poolakey را برقرار می‌کند (اگر برقرار نیست) و Payment را
     *  برمی‌گرداند؛ هر شکست BillingLaunchException است. */
    private suspend fun ensureConnected(context: Context): Payment {
        payment?.let { return it }
        return connectMutex.withLock {
            payment?.let { return@withLock it }
            val pay = Payment(
                context.applicationContext,
                PaymentConfiguration(SecurityCheck.Disable, false),
            )
            val conn = try {
                withTimeout(BIND_TIMEOUT_MS) {
                    // نوع را صریح می‌دهیم تا استنتاج T از resume گیر نکند.
                    suspendCancellableCoroutine<Connection> { cont ->
                        val c = pay.connect {
                            connectionSucceed {
                                if (cont.isActive) cont.resume(this)
                            }
                            connectionFailed { t ->
                                if (cont.isActive) {
                                    cont.resumeWithException(mapPoolakeyError(context, t))
                                }
                            }
                            disconnected {
                                payment = null
                                connection = null
                            }
                        }
                        cont.invokeOnCancellation {
                            try {
                                c.disconnect()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw billingFailure(context, BillingLaunchStage.BIND_TIMEOUT)
            }
            connection = conn
            payment = pay
            pay
        }
    }

    /** خرید یک SKU از راه Poolakey؛ برمی‌گرداند OwnedPurchase، یا null اگر
     *  کاربر پرداخت را لغو کرد. onFlowBegan وقتی صدا زده می‌شود که صفحه‌ی
     *  پرداخت بازار دارد باز می‌شود (همان‌جا ردیف pending ساخته می‌شود — B2).
     *  هر شکست دیگر BillingLaunchException است. */
    suspend fun purchaseProduct(
        context: Context,
        sku: String,
        onFlowBegan: (() -> Unit)? = null,
    ): OwnedPurchase? {
        val pay = ensureConnected(context)
        val registry = (context as? ComponentActivity)?.activityResultRegistry
            ?: throw billingFailure(context, BillingLaunchStage.BIND_FAILED)
        return suspendCancellableCoroutine { cont ->
            // توجه: کالبک‌های Poolakey همه به‌صورت receiver می‌آیند
            // (نه پارامتر)؛ `this` همان PurchaseCallback است.
            pay.purchaseProduct(registry, PurchaseRequest(sku, "", "")) {
                purchaseFlowBegan { onFlowBegan?.invoke() }
                purchaseSucceed { info ->
                    if (cont.isActive) {
                        cont.resume(
                            OwnedPurchase(
                                purchaseToken = info.purchaseToken,
                                sku = info.productId,
                                purchaseData = info.originalJson,
                                signature = info.dataSignature,
                            ),
                        )
                    }
                }
                purchaseCanceled {
                    if (cont.isActive) cont.resume(null)
                }
                purchaseFailed { t ->
                    if (cont.isActive) cont.resumeWithException(mapPoolakeyError(context, t))
                }
                failedToBeginFlow { t ->
                    if (cont.isActive) cont.resumeWithException(mapPoolakeyError(context, t))
                }
            }
        }
    }

    /** خریدهای مصرف‌نشده‌ی استور (برای بازیابی خریدهای ناتمام). */
    override suspend fun getOwnedPurchases(context: Context): List<OwnedPurchase> {
        val pay = ensureConnected(context)
        return suspendCancellableCoroutine { cont ->
            pay.getPurchasedProducts {
                querySucceed { list ->
                    if (cont.isActive) {
                        cont.resume(
                            list.map {
                                OwnedPurchase(
                                    purchaseToken = it.purchaseToken,
                                    sku = it.productId,
                                    purchaseData = it.originalJson,
                                    signature = it.dataSignature,
                                )
                            },
                        )
                    }
                }
                queryFailed { t ->
                    if (cont.isActive) cont.resumeWithException(mapPoolakeyError(context, t))
                }
            }
        }
    }

    /** مصرف خرید در استور بعد از وریفای موفق سرور. */
    override suspend fun consume(context: Context, purchaseToken: String) {
        val pay = ensureConnected(context)
        return suspendCancellableCoroutine { cont ->
            // توجه: consumeProduct برخلاف بقیه‌ی متدهای Poolakey، کالبک را
            // به‌صورت receiver می‌گیرد (نه پارامتر)؛ پس `this` همان
            // ConsumeCallback است و پارامتر صریح نمی‌گیرد.
            pay.consumeProduct(purchaseToken) {
                consumeSucceed {
                    if (cont.isActive) cont.resume(Unit)
                }
                consumeFailed { t ->
                    if (cont.isActive) {
                        cont.resumeWithException(RuntimeException("consume failed: ${t.message}"))
                    }
                }
            }
        }
    }

    /** قطع اتصال Poolakey (موقع destroy شدن اپ). */
    override fun disconnect() {
        try {
            connection?.disconnect()
        } catch (_: Exception) {
        }
        connection = null
        payment = null
    }
}

/** نقطه‌ی ورود پرداخت برای همین بیلد: پکیج استور و اکشن bind از
 *  StoreConfig فلِیور می‌آیند (src/bazaar و src/myket) — فلِیور بازار → بازار،
 *  فلِیور مایکت → مایکت. فلِیور بازار با Poolakey می‌خرد، مایکت با همان
 *  AIDL دستی؛ بقیه‌ی جریان (وریفای سروری، pending، بازیابی) یکی است. */
object Billing {
    private val delegate: StoreBilling =
        if (StoreConfig.STORE_ID == "bazaar") {
            PoolakeyStoreBilling(StoreConfig.MARKET_PACKAGE, StoreConfig.BILLING_BIND_ACTION)
        } else {
            StoreBilling(StoreConfig.MARKET_PACKAGE, StoreConfig.BILLING_BIND_ACTION)
        }

    /** true یعنی همین فلِیور با Poolakey می‌خرد (بازار)؛ مایکت همان AIDL دستی است. */
    val isPoolakey: Boolean get() = delegate is PoolakeyStoreBilling

    suspend fun getBuyIntentSender(context: Context, sku: String): IntentSender =
        delegate.getBuyIntentSender(context, sku)

    fun parsePurchaseResult(data: Intent?): OwnedPurchase? =
        delegate.parsePurchaseResult(data)

    suspend fun getOwnedPurchases(context: Context): List<OwnedPurchase> =
        delegate.getOwnedPurchases(context)

    suspend fun consume(context: Context, purchaseToken: String) =
        delegate.consume(context, purchaseToken)

    suspend fun verifyAndConsume(
        context: Context,
        purchase: OwnedPurchase,
        pendingId: Long? = null,
    ): Int = delegate.verifyAndConsume(context, purchase, pendingId)

    suspend fun createPendingPurchase(packId: Long): Long? =
        delegate.createPendingPurchase(packId)

    suspend fun recoverPurchases(context: Context): Int =
        delegate.recoverPurchases(context)

    /** خرید با Poolakey (فقط فلِیور بازار)؛ null یعنی کاربر لغو کرد.
     *  onFlowBegan وقتی صدا زده می‌شود که صفحه‌ی پرداخت دارد باز می‌شود. */
    suspend fun purchaseProduct(
        context: Context,
        sku: String,
        onFlowBegan: (() -> Unit)? = null,
    ): OwnedPurchase? {
        val poolakey = delegate as? PoolakeyStoreBilling
            ?: throw IllegalStateException("purchaseProduct is Bazaar-only")
        return poolakey.purchaseProduct(context, sku, onFlowBegan)
    }

    /** قطع اتصال استور (موقع destroy شدن اپ)؛ برای مایکت هیچ‌کاری نمی‌کند. */
    fun disconnect() = delegate.disconnect()
}
