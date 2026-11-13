package io.github.roberbarras.racha.desktop.player

import java.awt.Component
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.invoke.MethodHandle

/**
 * Native X11 window id of a heavyweight AWT component (what mpv's `wid` expects). AWT runs on X11/XWayland on
 * Linux. Uses JDK internals, so the JVM needs `--add-exports java.desktop/sun.awt=ALL-UNNAMED` and
 * `--add-exports java.desktop/sun.awt.X11=ALL-UNNAMED` (set in the build for the app and its tests).
 */
internal object X11 {
    fun windowId(component: Component): Long {
        require(component.isDisplayable) { "component has no native peer yet" }
        val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
        val peer = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
            .getMethod("getPeer", Component::class.java)
            .invoke(accessor, component)
        return Class.forName("sun.awt.X11.XBaseWindow").getMethod("getWindow").invoke(peer) as Long
    }

    /**
     * Xlib has one process-wide error handler. AWT installs its own, which never exits (it logs and ignores errors
     * it is not expecting). mpv's X11 video output (`vo_x11_init`/`vo_x11_uninit`) installs its logging handler when
     * it starts and calls `XSetErrorHandler(NULL)` when it stops, which leaves Xlib's default handler — and that one
     * *exits the process* on any error, so the next harmless AWT error would kill the app. [captureToolkitHandler]
     * remembers AWT's handler once AWT is up; [restoreToolkitHandler] puts it back whenever mpv's output starts or
     * stops and after a core is destroyed. AWT's handler also covers errors on mpv's own connection (ignored).
     */
    @Volatile private var toolkitHandler: MemorySegment? = null

    private val setErrorHandler: MethodHandle? by lazy {
        runCatching {
            val linker = Linker.nativeLinker()
            val symbol = SymbolLookup.libraryLookup("libX11.so.6", Arena.global()).find("XSetErrorHandler").orElseThrow()
            linker.downcallHandle(symbol, FunctionDescriptor.of(ADDRESS, ADDRESS))
        }.getOrNull()
    }

    fun captureToolkitHandler() {
        if (toolkitHandler != null) return
        val set = setErrorHandler ?: return
        val current = set.invokeWithArguments(MemorySegment.NULL) as MemorySegment
        set.invokeWithArguments(current)
        toolkitHandler = current
    }

    fun restoreToolkitHandler() {
        val handler = toolkitHandler ?: return
        val previous = setErrorHandler?.invokeWithArguments(handler) as MemorySegment?
        trace { "restore handler (was ${if (previous == handler) "awt" else previous?.address()?.toString(16)})" }
    }

    private val awtLockMethods by lazy {
        runCatching {
            val toolkit = Class.forName("sun.awt.SunToolkit")
            toolkit.getMethod("awtLock") to toolkit.getMethod("awtUnlock")
        }.getOrNull()
    }

    /**
     * Runs [block] holding AWT's toolkit lock, under which AWT makes every Xlib call: used around mpv's video output
     * shutting down plus [restoreToolkitHandler], so no AWT request can fail while Xlib's fatal default handler is
     * briefly installed. Lock order is always AWT first, then the engine's own lock.
     */
    fun <T> withToolkitLock(block: () -> T): T {
        val (lock, unlock) = awtLockMethods ?: return block()
        lock.invoke(null)
        try {
            return block()
        } finally {
            unlock.invoke(null)
        }
    }

    /** -Dracha.debug.x11=true: traces window ids and handler changes to stderr. */
    private val debug = System.getProperty("racha.debug.x11") == "true"

    fun trace(message: () -> String) {
        if (debug) System.err.println("X11 ${System.nanoTime() / 1_000_000} [${Thread.currentThread().name}] ${message()}")
    }
}
