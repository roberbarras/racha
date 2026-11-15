package io.github.roberbarras.racha.identity.domain;

public enum Platform {
    ANDROID, LINUX;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Platform fromWire(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
