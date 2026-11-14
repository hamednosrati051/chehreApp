package ir.inourai.newapp.billing

/** ثابت‌های استور برای فلِیور مایکت — پکیج اپ مایکت + اکشن bind سرویس پرداختش
 *  (دقیقاً مطابق مستندات مایکت: ir.mservices.market.InAppBillingService.BIND).
 *  همتای همین فایل در src/bazaar مقادیر بازار را دارد؛ کد مشترک (Billing)
 *  فقط همین دو مقدار را می‌خواند. BuildConfig ساخته نمی‌شود. */
object StoreConfig {
    const val MARKET_PACKAGE = "ir.mservices.market"
    const val BILLING_BIND_ACTION = "ir.mservices.market.InAppBillingService.BIND"
}
