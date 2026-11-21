package ir.inourai.newapp.data

import com.google.gson.GsonBuilder
import ir.inourai.newapp.data.Session.token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

// TODO: با آدرس واقعی بک‌اند خود اپ عوض شود.
private const val BASE_URL = "https://chehre.inourai.ir/api/v1/"

data class OtpRequest(val mobile: String)
// v16: store اختیاری است (bazaar/myket)؛ نبودنش یعنی رفتار قدیمی.
data class OtpVerify(val mobile: String, val code: String, val store: String? = null)
data class TokenResponse(val token: String, val is_new: Boolean? = null)
// v13: Bazaar native login — server creates/links a user by Bazaar accountId.
data class BazaarLoginRequest(val account_id: String, val store: String? = null)
data class BazaarLinkRequest(val account_id: String, val store: String? = null)
data class BazaarLinkResponse(val ok: Boolean)
data class AppConfig(val min_app_version_code: Int = 0)
data class MeResponse(val mobile_masked: String)
data class FavoriteRequest(val world_id: Long)
data class Category(val id: Long, val name: String, val slug: String)
data class World(
    val id: Long,
    val name: String,
    val slug: String,
    val category_id: Long?,
    val sample_image_url: String?,
    val coin_price: Int,
    val is_featured: Boolean,
    val is_instagram_trend: Boolean = false,
    val is_iranian_trend: Boolean = false,
    val is_sport_trend: Boolean = false,
    val is_seasonal_trend: Boolean = false,
    // دخالت ادمین در «تازه‌های هفته» (نسخه‌ی ۱۰، افزایشی): نبودن
    // فیلد در JSON یعنی خودکار؛ اپ‌های قدیمی این دو را نمی‌شناسند.
    val is_fresh_pinned: Boolean = false,
    val is_fresh_hidden: Boolean = false,
    val created_at: String? = null,
)
data class Trend(
    val id: Long,
    val name: String,
    val key: String?,
    val sort_order: Int,
    val display_type: String,
    // nullable دفاعی: اگر بک‌اند برای ترندی world_ids نفرستد، خالی حساب می‌شود.
    val world_ids: List<Long>? = null,
    // حلقه‌ی ترندهای دایره‌ای (نسخه‌ی ۱۰، افزایشی): null یعنی پیش‌فرض
    // روشن؛ چون Gson فیلدِ غایبِ Boolean غیر-null را false می‌کند،
    // nullable گرفته شده تا نبودن فیلد، حلقه را خاموش نکند.
    val show_ring: Boolean? = null,
)
data class GenerationRequest(
    val kind: String,
    val world_id: Long? = null,
    val input_url: String? = null,
)
data class GenerationCreated(val id: Long, val status: String)
data class GenerationStatus(
    val id: Long,
    val kind: String,
    val status: String,
    val output_url: String?,
    val charged: Boolean? = null,
    val error: String? = null,
    val world_id: Long? = null,
    val world_name: String? = null,
    // نسخه‌ی ۱۰، افزایشی: ورودی خود کاربر (قبل/بعد) و تاریخ شمسی زیرنویس.
    val input_url: String? = null,
    val created_at_fa: String? = null,
    // v13: رایگان (هدیه) یا پولی؛ نبودن فیلد یعنی پاسخ قدیمی (پولی فرض نمی‌شود،
    // فقط اعلان نشان داده نمی‌شود مگر ResultGiftContext).
    val is_free: Boolean = false,
    // v14: در نگارخانه هست یا نه (هر وضعیتی)؛ اعلان ۲۴ ساعته فقط وقتی
    // نشان داده می‌شود که رایگان باشد و در نگارخانه نباشد.
    val in_gallery: Boolean = false,
)
data class Balance(
    val coins: Int,
    val free_image: Int,
)
data class Pack(
    val id: Long,
    val name: String,
    val coins: Int,
    val price_toman: Int,
    val bazaar_sku: String,
    val badge: String?,
    val pack_type: String?,
    val sort_order: Int,
    // v11 هدیه‌ی خرید (پیش‌فرض ۰ یعنی پاسخ قدیمی بدون این فیلدها هم پارس می‌شود).
    val gift_coins: Int = 0,
    val gift_scope: String? = null,
    // v17 زیبال: هر پک دو دکمه دارد (بازار + زیبال)؛ پیش‌فرض زیبال خاموش است.
    val bazaar_enabled: Boolean = true,
    val zibal_enabled: Boolean = false,
    // v17 فالبک یک‌باره: اگر روشن باشد، بعد از خطای واقعی یک درگاه،
    // دکمه‌ی درگاه دیگر یک بار نشان داده می‌شود (حتی با پرچم خاموش).
    val show_fallback_button: Boolean = false,
    // v18 (10): قیمت اصلی (نمایشی)؛ null یعنی تخفیفی نیست و فقط
    // قیمت پرداختی نشان داده می‌شود.
    val original_price_toman: Int? = null,
)
data class PurchaseVerifyRequest(
    val pack_id: Long,
    val purchase_token: String,
    val purchase_data: String,
    val signature: String,
    // v16: شناسه‌ی ردیف pending ساخته‌شده موقع tap-buy؛ اختیاری است.
    val pending_id: Long? = null,
)
// v16: ساخت ردیف pending موقع tap-buy (قبل از بازشدن پرداخت استور)؛
// purchase_token هنوز نیست و موقع وریفای وصل می‌شود.
data class CreatePendingPurchaseRequest(val pack_id: Long)
data class PendingPurchaseCreated(val id: Long, val status: String)
// v17 زیبال: ساخت پرداخت + بررسی دوباره (recheck).
// fallback=true یعنی دکمه‌ی جایگزین یک‌باره بعد از خطای بازار است؛
// سرور فقط وقتی تنظیم show_fallback_button همان پک روشن باشد، با
// پرچم خاموش هم می‌سازد.
data class ZibalPurchaseRequest(val pack_id: Long, val fallback: Boolean = false)
data class ZibalPurchaseCreated(val purchase_id: Long, val payment_url: String, val status: String)
data class ZibalRecheckResponse(
    val id: Long,
    val status: String,
    val gift_coins: Int = 0,
    val already_credited: Boolean = false,
    val balance: Int? = null,
)
// v16: گزارش کاربر روی یک تصویر تولیدشده (UGC، قانون مایکت)؛
// reason اختیاری است و خالی هم قبول است.
data class GalleryReportRequest(val generation_id: Long, val reason: String? = null)
data class GalleryReportResponse(val id: Long, val status: String, val duplicate: Boolean = false)
data class PurchaseVerifyResponse(
    val id: Long,
    val status: String,
    val coins: Int,
    val already_credited: Boolean,
    val balance: Int,
    // v11: سکه‌ی هدیه‌ی اعطاشده در این خرید (۰ اگر هدیه‌ای نبود).
    val gift_coins: Int = 0,
)
data class PendingPurchase(
    val id: Long,
    val pack_id: Long,
    val amount_irr: Long,
    val purchase_token: String?,
    // v17 زیبال: درگاه + trackId (برای recheck موقع برگشت از مرورگر).
    val gateway: String? = null,
    val zibal_track_id: String? = null,
)

