package de.farmpulse.rpsim.character;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.NegotiationTrait;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic identity generation (role, name, fact file, personality stub) - reused by onboarding, the applicant
 * pool and village rotation. Works offline; the AI may only enrich the backstory afterwards.
 */
@Service
public class CharacterGeneratorService {

    public record Pools(List<String> firstNames, List<String> lastNames, List<String> traits, List<String> speechStyles,
                        Map<String, Map<String, String>> roles, Map<String, String> jobRoles) {
    }

    private final CharacterRepository characters;
    private final TrustScoreService trust;
    private final RpsimProperties props;
    private final ObjectProvider<CharacterEnrichment> enrichment;
    private final Pools pools;

    public CharacterGeneratorService(CharacterRepository characters, TrustScoreService trust, RpsimProperties props,
                                     ObjectProvider<CharacterEnrichment> enrichment, JsonMapper json) throws IOException {
        this.characters = characters;
        this.trust = trust;
        this.props = props;
        this.enrichment = enrichment;
        try (InputStream in = new ClassPathResource("character-pools/" + props.getAi().getLocale() + ".json").getInputStream()) {
            this.pools = json.readValue(in, Pools.class);
        }
    }

    public Pools pools() {
        return pools;
    }

    /** Specification of a character to generate. */
    public record Spec(CharacterRole role, CharacterCategory category, double startTrust, JobRole jobRole) {
        public static Spec of(CharacterRole role, CharacterCategory category, double startTrust) {
            return new Spec(role, category, startTrust, null);
        }
    }

    /**
     * Generates and persists a character. The seed makes the identity reproducible (reroll = new seed).
     * An INITIAL trust event records the start trust so the trust log stays replayable.
     */
    @Transactional
    public Character generate(Savegame sg, Spec spec, long seed) {
        RandomSource r = RandomSource.seeded(seed);
        Set<String> used = new HashSet<>();
        characters.findBySavegameOrderByIdAsc(sg).forEach(c -> used.add(c.getName()));
        String name = null;
        for (int i = 0; i < 50 && (name == null || used.contains(name)); i++) {
            name = r.pick(pools.firstNames()) + " " + r.pick(pools.lastNames());
        }
        Character c = new Character();
        c.setSavegame(sg);
        c.setRole(spec.role());
        c.setCategory(spec.category());
        c.setStatus(CharacterStatus.ACTIVE);
        c.setName(name);
        List<String> traitPool = new ArrayList<>(pools.traits());
        java.util.Collections.shuffle(traitPool, new java.util.Random(seed));
        List<String> traits = traitPool.subList(0, r.intBetween(3, 5));
        c.setTraits(String.join(", ", traits));
        c.setSpeechStyle(r.pick(pools.speechStyles()));
        c.setNegotiationTrait(traits.contains("stur") ? NegotiationTrait.STUBBORN
                : traits.contains("diplomatisch") || traits.contains("großzügig") ? NegotiationTrait.NEGOTIABLE
                : NegotiationTrait.values()[r.intBetween(0, 2)]);
        RpsimProperties.Negotiation n = props.getFormulas().getNegotiation();
        if (spec.category() == CharacterCategory.DYNAMIC) {
            c.setVirtualWealth(Math.round(r.uniform(n.getVirtualWealthMin(), n.getVirtualWealthMax()) / 1000.0) * 1000);
            c.setSellWilling(r.chance(n.getSellWillingProbability()));
        }
        c.setGenerationSeed(seed);
        c.setJoinedAtGameTime(sg.getCurrentGameTime());
        c.setShortDescription(shortDescription(c, spec.jobRole()));
        c.setBackstory(c.getShortDescription());
        characters.save(c);
        if (spec.startTrust() != 0) {
            trust.recordEvent(c, spec.startTrust(), TrustReason.INITIAL, "Starttrust");
        }
        return c;
    }

    /** Deterministic short description - stays in place if AI enrichment is unavailable. */
    public String shortDescription(Character c, JobRole jobRole) {
        Map<String, String> role = pools.roles().get(c.getRole().name());
        String title = jobRole != null ? pools.jobRoles().get(jobRole.name()) : role.get("title");
        String traits = c.getTraits();
        return c.getName() + ", " + title + " – " + role.get("description") + ". Gilt als " + traits + ".";
    }

    /** Optional AI enrichment of the backstory; failures keep the deterministic text. */
    @Transactional
    public void enrich(Character c, String freeText) {
        CharacterEnrichment e = enrichment.getIfAvailable();
        if (e == null) {
            return;
        }
        try {
            Optional<String> story = e.enrichBackstory(c, freeText, c.getSavegame().getTonePreset());
            story.filter(s -> !s.isBlank()).ifPresent(s -> {
                c.setBackstory(s);
                c.setAiEnriched(true);
            });
        } catch (RuntimeException ex) {
            // deterministic short description remains
        }
    }

    public String jobRoleTitle(JobRole role) {
        return pools.jobRoles().get(role.name());
    }
}
