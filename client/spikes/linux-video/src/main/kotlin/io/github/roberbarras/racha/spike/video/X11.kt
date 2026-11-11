package io.github.roberbarras.racha.spike.video

import java.awt.Component

/**
 * Native X11 window id of a heavyweight AWT component (what mpv's `wid` expects). Uses JDK internals, so the JVM
 * needs `--add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.awt.X11=ALL-UNNAMED`.
 */
object X11 {
    fun windowId(component: Component): Long {
        require(component.isDisplayable) { "component has no native peer yet" }
        val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
        val peer = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
            .getMethod("getPeer", Component::class.java)
            .invoke(accessor, component)
        return Class.forName("sun.awt.X11.XBaseWindow").getMethod("getWindow").invoke(peer) as Long
    }
}