data class PaymentItem(
    val id: Long,
    val pack_name: String,
    val coins: Int,
    val amount_irr: Long,
    val status: String,
    val created_at: String?,
    val created_at_fa: String? = null,
)
data class ImageLikeRequest(val generation_id: Long)
data class LikedGeneration(
    val id: Long,
    val output_url: String?,
    val world_name: String?,
    val world_id: Long?,
    val created_at: String?,
    val status: String?,
    // نسخه‌ی ۱۰، افزایشی: تاریخ شمسی زیرنویس.
    val created_at_fa: String? = null,
    // v13: رایگان (هدیه) یا پولی (برای اعلان ۲۴ ساعته).
    val is_free: Boolean = false,
)
data class GallerySubmitRequest(val generation_id: Long)
data class GallerySubmitResponse(val id: Long, val status: String)
data class GalleryItem(
    val id: Long,
    val image_url: String,
    val generation_id: Long? = null,
    // v18 (6+7): /gallery/public این‌ها را می‌فرستد؛ نبودن‌شان یعنی
    // پاسخ قدیمی (پولی فرض می‌شود و نامی نشان داده نمی‌شود).
    val is_free: Boolean = false,
    val world_name: String? = null,
    val world_id: Long? = null,
)
// v18 (2): تیکت‌ها — قرارداد WORKLIST.md؛ وضعیت‌ها "open"/"closed".
data class TicketCreateRequest(val subject: String, val message: String)
data class TicketCreated(val id: Long, val status: String? = null)
data class TicketSummary(
    val id: Long,
    val subject: String,
    val status: String,
    val created_at: String? = null,
)
data class TicketMessage(
    val id: Long,
    val sender: String,
    val body: String,
    val created_at: String? = null,
)
data class TicketDetail(
    val id: Long,
    val subject: String,
    val status: String,
    val messages: List<TicketMessage> = emptyList(),
)
data class TicketMessageRequest(val body: String)
// v18 (4): بازسازی با کیفیت بالا — قیمتِ روز دنیا برمی‌گردد.
data class RegenerateResponse(
    val generation_id: Long = 0,
    val world_id: Long = 0,
    val input_url: String? = null,
    val coin_price: Int = 0,
)
data class DeviceTokenRequest(val token: String, val platform: String = "android")
data class Announcement(val id: Long, val title: String, val body: String, val created_at: String? = null)
data class PublicSettings(
    val about_us: String = "",
    val support_info: String = "",
    val bug_report_info: String = "",
    val bazaar_url: String = "",
    // v17 (S2): لینک مایکت برای فلِیور مایکت؛ خالی یعنی لینک خودکار.
    val myket_url: String = "",
    val rules_privacy: String = "",
    val entry_gift_enabled: String = "1",
    // v18 (9): پرومو مودال — عنوان/متن/شماره‌ی نمایش/روشن‌بودن از
    // تنظیمات عمومی. رشته‌ای گرفته شده‌اند تا «1»/عدد/true هر سه پارس
    // شوند (Gson مقدار رشته‌ای را به Int/Boolean تبدیل نمی‌کند)؛
    // null یعنی کلید هنوز در پاسخ نیست.
    val promo_modal_title: String? = null,
    val promo_modal_body: String? = null,
    val promo_modal_show_number: String? = null,
    val promo_modal_enabled: String? = null,
)

