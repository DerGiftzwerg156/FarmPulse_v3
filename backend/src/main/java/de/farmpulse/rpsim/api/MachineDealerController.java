package de.farmpulse.rpsim.api;

import de.farmpulse.rpsim.dealer.MachineDealerService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.savegame.SavegameContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap vehicle dealer RFH-H: the app "Händler". {@code GET} shows the dealer without side effect; {@code POST .../open}
 * is sent when the app is opened and creates the dealer of an older savegame (introduction by mail and diary).
 */
@RestController
public class MachineDealerController {

    /** The dealer as contact: reference and trust level (display abstraction, never the number). */
    public record DealerContactView(Views.CharacterRef character, String trustLevel) {
    }

    /** {@code dealer} = null while an older savegame has no dealer yet (before the first opening). */
    public record DealerView(DealerContactView dealer) {
    }

    private final SavegameContext context;
    private final MachineDealerService dealers;
    private final ApiMapper mapper;

    public MachineDealerController(SavegameContext context, MachineDealerService dealers, ApiMapper mapper) {
        this.context = context;
        this.dealers = dealers;
        this.mapper = mapper;
    }

    @GetMapping("/api/dealer")
    @Transactional(readOnly = true)
    public DealerView dealer() {
        Savegame sg = context.requireActive();
        return new DealerView(dealers.dealer(sg).map(this::contact).orElse(null));
    }

    @PostMapping("/api/dealer/open")
    @Transactional
    public DealerView open() {
        Savegame sg = context.requireActive();
        return new DealerView(contact(dealers.open(sg)));
    }

    private DealerContactView contact(Character c) {
        return new DealerContactView(mapper.ref(c), mapper.trustLevel(c));
    }
}
