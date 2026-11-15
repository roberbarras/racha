package io.github.roberbarras.racha.identity.adapter.web;

import io.github.roberbarras.racha.identity.application.AuthService;
import io.github.roberbarras.racha.identity.application.DeviceInfo;
import io.github.roberbarras.racha.identity.domain.Device;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import io.github.roberbarras.racha.shared.adapter.web.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** §7.3 `/devices/register`, plus the list of the user's devices. */
@RestController
@RequestMapping("/devices")
@Tag(name = "Devices")
public class DeviceController {
    public record DeviceResponse(UUID id, String name, String platform, String appVersion, Instant createdAt, Instant lastSeenAt) {
        static DeviceResponse of(Device d) {
            return new DeviceResponse(d.id(), d.name(), d.platform().wire(), d.appVersion(), d.createdAt(), d.lastSeenAt());
        }
    }

    private final AuthService auth;
    private final IdentityPorts.Devices devices;
    private final Clock clock;

    public DeviceController(AuthService auth, IdentityPorts.Devices devices, Clock clock) {
        this.auth = auth;
        this.devices = devices;
        this.clock = clock;
    }

    @PostMapping("/register")
    @Operation(summary = "Register or update a device of the signed-in user (name, platform, app version)")
    public DeviceResponse register(@AuthenticationPrincipal Jwt jwt, @RequestBody AuthController.DeviceRequest body) {
        var caller = Caller.of(jwt);
        return DeviceResponse.of(auth.registerDevice(caller.userId(), new DeviceInfo(body.id(), body.name(), body.platform(), body.appVersion()), clock.instant()));
    }

    @GetMapping
    @Operation(summary = "The signed-in user's devices")
    public List<DeviceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return devices.ofUser(Caller.of(jwt).userId()).stream().map(DeviceResponse::of).toList();
    }
}