interface ApiService {
    @POST("auth/otp/request")
    suspend fun otpRequest(@Body b: OtpRequest): Response<Unit>

    @POST("auth/otp/verify")
    suspend fun otpVerify(@Body b: OtpVerify): TokenResponse

    // v13: Bazaar native login + link + soft update-prompt config.
    @POST("auth/bazaar")
    suspend fun bazaarLogin(@Body b: BazaarLoginRequest): TokenResponse

    @POST("auth/bazaar/link")
    suspend fun linkBazaar(@Body b: BazaarLinkRequest): BazaarLinkResponse

    @GET("app-config")
    suspend fun appConfig(): AppConfig

    @GET("auth/me")
    suspend fun me(): MeResponse

    @GET("categories")
    suspend fun categories(): List<Category>

    @GET("worlds")
    suspend fun worlds(): List<World>

    @GET("worlds/{id}")
    suspend fun world(@Path("id") id: Long): World

    @GET("trends")
    suspend fun trends(): List<Trend>

    @GET("favorites")
    suspend fun favorites(): List<World>

    @POST("favorites")
    suspend fun addFavorite(@Body b: FavoriteRequest): Response<Unit>

    @DELETE("favorites/{worldId}")
    suspend fun removeFavorite(@Path("worldId") worldId: Long): Response<Unit>

