package de.farmpulse.rpsim.lan;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.LanSession;
import de.farmpulse.rpsim.domain.LanSettings;
import de.farmpulse.rpsim.repository.LanSessionRepository;
import de.farmpulse.rpsim.repository.LanSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-N1/N2: switch "Im Heimnetz erreichbar", optional PIN (owner decision) and the sessions of devices in
 * the home network. The PIN is hashed with PBKDF2WithHmacSHA256 from the JDK; a session cookie value is random and
 * stored only as SHA-256. Too many wrong PINs lock the sender address for a while (in memory).
 */
@Service
public class LanAccessService {

    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int TOKEN_BYTES = 32;

    /** Result of a login attempt. */
    public enum LoginResult { OK, WRONG_PIN, LOCKED, NO_PIN }

    /** Outcome of {@link #login}: the new cookie value on success. */
    public record Login(LoginResult result, String token, long lockedSeconds) {
    }

    private record Attempts(int failed, Instant lockedUntil) {
    }

    private final LanSettingsRepository settings;
    private final LanSessionRepository sessions;
    private final RpsimProperties props;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public LanAccessService(LanSettingsRepository settings, LanSessionRepository sessions, RpsimProperties props) {
        this.settings = settings;
        this.sessions = sessions;
        this.props = props;
    }

    /** Tests: fixed real time for lockout and session expiry. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    private RpsimProperties.Lan cfg() {
        return props.getWeb().getLan();
    }

    private LanSettings row() {
        return settings.findById(LanSettings.ID).orElseGet(() -> {
            LanSettings s = new LanSettings();
            s.setId(LanSettings.ID);
            s.setUpdatedAt(clock.instant());
            return settings.save(s);
        });
    }

    @Transactional
    public boolean enabled() {
        return row().isEnabled();
    }

    @Transactional
    public boolean pinSet() {
        return row().getPinHash() != null;
    }

    /** Switches the home-network access; switching it off ends every session of the devices. */
    @Transactional
    public void setEnabled(boolean enabled) {
        LanSettings s = row();
        s.setEnabled(enabled);
        s.setUpdatedAt(clock.instant());
        if (!enabled) {
            sessions.deleteAll();
        }
    }

    /** Sets or changes the PIN (digits only, length from the config); every session of the devices ends. */
    @Transactional
    public void setPin(String pin) {
        if (pin == null || !pin.matches("\\d+") || pin.length() < cfg().getPinMinLength()
                || pin.length() > cfg().getPinMaxLength()) {
            throw new BusinessRuleException("LAN_PIN_INVALID", "Die PIN muss aus " + cfg().getPinMinLength() + " bis "
                    + cfg().getPinMaxLength() + " Ziffern bestehen");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        int iterations = cfg().getPbkdf2Iterations();
        LanSettings s = row();
        s.setPinSalt(Base64.getEncoder().encodeToString(salt));
        s.setPinIterations(iterations);
        s.setPinHash(Base64.getEncoder().encodeToString(pbkdf2(pin, salt, iterations)));
        s.setUpdatedAt(clock.instant());
        sessions.deleteAll();
        attempts.clear();
    }

    /** Removes the PIN (it is optional); devices in the home network then need no login. Sessions end. */
    @Transactional
    public void removePin() {
        LanSettings s = row();
        s.setPinHash(null);
        s.setPinSalt(null);
        s.setPinIterations(null);
        s.setUpdatedAt(clock.instant());
        sessions.deleteAll();
        attempts.clear();
    }

    /** Checks the PIN of a device; on success a new session (cookie value) is created. */
    @Transactional
    public Login login(String pin, String remoteAddr) {
        LanSettings s = row();
        if (s.getPinHash() == null) {
            return new Login(LoginResult.NO_PIN, null, 0);
        }
        Instant now = clock.instant();
        String key = remoteAddr == null ? "" : remoteAddr;
        Attempts a = attempts.get(key);
        if (a != null && a.lockedUntil() != null && now.isBefore(a.lockedUntil())) {
            return new Login(LoginResult.LOCKED, null, secondsUntil(now, a.lockedUntil()));
        }
        boolean ok = pin != null && MessageDigest.isEqual(Base64.getDecoder().decode(s.getPinHash()),
                pbkdf2(pin, Base64.getDecoder().decode(s.getPinSalt()), s.getPinIterations()));
        if (!ok) {
            int failed = (a == null || a.lockedUntil() != null ? 0 : a.failed()) + 1;
            if (failed >= cfg().getMaxFailedAttempts()) {
                Instant until = now.plus(Duration.ofMinutes(cfg().getLockoutMinutes()));
                attempts.put(key, new Attempts(failed, until));
                return new Login(LoginResult.LOCKED, null, secondsUntil(now, until));
            }
            attempts.put(key, new Attempts(failed, null));
            return new Login(LoginResult.WRONG_PIN, null, 0);
        }
        attempts.remove(key);
        sessions.deleteExpired(now);
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        LanSession session = new LanSession();
        session.setTokenHash(sha256(token));
        session.setRemoteAddress(remoteAddr);
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(Duration.ofDays(cfg().getSessionDays())));
        sessions.save(session);
        return new Login(LoginResult.OK, token, 0);
    }

    /** True when the cookie value belongs to a session that has not expired. */
    @Transactional(readOnly = true)
    public boolean sessionValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return sessions.findByTokenHash(sha256(token)).map(s -> clock.instant().isBefore(s.getExpiresAt()))
                .orElse(false);
    }

    /** Ends the session of this cookie value (logout). */
    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.findByTokenHash(sha256(token)).ifPresent(sessions::delete);
        }
    }

    /** Remaining lock in whole seconds (rounded up). */
    private static long secondsUntil(Instant now, Instant until) {
        return (Duration.between(now, until).toMillis() + 999) / 1000;
    }

    public long sessionSeconds() {
        return Duration.ofDays(cfg().getSessionDays()).toSeconds();
    }

    public String cookieName() {
        return cfg().getCookieName();
    }

    static byte[] pbkdf2(String pin, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
