package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.FarmOrigin;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.Training;
import de.farmpulse.rpsim.domain.VillageRelation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request DTOs. Form fields that decide real numbers (credit, job posting, bids) are typed numbers with Bean
 * Validation - no endpoint ever extracts a number from free text.
 */
public final class Requests {

    private Requests() {
    }

    public record OnboardingRequest(FarmOrigin farmOrigin, VillageRelation villageRelation,
                                    @Size(max = 2000) String freeText,
                                    @NotNull @PositiveOrZero Long startingCapitalTarget,
                                    @PositiveOrZero Long legacyLoanAmount, TonePreset tonePreset,
                                    @Size(max = 10) List<@NotNull JobRole> initialEmployees,
                                    Boolean familyParents, Boolean familyPartner, Boolean familyChildren,
                                    @Size(max = 60) String farmName) {
    }

    public record RerollRequest(Long characterId) {
    }

    /** TODO T-20: insurance tariff to be offered (BASIC / COMFORT). */
    public record InsuranceOfferRequest(@NotBlank String level) {
    }

    /** Channel of a report or answer (MAIL / CALL). */
    public record ChannelRequest(Channel channel) {
    }

    /** Resolution of a dashboard notice: RESEND / KEEP (rewind decision) or DISMISS. */
    public record NoticeActionRequest(@NotBlank String action) {
    }

    public record ConfirmRequest(@NotBlank String savegameId) {
    }

    public record TextRequest(@NotBlank @Size(max = 4000) String text) {
    }

    public record ProactiveRequest(@NotBlank @Size(max = 4000) String text, Channel channel) {
    }

    /** Roadmap V3 R3-K1: {@code farmlandIds} = own fields offered as collateral (optional). */
    public record CreditApplicationRequest(@NotNull @Positive Long amount, @NotBlank @Size(max = 200) String purpose,
                                           @NotNull @Min(1) @Max(600) Integer termMonths,
                                           @Size(max = 50) List<Integer> farmlandIds) {
    }

    public record DeferralRequest(@Size(max = 4000) String message) {
    }

    /** Sondertilgung: amount that reduces the remaining debt (fee and pro-rata interest come on top). */
    public record SpecialRepaymentRequest(@NotNull @Positive Long amount) {
    }

    public record JobPostingRequest(@NotNull JobRole jobRole) {
    }

    public record InterviewRequest(@NotBlank @Size(max = 4000) String question, Channel channel) {
    }

    public record RaiseRequest(@NotNull @Positive Long newSalary) {
    }

    public record TimeOffRequest(@NotNull @Min(1) @Max(30) Integer days) {
    }

    /** "Schulungen": the training to book for a machine operator. */
    public record TrainingRequest(@NotNull Training training) {
    }

    public record SellOfferRequest(@NotNull @Positive Long askingPrice) {
    }

    /** Roadmap V3 R3-L1: lease-out form - term in FS25 years, desired rent in € per ha and month. */
    public record LeaseOutRequest(@NotNull @Positive Integer termYears, @NotNull @Positive Long desiredRate) {
    }

    public record OfferRequest(@NotNull @Positive Long amount) {
    }

    public record DirectNegotiationRequest(@NotNull Long characterId, @NotNull Integer farmlandId) {
    }

    public record ParticipationRequest(@NotNull Boolean participate) {
    }

    public record DiaryNoteRequest(@NotBlank @Size(max = 200) String title, @Size(max = 10000) String text) {
    }

    public record AiSettingsRequest(@NotBlank String provider, String model, String apiKey, String baseUrl) {
    }

    /** Roadmap V2 R2-A1 / R2-A3: helperWageMode EMPLOYEES or VANILLA. */
    public record HelperSettingsRequest(@NotBlank @Pattern(regexp = "EMPLOYEES|VANILLA")
                                        String helperWageMode, boolean strictHelperLimit) {
    }

    /** Roadmap V2 R2-D: switch of the reactions to the vanilla loan and the game's field menu. */
    public record BypassSettingsRequest(boolean reactionsEnabled) {
    }

    /** Roadmap V2 R2-C6: switch of the field work hints. */
    /** Roadmap V3.1 R31-B: switches of the burdening events. */
    public record BurdenSettingsRequest(boolean areaCheck, boolean fertilizer, boolean disease, boolean sickLeave,
                                        Boolean nightWork, Boolean cropDamage, Boolean dieselTheft) {
    }

    public record FieldSettingsRequest(boolean fieldHintsEnabled) {
    }

    /** Roadmap V3 R3-T2: empty = no farm name (the chronicle uses the map name). */
    public record FarmSettingsRequest(@Size(max = 60) String farmName) {
    }

    /** Roadmap V2 R2-F2: occasions asked in the game (PromptKind names; empty = none). */
    public record PromptSettingsRequest(@NotNull List<@NotBlank @Pattern(regexp = "CALL|CONTRACT_OFFER|WILDLIFE_OFFER"
            + "|CREDIT_COUNTER|INVITATION|COMPENSATION_CLAIM|TAX_BILL|TAX_ADVISOR") String> kinds) {
    }
}
