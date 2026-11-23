package michi.versioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `exact tag uses the plain version name`() {
        val result = AppVersionCalculator.resolve("v1.0.0-0-gabc1234")

        assertEquals("1.0.0", result.version.versionName)
        assertEquals(10000, result.version.versionCode)
        assertEquals("1.0.0", result.version.iosMarketingVersion)
        assertNull(result.warning)
    }

    @Test
    fun `commits after the tag add build metadata and keep the base version`() {
        val result = AppVersionCalculator.resolve("v1.0.0-22-ga403499")

        assertEquals("1.0.0+22.a403499", result.version.versionName)
        assertEquals(10000, result.version.versionCode)
        assertEquals("1.0.0", result.version.iosMarketingVersion)
    }

    @Test
    fun `1_1_0 maps to 10100`() {
        val result = AppVersionCalculator.resolve("v1.1.0-0-gabc1234")

        assertEquals("1.1.0", result.version.versionName)
        assertEquals(10100, result.version.versionCode)
    }

    @Test
    fun `no tag falls back to 0_0_0-unknown and versionCode 1`() {
        val result = AppVersionCalculator.resolve(null)

        assertEquals("0.0.0-unknown", result.version.versionName)
        assertEquals(1, result.version.versionCode)
        assertEquals("0.0.0", result.version.iosMarketingVersion)
        assertTrue(result.warning!!.contains("no reachable vX.Y.Z git tag"))
    }

    @Test
    fun `malformed describe output falls back`() {
        val result = AppVersionCalculator.resolve("v1.0.0-extra")

        assertEquals("0.0.0-unknown", result.version.versionName)
        assertEquals(1, result.version.versionCode)
    }

    @Test
    fun `both command line overrides win over the tag`() {
        val result = AppVersionCalculator.resolve(
            describe = "v1.0.0-22-ga403499",
            overrideName = "2.0.0",
            overrideCode = "20000",
        )

        assertEquals("2.0.0", result.version.versionName)
        assertEquals(20000, result.version.versionCode)
        assertEquals("2.0.0", result.version.iosMarketingVersion)
    }

    @Test
    fun `name override keeps the tag version code`() {
        val result = AppVersionCalculator.resolve(
            describe = "v1.0.0-22-ga403499",
            overrideName = "1.0.0-custom",
        )

        assertEquals("1.0.0-custom", result.version.versionName)
        assertEquals(10000, result.version.versionCode)
        assertEquals("1.0.0", result.version.iosMarketingVersion)
    }

    @Test
    fun `code override keeps the tag version name`() {
        val result = AppVersionCalculator.resolve(
            describe = "v1.0.0-22-ga403499",
            overrideCode = "10001",
        )

        assertEquals("1.0.0+22.a403499", result.version.versionName)
        assertEquals(10001, result.version.versionCode)
    }

    @Test
    fun `invalid code override fails clearly`() {
        val error = runCatching {
            AppVersionCalculator.resolve("v1.0.0-0-gabc1234", overrideCode = "not-a-number")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("-PmichiVersionCode"))
    }

    @Test
    fun `minor at 100 fails clearly`() {
        val error = runCatching {
            AppVersionCalculator.resolve("v1.100.0-0-gabc1234")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("between 0 and 99"))
    }

    @Test
    fun `patch at 100 fails clearly`() {
        val error = runCatching {
            AppVersionCalculator.resolve("v1.2.100-0-gabc1234")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("between 0 and 99"))
    }

    @Test
    fun `requireTagVersion accepts a valid tag`() {
        val result = AppVersionCalculator.resolve("v1.2.3-0-gabc1234", requireTagVersion = true)

        assertEquals("1.2.3", result.version.versionName)
        assertEquals(10203, result.version.versionCode)
        assertNull(result.warning)
    }

    @Test
    fun `requireTagVersion rejects a missing tag`() {
        val error = runCatching {
            AppVersionCalculator.resolve(null, requireTagVersion = true)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("-PrequireTagVersion=true"))
        assertTrue(error.message!!.contains("0.0.0-unknown"))
    }

    @Test
    fun `requireTagVersion rejects a malformed describe`() {
        val error = runCatching {
            AppVersionCalculator.resolve("v1.0.0-extra", requireTagVersion = true)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("-PrequireTagVersion=true"))
    }

    @Test
    fun `requireTagVersion rejects overrides without a tag`() {
        val error = runCatching {
            AppVersionCalculator.resolve(
                describe = null,
                overrideName = "2.0.0",
                overrideCode = "20000",
                requireTagVersion = true,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("-PrequireTagVersion=true"))
    }
}
