package io.github.roberbarras.racha.identity.application;

import io.github.roberbarras.racha.identity.domain.Device;
import io.github.roberbarras.racha.identity.domain.Platform;
import java.util.UUID;

/** The device a client signs in from; [id] reuses a device already registered by this user. */
public record DeviceInfo(UUID id, String name, String platform, String appVersion) {
    Platform parsedPlatform() {
        try {
            return Platform.fromWire(platform);
        } catch (RuntimeException e) {
            throw new AuthErrors.InvalidInput("platform must be android or linux");
        }
    }

    String checkedName() {
        if (name == null || name.isBlank() || name.length() > Device.MAX_NAME_LENGTH) {
            throw new AuthErrors.InvalidInput("device name is required (max " + Device.MAX_NAME_LENGTH + " characters)");
        }
        return name.strip();
    }
}
