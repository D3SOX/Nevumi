package me.d3sox.nevumi.app

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ControllerContextTest {
    @Test
    fun aTimeoutInsideAnActionIsReportedAsAnErrorInsteadOfEndingItSilently(): Unit = runBlocking {
        val context = ControllerContext()
        try {
            val action = context.action { withTimeout(10) { awaitCancellation() } }
            action.join()
            assertFalse(action.isCancelled)
            assertNotNull(context.state.value.error)
        } finally { context.scope.cancel() }
    }
}
