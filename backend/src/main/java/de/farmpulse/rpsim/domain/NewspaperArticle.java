package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D1: one article of a newspaper issue - facts of the backend, text of the AI (or a template). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "newspaper_article")
public class NewspaperArticle extends SavegameScoped {

    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    /** VILLAGE, FARM, MARKET, OFFICIAL, CLASSIFIEDS. */
    @Column(name = "section", nullable = false, length = 32)
    private String section;

    @Column(name = "position", nullable = false)
    private int position;

    /** Null until the narration job wrote the text. */
    @Column(name = "headline", length = 255)
    private String headline;

    @Lob
    @Column(name = "body")
    private String body;

    @Lob
    @Column(name = "facts_json")
    private String factsJson;

    @Column(name = "fallback", nullable = false)
    private boolean fallback;
}
