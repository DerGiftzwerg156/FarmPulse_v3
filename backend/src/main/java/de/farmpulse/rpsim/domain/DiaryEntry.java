package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Diary/chronicle entry (automatic or free player note without mechanical effect). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "diary_entry")
public class DiaryEntry extends SavegameScoped {

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "entry_type", nullable = false, length = 16)
    private DiaryEntryType entryType;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Lob
    @Column(name = "text")
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "related_entity_type", length = 64)
    private String relatedEntityType;

    @Column(name = "related_entity_id")
    private Long relatedEntityId;
}
