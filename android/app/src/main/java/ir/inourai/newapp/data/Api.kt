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
private const val BASE_URL = "http://171.22.25.152:5001/api/v1/"

data class OtpRequest(val mobile: String)
data class OtpVerify(val mobile: String, val code: String)
data class TestLoginRequest(val mobile: String)
data class TokenResponse(val token: String, val is_new: Boolean? = null)
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
)
data class PendingPurchase(
    val id: Long,
    val pack_id: Long,
    val amount_irr: Long,
    val purchase_token: String?,
)
data class GallerySubmitRequest(val generation_id: Long)
data class GallerySubmitResponse(val id: Long, val status: String)
data class GalleryItem(val id: Long, val image_url: String, val generation_id: Long? = null)
data class PublicSettings(
    val about_us: String = "",
    val support_info: String = "",
    val bug_report_info: String = "",
    val instagram_handle: String = "",
    val bazaar_url: String = "",
)

interface ApiService {
    @POST("auth/otp/request")
    suspend fun otpRequest(@Body b: OtpRequest): Response<Unit>

    @POST("auth/otp/verify")
    suspend fun otpVerify(@Body b: OtpVerify): TokenResponse

    @POST("auth/test/login")
    suspend fun testLogin(@Body b: TestLoginRequest): TokenResponse

    @GET("auth/me")
    suspend fun me(): MeResponse

    @GET("categories")
    suspend fun categories(): List<Category>

    @GET("worlds")
    suspend fun worlds(): List<World>

    @GET("worlds/{id}")
    suspend fun world(@Path("id") id: Long): World

    @GET("favorites")
    suspend fun favorites(): List<World>

    @POST("favorites")
    suspend fun addFavorite(@Body b: FavoriteRequest): Response<Unit>

    @DELETE("favorites/{worldId}")
    suspend fun removeFavorite(@Path("worldId") worldId: Long): Response<Unit>

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

    @POST("gallery/submit")
    suspend fun gallerySubmit(@Body b: GallerySubmitRequest): GallerySubmitResponse

    @GET("gallery/mine")
    suspend fun myGallery(): List<GalleryItem>

    @GET("gallery/public")
    suspend fun publicGallery(): List<GalleryItem>

    @GET("app/settings")
    suspend fun publicSettings(): PublicSettings
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

    /** آپلود فایل ورودی (سلفی) به endpoint ‎/uploads بک‌اند خود اپ. */
    suspend fun uploadFile(file: java.io.File): String {
        val tokenValue = token ?: throw IllegalStateException("login required")
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
                throw RuntimeException("upload failed: ${resp.code}")
            }
            JSONObject(resp.body?.string() ?: "").getString("url")
        }
    }
}

/** پیام فارسی ساده برای خطای ساخت؛ بدنه‌ی خام provider هرگز به کاربر نشان داده نمی‌شود. */
fun friendlyGenerationError(raw: String?): String {
    if (raw == null) return "ساخت ناموفق بود"
    if (raw.contains("content_policy_violation") || raw.contains("safety system")) {
        return "ساخت این تصویر به‌خاطر محدودیت ایمنی مدل رد شد. عکس یا دنیای دیگری را امتحان کن؛ سکه و هدیه‌ات کم نشده است."
    }
    if (raw.contains("provider http")) {
        return "ساخت ناموفق بود (خطای سرویس‌دهنده)؛ دوباره تلاش کن"
    }
    return raw
}
