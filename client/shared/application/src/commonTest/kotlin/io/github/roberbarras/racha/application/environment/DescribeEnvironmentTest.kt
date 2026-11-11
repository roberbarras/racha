package io.github.roberbarras.racha.application.environment

import kotlin.test.Test
import kotlin.test.assertEquals

class DescribeEnvironmentTest {
    @Test
    fun greetsWithThePlatformProvidedByTheAdapter() {
        val describe = DescribeEnvironment { "TestOS" }

        assertEquals("Hello from Racha on TestOS", describe())
    }
}
