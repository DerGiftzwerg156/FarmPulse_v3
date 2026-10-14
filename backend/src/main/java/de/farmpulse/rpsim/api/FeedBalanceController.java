package de.farmpulse.rpsim.api;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.feed.FeedBalanceService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V4 section F: the tab "Futter" of the app "Stall" and the home tile (owner decision 2026-10-13) - need, stock,
 * reach and traffic light per animal type and food group, and the stock the player enters (bales, pallets, other).
 */
@RestController
public class FeedBalanceController {

    /** F2: bales of a size from {@code bale-sizes} (bales + baleSize) or litres directly. */
    public record ManualRequest(@NotBlank String fillType, Integer bales, String baleSize, Double liters, String note) {
    }

    private final SavegameContext context;
    private final FeedBalanceService feed;

    public FeedBalanceController(SavegameContext context, FeedBalanceService feed) {
        this.context = context;
        this.feed = feed;
    }

    @GetMapping("/api/feed-balance")
    @Transactional(readOnly = true)
    public FeedBalanceService.Balance view() {
        return feed.balance(context.requireActive());
    }

    @PostMapping("/api/feed-balance/manual")
    @Transactional
    public FeedBalanceService.Balance add(@Valid @RequestBody ManualRequest r) {
        Savegame sg = context.requireActive();
        feed.addManual(sg, r.fillType(), r.bales(), r.baleSize(), r.liters(), r.note());
        return feed.balance(sg);
    }

    @PutMapping("/api/feed-balance/manual/{id}")
    @Transactional
    public FeedBalanceService.Balance update(@PathVariable long id, @Valid @RequestBody ManualRequest r) {
        Savegame sg = context.requireActive();
        feed.updateManual(sg, id, r.fillType(), r.bales(), r.baleSize(), r.liters(), r.note());
        return feed.balance(sg);
    }

    /** F2: "stimmt noch" - the reminder "bitte prüfen" starts again. */
    @PostMapping("/api/feed-balance/manual/{id}/check")
    @Transactional
    public FeedBalanceService.Balance check(@PathVariable long id) {
        Savegame sg = context.requireActive();
        feed.checkManual(sg, id);
        return feed.balance(sg);
    }

    @DeleteMapping("/api/feed-balance/manual/{id}")
    @Transactional
    public FeedBalanceService.Balance remove(@PathVariable long id) {
        Savegame sg = context.requireActive();
        feed.removeManual(sg, id);
        return feed.balance(sg);
    }
}
