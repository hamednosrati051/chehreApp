package com.android.vending.billing;

// رابط AIDL پرداخت درون‌برنامه‌ای بازار (مشابه IAB نسخه‌ی ۳).
// ترتیب متدها عمداً همان ترتیب استاندارد است تا کدهای تراکنش با سرویس بازار بخواند.
interface IInAppBillingService {
    int isBillingSupported(int apiVersion, String packageName, String type);
    Bundle getSkuDetails(int apiVersion, String packageName, String type, in Bundle skusBundle);
    Bundle getBuyIntent(int apiVersion, String packageName, String sku, String type, String developerPayload);
    Bundle getPurchases(int apiVersion, String packageName, String type, String continuationToken);
    int consumePurchase(int apiVersion, String packageName, String purchaseToken);
    Bundle getBuyIntentToReplaceSkus(int apiVersion, String packageName, in List<String> oldSkus, String newSku, String type, String developerPayload);
    Bundle getPurchaseHistory(int apiVersion, String packageName, String type, String continuationToken, in Bundle extraParams);
}
