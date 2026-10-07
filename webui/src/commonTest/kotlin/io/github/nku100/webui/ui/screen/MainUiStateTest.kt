package io.github.nku100.webui.ui.screen

import io.github.nku100.webui.platform.RootEnvironment
import io.github.nku100.webui.platform.RootImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainUiStateTest {
    @Test
    fun beginningFetchKeepsPreviouslyDetectedRootEnvironment() {
        val rootEnvironment = RootEnvironment(
            available = true,
            implementation = RootImplementation.MAGISK,
            version = "31.0:MAGISKSU",
        )
        val state = MainUiState(rootEnvironment = rootEnvironment, isLoading = false)

        val loadingState = state.beginFetch()

        assertTrue(loadingState.isLoading)
        assertEquals(rootEnvironment, loadingState.rootEnvironment)
    }
}
