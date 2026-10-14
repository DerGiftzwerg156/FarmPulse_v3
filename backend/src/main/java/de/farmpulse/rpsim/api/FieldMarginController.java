package de.farmpulse.rpsim.api;

import de.farmpulse.rpsim.domain.FieldMarginItem;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.field.FieldMarginService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V4 section D: the card "Deckungsbeitrag" in the app "Bank" (owner decision 2026-10-13) - the calculation per
 * field book entry, the ranking and the crops of the period of the field book evaluation, corrections and own lines.
 */
@RestController
public class FieldMarginController {

    /** D4: litres and / or amount of a line; both null give it back to the capture. Amounts are entered positive. */
    public record CorrectRequest(Double liters, Double amount) {
    }

    /** D4: an own line (EXTRA_COST, EXTRA_REVENUE or the replacement of an estimated SEED / FERTILIZER / LIME / PROTECTION). */
    public record AddRequest(@NotNull FieldMarginItem.Kind kind, String note, Double liters, @NotNull Double amount) {
    }

    /** D4: the price per litre of the product; null gives it back to the rule of D2. */
    public record PriceRequest(Double perLiter) {
    }

    /** D3: switch "Gemeinkosten verteilen". */
    public record OverheadsRequest(@NotNull Boolean enabled) {
    }

    private final SavegameContext context;
    private final FieldMarginService margins;
    private final de.farmpulse.rpsim.planning.CropPlanService cropPlans;

    public FieldMarginController(SavegameContext context, FieldMarginService margins,
                                 de.farmpulse.rpsim.planning.CropPlanService cropPlans) {
        this.context = context;
        this.margins = margins;
        this.cropPlans = cropPlans;
    }

    /** D5 (owner decision 2026-10-14): the card with the expected revenue of the running seasons with a plan. */
    private FieldMarginService.Margins card(Savegame sg) {
        return margins.margins(sg).withExpected(cropPlans.runningRevenue(sg));
    }

    @GetMapping("/api/field-margin")
    @Transactional(readOnly = true)
    public FieldMarginService.Margins view() {
        return card(context.requireActive());
    }

    @PostMapping("/api/field-margin/overheads")
    @Transactional
    public FieldMarginService.Margins overheads(@Valid @RequestBody OverheadsRequest r) {
        Savegame sg = context.requireActive();
        margins.setOverheads(sg, r.enabled());
        return card(sg);
    }

    @PostMapping("/api/field-margin/items/{id}")
    @Transactional
    public FieldMarginService.Margins correct(@PathVariable long id, @RequestBody CorrectRequest r) {
        Savegame sg = context.requireActive();
        margins.correct(sg, id, r.liters(), r.amount());
        return card(sg);
    }

    @DeleteMapping("/api/field-margin/items/{id}")
    @Transactional
    public FieldMarginService.Margins remove(@PathVariable long id) {
        Savegame sg = context.requireActive();
        margins.remove(sg, id);
        return card(sg);
    }

    @PostMapping("/api/field-margin/entries/{entryId}/items")
    @Transactional
    public FieldMarginService.Margins add(@PathVariable long entryId, @Valid @RequestBody AddRequest r) {
        Savegame sg = context.requireActive();
        margins.add(sg, entryId, r.kind(), r.note(), r.liters(), r.amount());
        return card(sg);
    }

    @PostMapping("/api/field-margin/entries/{entryId}/price")
    @Transactional
    public FieldMarginService.Margins price(@PathVariable long entryId, @RequestBody PriceRequest r) {
        Savegame sg = context.requireActive();
        margins.setPrice(sg, entryId, r.perLiter());
        return card(sg);
    }
}
