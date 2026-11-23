package com.luum.michi.app.settings

import com.luum.michi.app.settings.ui.model.shortVersion
import com.luum.michi.app.settings.ui.model.versionLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsVersionTest {

    @Test
    fun theVersionRowShowsTheNameAndCode() {
        assertEquals("1.1.0 (10100)", versionLabel("1.1.0", 10100))
        assertEquals("1.1.0 (10100)", versionLabel("1.1.0+27.6edef90", 10100))
    }

    @Test
    fun theShortVersionDropsThePatchAndBuildSuffix() {
        assertEquals("1.1", shortVersion("1.1.0"))
        assertEquals("1.1", shortVersion("1.1.0+22.a403499"))
        assertEquals("1.1", shortVersion("1.1.0-unknown"))
        assertEquals("1.1", shortVersion("1.1"))
    }
}
