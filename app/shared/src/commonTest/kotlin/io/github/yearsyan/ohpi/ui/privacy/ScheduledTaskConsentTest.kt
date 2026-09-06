package io.github.yearsyan.ohpi.ui.privacy

import io.github.yearsyan.ohpi.net.GatewayCapabilities
import io.github.yearsyan.ohpi.net.GatewayCapabilitySelection
import io.github.yearsyan.ohpi.net.GatewayModelCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduledTaskConsentTest {
    private val first = GatewayModelCapability(id = "shared-model", provider = "first")
    private val second = GatewayModelCapability(id = "shared-model", provider = "second")
    private val capabilities = GatewayCapabilities(
        workspaceId = "workspace",
        directory = "/demo",
        defaultSelection = GatewayCapabilitySelection("first", "shared-model"),
        models = listOf(first, second),
    )

    @Test
    fun defaultResolvesToAnExplicitProviderBeforeApproval() {
        assertEquals(first, scheduledTaskConsentModel("", capabilities))
        assertEquals(second, scheduledTaskConsentModel("second/shared-model", capabilities))
    }

    @Test
    fun unknownAndAmbiguousModelsCannotReceiveApproval() {
        assertNull(scheduledTaskConsentModel("shared-model", capabilities))
        assertNull(scheduledTaskConsentModel("missing/model", capabilities))
        assertNull(scheduledTaskConsentModel("", capabilities.copy(defaultSelection = null)))
        assertNull(scheduledTaskConsentModel("", capabilities.copy(models = listOf(first, first))))
    }
}
