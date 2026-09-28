package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Roadmap V2 R2-F2: one yes/no question sent to the game (PROMPT instruction). The key identifies the occasion (kind,
 * target and round) so the same decision is never asked twice.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "game_prompt")
public class GamePrompt extends SavegameScoped {

    @Column(name = "prompt_id", nullable = false, length = 64)
    private String promptId;

    @Column(name = "prompt_key", nullable = false, length = 96)
    private String promptKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private PromptKind kind;

    /** Id of the call, contract, case or credit application the question is about. */
    @Column(name = "target_id", nullable = false)
    private long targetId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private PromptStatus status = PromptStatus.OPEN;

    @Column(name = "instruction_id", length = 64)
    private String instructionId;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "text", nullable = false, length = 600)
    private String text;

    @Column(name = "created_game_time", nullable = false)
    private long createdGameTime;

    @Column(name = "expires_game_time", nullable = false)
    private long expiresGameTime;

    /** YES / NO once answered in the game. */
    @Column(name = "answer", length = 32)
    private String answer;

    @Column(name = "answered_game_time")
    private Long answeredGameTime;

    /** DONE, NOTHING (a "no" that leaves the decision in the browser), IGNORED or the error message. */
    @Column(name = "result", length = 255)
    private String result;
}
