package com.example.nfctransit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackPolicyTest {
    @Test
    fun parseOverlaysBooleanKeysOnDefaults() {
        assertEquals(FeedbackPolicy.DEFAULT, FeedbackPolicy.parse("{}"))
        assertEquals(
            FeedbackPolicy(publish = false, imported = true, terminalMismatch = false),
            FeedbackPolicy.parse("""{"publish":false,"imported":true,"terminalMismatch":"yes","extra":1}""")
        )
    }

    @Test
    fun policyUrlSitsNextToUploadEndpoint() {
        assertEquals(
            "https://example.org:8443/v1/feedback-policy",
            FeedbackPolicy.policyUrl("https://example.org:8443/v1/overrides")
        )
        assertNull(FeedbackPolicy.policyUrl(""))
    }
}
