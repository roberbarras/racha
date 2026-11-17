package io.github.roberbarras.racha.application.cast

/** "Send to" (H18) as a platform wires it: finding devices, talking to them, serving them the files. */
class CastServices(
    val discovery: CastDiscovery,
    val connector: CastConnector,
    /** One per player: it serves only that player's files and stops with it. */
    val publisher: () -> MediaPublisher,
)
