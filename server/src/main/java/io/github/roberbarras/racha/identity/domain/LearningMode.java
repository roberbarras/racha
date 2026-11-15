package io.github.roberbarras.racha.identity.domain;

/** Spec §2.1: study or leisure. Stored lower-case. */
public enum LearningMode {
    STUDY, LEISURE;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static LearningMode fromWire(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
