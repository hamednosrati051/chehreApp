package ir.inourai.newapp.billing

/** ثابت‌های استور برای فلِیور بازار — پکیج اپ بازار + اکشن bind سرویس پرداختش.
 *  همتای همین فایل در src/myket مقادیر مایکت را دارد؛ کد مشترک (Billing)
 *  فقط همین دو مقدار را می‌خواند. BuildConfig ساخته نمی‌شود. */
object StoreConfig {
    const val MARKET_PACKAGE = "com.farsitel.bazaar"
    const val BILLING_BIND_ACTION = "ir.cafebazaar.pardakht.InAppBillingService.BIND"
}
