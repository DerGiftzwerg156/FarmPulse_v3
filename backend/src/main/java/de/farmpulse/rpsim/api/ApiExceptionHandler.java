package de.farmpulse.rpsim.api;

import java.util.LinkedHashMap;
import java.util.Map;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.GamePcOnlyException;
import de.farmpulse.rpsim.common.NotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Consistent error responses: {"code": "...", "message": "...", "fields": {...}}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(String code, String message, Map<String, String> fields) {
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", e.getMessage(), Map.of()));
    }

    /** Roadmap V3 R3-N1 / review 10/2026 Phase 0.4: settings of the installation only on the gaming PC. */
    @ExceptionHandler(GamePcOnlyException.class)
    public ResponseEntity<ApiError> gamePcOnly(GamePcOnlyException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("LAN_GAME_PC_ONLY", e.getMessage(), Map.of()));
    }

    /**
     * Review 10/2026 Phase 1.4 (R-2): the data was changed in between (another device or the bridge cycle) - nothing
     * was saved; the frontend shows a hint and reloads.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiError> concurrentUpdate(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("CONCURRENT_UPDATE",
                "Die Daten wurden inzwischen geändert – die Ansicht wurde neu geladen. Bitte die Eingabe prüfen und "
                        + "erneut absenden.", Map.of()));
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> rule(BusinessRuleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getCode(), e.getMessage(), Map.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION", "Eingabe ungültig", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiError> unreadable(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST",
                "Anfrage nicht lesbar (Zahlen bitte über das Formular als Zahl übermitteln)", Map.of()));
    }
}
