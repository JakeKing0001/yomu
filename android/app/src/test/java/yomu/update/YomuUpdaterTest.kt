package yomu.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class YomuUpdaterTest {

    private fun release(tag: String, vararg assets: String) = """
        {"tag_name":"$tag","draft":false,"assets":[${assets.joinToString(",") {
        """{"name":"$it","size":10,"browser_download_url":"https://dl/$it"}"""
    }}]}
    """.trimIndent()

    @Test
    fun `picks the newest android build for the device's processor`() {
        val text = "[" + listOf(
            release("v1.4", "Yomu.ipa"),
            release("android-v400", "yomu-arm64-v8a.apk"),
            release("android-v512", "yomu-arm64-v8a.apk", "yomu-x86_64.apk"),
        ).joinToString(",") + "]"

        val newest = YomuUpdater.newestRelease(text, listOf("arm64-v8a"))

        assertEquals(512L, newest?.versionCode)
        assertEquals("https://dl/yomu-arm64-v8a.apk", newest?.apkUrl)
    }

    @Test
    fun `ignores releases without an apk this device can run`() {
        val text = "[" + release("android-v600", "yomu-x86_64.apk") + "]"

        assertNull(YomuUpdater.newestRelease(text, listOf("arm64-v8a")))
    }
}
