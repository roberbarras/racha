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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** §7.3 `/devices/register`, plus the list of the user's devices. */
@RestController
@RequestMapping("/devices")
@Tag(name = "Devices")
public class DeviceController {
    /** [activeSessions]: signed-in sessions now (0: signed out); [current]: the device making this request. */
    public record DeviceResponse(
            UUID id, String name, String platform, String appVersion, Instant createdAt, Instant lastSeenAt, int activeSessions, boolean current) {
        static DeviceResponse of(Device d) {
            return of(d, 0, false);
        }

        static DeviceResponse of(Device d, int activeSessions, boolean current) {
            return new DeviceResponse(d.id(), d.name(), d.platform().wire(), d.appVersion(), d.createdAt(), d.lastSeenAt(), activeSessions, current);
        }
    }

    private final AuthService auth;
    private final IdentityPorts.Devices devices;
    private final IdentityPorts.Sessions sessions;
    private final Clock clock;

    public DeviceController(AuthService auth, IdentityPorts.Devices devices, IdentityPorts.Sessions sessions, Clock clock) {
        this.auth = auth;
        this.devices = devices;
        this.sessions = sessions;
        this.clock = clock;
    }

    @PostMapping("/register")
    @Operation(summary = "Register or update a device of the signed-in user (name, platform, app version)")
    public DeviceResponse register(@AuthenticationPrincipal Jwt jwt, @RequestBody AuthController.DeviceRequest body) {
        var caller = Caller.of(jwt);
        return DeviceResponse.of(auth.registerDevice(caller.userId(), new DeviceInfo(body.id(), body.name(), body.platform(), body.appVersion()), clock.instant()));
    }

    @GetMapping
    @Operation(summary = "The signed-in user's devices, with their active sessions and which one is calling")
    public List<DeviceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        var caller = Caller.of(jwt);
        var active = sessions.activeByDevice(caller.userId(), clock.instant());
        return devices.ofUser(caller.userId()).stream()
                .map(d -> DeviceResponse.of(d, active.getOrDefault(d.id(), 0), d.id().equals(caller.deviceId())))
                .toList();
    }

    @PostMapping("/{id}/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign one of your devices out (a lost phone); it keeps its local data and can sign in again")
    public void signOut(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var caller = Caller.of(jwt);
        if (devices.byId(id).filter(d -> d.userId().equals(caller.userId())).isEmpty()) throw new NotFound();
        sessions.revokeDevice(caller.userId(), id, clock.instant());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Forget one of your devices (and sign it out)")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!devices.delete(Caller.of(jwt).userId(), id)) throw new NotFound();
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static final class NotFound extends RuntimeException {
        NotFound() {
            super("no such device");
        }
    }
}
