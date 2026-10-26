package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRetrySettingsTest {
    @Test
    fun `disabled means no retries regardless of stored count`() {
        val settings = ModelRetrySettings(enabled = false, maxRetries = 5)
        assertEquals(0, settings.effectiveMaxRetries)
    }

    @Test
    fun `enabled keeps bounded retry count`() {
        assertEquals(ModelRetrySettings.DEFAULT_MAX_RETRIES, ModelRetrySettings().effectiveMaxRetries)
        assertEquals(0, ModelRetrySettings(maxRetries = 0).effectiveMaxRetries)
        assertEquals(ModelRetrySettings.MAX_RETRIES, ModelRetrySettings(maxRetries = 99).effectiveMaxRetries)
    }

    @Test
    fun `normalized clamps out of range values`() {
        val normalized = ModelRetrySettings(maxRetries = 99).normalized()
        assertEquals(ModelRetrySettings.MAX_RETRIES, normalized.maxRetries)
        assertTrue(normalized.enabled)
        assertEquals(0, ModelRetrySettings(maxRetries = -5).normalized().maxRetries)
    }

    @Test
    fun `defaults enable retries`() {
        val settings = ModelRetrySettings()
        assertTrue(settings.enabled)
        assertFalse(settings.effectiveMaxRetries == 0)
    }
}
