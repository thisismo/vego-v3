package io.thisismo.vego.client

import io.thisismo.vego.client.core.di.initKoin
import io.thisismo.vego.common.client.network.BackendReachability
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class DiTest {
    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun testKoinInitializes() {
        val koinApp = initKoin()
        assertNotNull(koinApp.koin.getOrNull<BackendReachability>())
    }
}
