package com.fabrice.pdfbox

import com.fabrice.pdfbox.core.update.UpdateChecker
import com.fabrice.pdfbox.core.update.UpdateSchedule
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `comparaison de versions segment par segment`() {
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0", "1.0.1") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.1.0", "1.1.0"))
        assertTrue(UpdateChecker.compareVersions("2.0", "1.9.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.1.0", "1.1"))
    }

    @Test
    fun `manifeste lu avant l API`() {
        val body = """{"version":"1.2.0","notes":"corrections","apk":"https://exemple/pdf-box-v1.2.0.apk"}"""
        val resultat = UpdateChecker.parseManifest(body, "1.1.0")
        assertTrue(resultat is UpdateChecker.CheckResult.Available)
        val info = (resultat as UpdateChecker.CheckResult.Available).info
        assertEquals("1.2.0", info.version)
        assertEquals("https://exemple/pdf-box-v1.2.0.apk", info.apkUrl)
    }

    @Test
    fun `manifeste a jour`() {
        val body = """{"version":"1.1.0","apk":"https://exemple/pdf-box-v1.1.0.apk"}"""
        assertTrue(UpdateChecker.parseManifest(body, "1.1.0") is UpdateChecker.CheckResult.UpToDate)
    }

    @Test
    fun `manifeste sans adresse d APK laisse parler l API`() {
        assertNull(UpdateChecker.parseManifest("""{"version":"9.0.0"}""", "1.0.0"))
    }

    @Test
    fun `manifeste invalide renvoie null pour laisser parler l API`() {
        assertNull(UpdateChecker.parseManifest("<html>404</html>", "1.0.0"))
    }

    @Test
    fun `API - release roulante latest lue via le titre`() {
        val body = """
            [{"tag_name":"latest","name":"PDF Box by Fabrice Heuvrard v1.3.0","draft":false,
              "assets":[{"name":"version.json","browser_download_url":"https://exemple/version.json"},
                        {"name":"pdf-box-v1.3.0.apk","browser_download_url":"https://exemple/pdf-box-v1.3.0.apk"}]}]
        """.trimIndent()
        val resultat = UpdateChecker.parseApi(body, "1.2.0")
        assertTrue(resultat is UpdateChecker.CheckResult.Available)
        val info = (resultat as UpdateChecker.CheckResult.Available).info
        assertEquals("1.3.0", info.version)
        assertEquals("https://exemple/pdf-box-v1.3.0.apk", info.apkUrl)
    }

    @Test
    fun `API - brouillon ignore`() {
        val body = """
            [{"tag_name":"v9.9.9","name":"v9.9.9","draft":true,
              "assets":[{"name":"pdf-box-v9.9.9.apk","browser_download_url":"https://exemple/app.apk"}]}]
        """.trimIndent()
        assertTrue(UpdateChecker.parseApi(body, "1.0.0") is UpdateChecker.CheckResult.UpToDate)
    }

    @Test
    fun `API - sans APK, rien a installer`() {
        val body = """[{"tag_name":"v2.0.0","name":"v2.0.0","draft":false,"assets":[]}]"""
        assertTrue(UpdateChecker.parseApi(body, "1.0.0") is UpdateChecker.CheckResult.UpToDate)
    }

    @Test
    fun `APK choisi par sa version`() {
        val assets = JSONArray(
            """[{"name":"pdf-box-v1.0.0.apk","browser_download_url":"https://exemple/ancien.apk"},
                {"name":"pdf-box-v1.1.0.apk","browser_download_url":"https://exemple/nouveau.apk"}]"""
        )
        assertEquals("https://exemple/nouveau.apk", UpdateChecker.pickApk(assets, "1.1.0"))
    }

    @Test
    fun `calendrier des verifications`() {
        assertTrue(UpdateSchedule.isDue(manual = true, lastCheckAt = 0L, now = 0L))
        assertTrue(UpdateSchedule.isDue(manual = false, lastCheckAt = UpdateSchedule.NEVER, now = 123L))
        assertFalse(UpdateSchedule.isDue(manual = false, lastCheckAt = 1_000L, now = 2_000L))
        assertTrue(UpdateSchedule.isDue(manual = false, lastCheckAt = 1_000L, now = 1_000L + UpdateSchedule.MIN_INTERVAL_MS))
    }

    @Test
    fun `version extraite d un titre`() {
        assertEquals("1.3.0", UpdateChecker.extractVersion("PDF Box by Fabrice Heuvrard v1.3.0"))
        assertNull(UpdateChecker.extractVersion("latest"))
    }
}
