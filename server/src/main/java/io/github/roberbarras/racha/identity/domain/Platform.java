package io.github.roberbarras.racha.identity.domain;

/** Where a device runs: the two apps, or the web panel (H14). */
public enum Platform {
    ANDROID, LINUX, WEB;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Platform fromWire(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
