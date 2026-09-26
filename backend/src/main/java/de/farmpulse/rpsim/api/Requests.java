package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.FarmOrigin;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.VillageRelation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
                                    @Size(max = 10) List<@NotNull JobRole> initialEmployees) {
    }

    public record RerollRequest(Long characterId) {
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

    public record CreditApplicationRequest(@NotNull @Positive Long amount, @NotBlank @Size(max = 200) String purpose,
                                           @NotNull @Min(1) @Max(600) Integer termMonths) {
    }

    public record DeferralRequest(@Size(max = 4000) String message) {
    }

    public record JobPostingRequest(@NotNull JobRole jobRole) {
    }

    public record InterviewRequest(@NotBlank @Size(max = 4000) String question, Channel channel) {
    }

    public record RaiseRequest(@NotNull @Positive Long newSalary) {
    }

    public record TimeOffRequest(@NotNull @Min(1) @Max(30) Integer days) {
    }

    public record SellOfferRequest(@NotNull @Positive Long askingPrice) {
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
}
