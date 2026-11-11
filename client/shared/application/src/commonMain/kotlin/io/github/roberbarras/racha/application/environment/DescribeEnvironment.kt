package io.github.roberbarras.racha.application.environment

/** Use case: the greeting shown by the walking skeleton, proving UI → application → adapter wiring. */
class DescribeEnvironment(private val platform: PlatformInfo) {
    operator fun invoke(): String = "Hello from Racha on ${platform.name()}"
}
