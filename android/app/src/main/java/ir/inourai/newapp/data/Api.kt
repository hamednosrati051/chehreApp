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
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

// TODO: با آدرس واقعی بک‌اند خود اپ عوض شود.
private const val BASE_URL = "https://chehre.inourai.ir/api/v1/"

data class OtpRequest(val mobile: String)
data class OtpVerify(val mobile: String, val code: String)
data class TokenResponse(val token: String, val is_new: Boolean? = null)
// v13: Bazaar native login — server creates/links a user by Bazaar accountId.
data class BazaarLoginRequest(val account_id: String)
data class BazaarLinkRequest(val account_id: String)
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
)
data class PurchaseVerifyRequest(
    val pack_id: Long,
    val purchase_token: String,
    val purchase_data: String,
    val signature: String,
)
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
data class GalleryItem(val id: Long, val image_url: String, val generation_id: Long? = null)
data class DeviceTokenRequest(val token: String, val platform: String = "android")
data class Announcement(val id: Long, val title: String, val body: String, val created_at: String? = null)
data class PublicSettings(
    val about_us: String = "",
    val support_info: String = "",
    val bug_report_info: String = "",
    val bazaar_url: String = "",
    val rules_privacy: String = "",
    val entry_gift_enabled: String = "1",
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

    @GET("generations/{id}")
    suspend fun generation(@Path("id") id: Long): GenerationStatus

    @GET("generations/mine")
    suspend fun myGenerations(): List<GenerationStatus>

    @GET("coins/balance")
    suspend fun balance(): Balance

    @GET("coins/packs")
    suspend fun packs(): List<Pack>

    @POST("coins/purchases/verify")
    suspend fun verifyPurchase(@Body b: PurchaseVerifyRequest): PurchaseVerifyResponse

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
