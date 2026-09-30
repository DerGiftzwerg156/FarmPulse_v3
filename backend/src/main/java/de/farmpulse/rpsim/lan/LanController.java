package de.farmpulse.rpsim.lan;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.api.ApiExceptionHandler.ApiError;
import de.farmpulse.rpsim.config.RpsimProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3 R3-N1..N3: settings card "Tablet & Netzwerk" and the PIN login of devices in the home network. Only the
 * gaming PC (loopback) may change the switch and the PIN (owner decision); a tablet sees the card read-only.
 */
@RestController
public class LanController {

    /** Card "Tablet & Netzwerk" and the login screen. {@code urls} = http://&lt;private IPv4&gt;:&lt;port&gt;. */
    public record LanStatusView(boolean enabled, boolean pinSet, boolean gamePc, boolean authenticated,
                                List<String> urls, int pinMinLength, int pinMaxLength) {
    }

    public record LanSettingsRequest(@NotNull Boolean enabled) {
    }

    public record LanPinRequest(String pin) {
    }

    public record LanLoginView(String result, long lockedSeconds) {
    }

    private final LanAccessService lan;
    private final RpsimProperties props;

    public LanController(LanAccessService lan, RpsimProperties props) {
        this.lan = lan;
        this.props = props;
    }

    @GetMapping("/api/lan/status")
    public LanStatusView status(HttpServletRequest req) {
        boolean gamePc = NetworkAddresses.isLoopback(req.getRemoteAddr());
        boolean authenticated = gamePc || !lan.pinSet()
                || lan.sessionValid(LanAccessFilter.cookie(req, lan.cookieName()));
        int port = req.getLocalPort();
        List<String> urls = NetworkAddresses.privateIpv4Addresses().stream().map(ip -> "http://" + ip + ":" + port)
                .toList();
        var cfg = props.getWeb().getLan();
        return new LanStatusView(lan.enabled(), lan.pinSet(), gamePc, authenticated, urls, cfg.getPinMinLength(),
                cfg.getPinMaxLength());
    }

    @PutMapping("/api/lan/settings")
    public LanStatusView save(@Valid @RequestBody LanSettingsRequest r, HttpServletRequest req) {
        requireGamePc(req);
        lan.setEnabled(r.enabled());
        return status(req);
    }

    @PutMapping("/api/lan/pin")
    public LanStatusView setPin(@RequestBody LanPinRequest r, HttpServletRequest req) {
        requireGamePc(req);
        lan.setPin(r.pin());
        return status(req);
    }

    @DeleteMapping("/api/lan/pin")
    public LanStatusView removePin(HttpServletRequest req) {
        requireGamePc(req);
        lan.removePin();
        return status(req);
    }

    /** PIN login of a device in the home network: sets the session cookie (HttpOnly, SameSite=Strict). */
    @PostMapping("/api/lan/login")
    public ResponseEntity<LanLoginView> login(@RequestBody LanPinRequest r, HttpServletRequest req) {
        LanAccessService.Login l = lan.login(r.pin(), req.getRemoteAddr());
        return switch (l.result()) {
            case OK -> ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(l.token(), lan.sessionSeconds()).toString())
                    .body(new LanLoginView("OK", 0));
            case NO_PIN -> ResponseEntity.ok(new LanLoginView("NO_PIN", 0));
            case WRONG_PIN -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new LanLoginView("WRONG_PIN", 0));
            case LOCKED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new LanLoginView("LOCKED", l.lockedSeconds()));
        };
    }

    @PostMapping("/api/lan/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        lan.logout(LanAccessFilter.cookie(req, lan.cookieName()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(lan.cookieName(), value).httpOnly(true).sameSite("Strict").path("/")
                .maxAge(maxAgeSeconds).build();
    }

    private static void requireGamePc(HttpServletRequest req) {
        if (!NetworkAddresses.isLoopback(req.getRemoteAddr())) {
            throw new GamePcOnlyException();
        }
    }

    /** Changing the switch or the PIN from a tablet. */
    static class GamePcOnlyException extends RuntimeException {
    }

    @ExceptionHandler(GamePcOnlyException.class)
    public ResponseEntity<ApiError> gamePcOnly() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("LAN_GAME_PC_ONLY", "Nur am Spiele-PC änderbar", Map.of()));
    }
}