    @GET("image-likes")
    suspend fun imageLikes(): List<LikedGeneration>

    @POST("image-likes")
    suspend fun likeImage(@Body b: ImageLikeRequest): Response<Unit>

    @DELETE("image-likes/{generationId}")
    suspend fun unlikeImage(@Path("generationId") generationId: Long): Response<Unit>

    @POST("generations")
    suspend fun createGeneration(@Body b: GenerationRequest): GenerationCreated

    // v18 (1): ساخت تک‌مرحله‌ای — آپلود + ساخت در یک تماس؛
    // world_id فیلد فرم است، file فایل عکس.
    @Multipart
    @POST("generations/with_image")
    suspend fun createGenerationWithImage(
        @Part file: MultipartBody.Part,
        @Part("world_id") worldId: RequestBody,
    ): GenerationCreated

    @GET("generations/{id}")
    suspend fun generation(@Path("id") id: Long): GenerationStatus

    // v18 (4): بازسازی — ورودی اصلی + قیمتِ روز دنیا.
    @GET("generations/{id}/regenerate")
    suspend fun regenerate(@Path("id") id: Long): RegenerateResponse

    @GET("generations/mine")
    suspend fun myGenerations(): List<GenerationStatus>

    @GET("coins/balance")
    suspend fun balance(): Balance

    @GET("coins/packs")
    suspend fun packs(): List<Pack>

    @POST("coins/purchases/verify")
    suspend fun verifyPurchase(@Body b: PurchaseVerifyRequest): PurchaseVerifyResponse

    // v16: ردیف pending موقع tap-buy، پیش از پرداخت استور.
    @POST("coins/purchases")
    suspend fun createPendingPurchase(@Body b: CreatePendingPurchaseRequest): PendingPurchaseCreated

    // v17 زیبال: ساخت پرداخت + بررسی دوباره (recheck).
    @POST("coins/purchases/zibal")
    suspend fun createZibalPurchase(@Body b: ZibalPurchaseRequest): ZibalPurchaseCreated

    @POST("coins/purchases/{id}/zibal/recheck")
    suspend fun recheckZibalPurchase(@Path("id") id: Long): ZibalRecheckResponse

    // v16: گزارش UGC روی یک تصویر تولیدشده.
    @POST("gallery/reports")
    suspend fun reportGeneration(@Body b: GalleryReportRequest): GalleryReportResponse

    @GET("coins/purchases/pending")
    suspend fun pendingPurchases(): List<PendingPurchase>

    @GET("coins/purchases/mine")
    suspend fun myPurchases(): List<PaymentItem>

    @POST("gallery/submit")
    suspend fun gallerySubmit(@Body b: GallerySubmitRequest): GallerySubmitResponse

    @GET("gallery/mine")
    suspend fun myGallery(): List<GalleryItem>

    @GET("gallery/public")
    suspend fun publicGallery(): List<GalleryItem>

    @GET("app/settings")
    suspend fun publicSettings(): PublicSettings

    // v18 (2): تیکت‌ها.
    @POST("tickets")
    suspend fun createTicket(@Body b: TicketCreateRequest): TicketCreated

    @GET("tickets")
    suspend fun tickets(): List<TicketSummary>

    @GET("tickets/{id}")
    suspend fun ticket(@Path("id") id: Long): TicketDetail

    @POST("tickets/{id}/messages")
    suspend fun sendTicketMessage(
        @Path("id") id: Long,
        @Body b: TicketMessageRequest,
    ): Response<Unit>

    @POST("device-tokens/register")
    suspend fun registerDeviceToken(@Body b: DeviceTokenRequest): Response<Unit>

    @GET("announcements")
    suspend fun announcements(): List<Announcement>
}

