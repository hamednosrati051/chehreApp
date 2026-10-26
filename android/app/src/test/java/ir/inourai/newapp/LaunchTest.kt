package ir.inourai.newapp

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * تست لانچ: MainActivity را در JVM بالا می‌آورد (بدون گوشی/امیولاتور).
 * اگر باز شدن اپ کرش داشته باشد، همین‌جا با همان stack trace می‌ترکد.
 * فراخوانی‌های شبکه‌ی صفحه‌ی اول در صورت خطا catch می‌شوند، پس نبودِ
 * شبکه‌ی واقعی در تست، خودش باعث fail نمی‌شود.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {

    @Test
    fun mainActivity_launchesWithoutCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        checkNotNull(activity)
        // فرصت بده LaunchedEffectهای صفحه‌ی اول (worlds/publicGallery) اجرا شوند.
        Robolectric.flushForegroundThreadScheduler()
        Thread.sleep(2000)
        controller.destroy()
    }
}
