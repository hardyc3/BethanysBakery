package com.bethanysbakery.admin.core.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bethanysbakery.admin.core.security.CertificateStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CertificateStatusBadgeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun expiringCertificateShowsExactWarning() {
        compose.setContent { CertificateStatusBadge(CertificateStatus.EXPIRING_SOON) }

        compose.onNodeWithText("Device certificate expiring; re-pair required").assertIsDisplayed()
    }
}