object Api {
    private val authInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder().apply {
            token?.let { addHeader("Authorization", "Bearer $it") }
        }.build()
        chain.proceed(req)
    }

    val service: ApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(OkHttpClient.Builder().addInterceptor(authInterceptor).build())
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
        .build()
        .create(ApiService::class.java)

    /** آپلود فایل ورودی (سلفی) به endpoint ‎/uploads بک‌اند خود اپ.
     *  دلیل رد خوانده می‌شود تا کاربر به‌جای پیام کلی، علت را بفهمد:
     *  فایل بزرگ‌تر از سقف → upload_too_big، ۴۰۰ دیگر → upload_conversion،
     *  بقیه‌ی کدها → upload_failed. هیچ‌کدام متن خام به کاربر نمی‌رسد
     *  (نگاشت در userError انجام می‌شود). */
    suspend fun uploadFile(file: java.io.File): String {
        val tokenValue = token ?: throw IllegalStateException("login required")
        if (file.length() > UPLOAD_MAX_BYTES) throw RuntimeException("upload_too_big")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url("${BASE_URL}uploads")
            .addHeader("Authorization", "Bearer $tokenValue")
            .post(body)
            .build()
        return withContext(Dispatchers.IO) {
            val resp = OkHttpClient().newCall(req).execute()
            if (!resp.isSuccessful) {
                val bodyText = try {
                    resp.body?.string() ?: ""
                } catch (_: Exception) {
                    ""
                }
                if (resp.code == 400 && bodyText.contains("file too large")) {
                    throw RuntimeException("upload_too_big")
                }
                if (resp.code == 400) throw RuntimeException("upload_conversion")
                throw RuntimeException("upload_failed")
            }
            JSONObject(resp.body?.string() ?: "").getString("url")
        }
    }

    /** v18 (1): ساخت تک‌مرحله‌ای — همان فایل ورودی + world_id در یک
     *  تماس به ‎/generations/with_image؛ جایگزین uploadFile +
     *  createGeneration در صفحه‌ی ساخت (ادغام ورودی یتیم را می‌بندد).
     *  سقف حجم سمت کاربر مثل قبل چک می‌شود؛ ۴۰۲/۴۰۱ را فراخواننده
     *  مثل قبل مدیریت می‌کند. uploadFile می‌ماند (برای نسخه‌های
     *  قدیمیِ کد و مسیر /uploads سرور دست نخورده است). */
    suspend fun createGenerationWithImage(
        file: java.io.File,
        worldId: Long,
    ): GenerationCreated {
        if (file.length() > UPLOAD_MAX_BYTES) throw RuntimeException("upload_too_big")
        val part = MultipartBody.Part.createFormData(
            "file",
            file.name,
            file.asRequestBody("application/octet-stream".toMediaType()),
        )
        val wid = worldId.toString().toRequestBody("text/plain".toMediaType())
        return service.createGenerationWithImage(part, wid)
    }
}

/** پیام فارسی ساده برای خطای ساخت؛ بدنه‌ی خام provider هرگز به کاربر نشان داده نمی‌شود.
 *  خطاهای بدون نگاشت (از جمله تایم‌اوت خواندن از provider) → «ناموفق تولید عکس». */
fun friendlyGenerationError(raw: String?): String {
    if (raw == null) return "ناموفق تولید عکس"
    // رد ایمنی — همه‌ی مدل‌ها، هدیه و پولی: GapGPT با
    // content_policy_violation / safety system، کلاین با کد ۳۰۳۰
    // (Your output has been flagged)؛ هر دو یعنی همان ترکیب
    // دنیا+عکس دوباره هم رد می‌شود، پس کاربر باید دنیای دیگری بگیرد.
    if (raw.contains("content_policy_violation") || raw.contains("safety system") ||
        raw.contains("3030") || raw.contains("has been flagged")) {
        return "رد ایمنی مدل؛ دنیای دیگری را امتحان کن — هزینه‌ای از تو کم نشده است."
    }
    if (raw.contains("provider http")) {
        return "ساخت ناموفق بود (خطای سرویس‌دهنده)؛ دوباره تلاش کن"
    }
    return "ناموفق تولید عکس"
}
