package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3 R3-W1: a declared drought (dry growth months {@code firstMonthIndex}..{@code lastMonthIndex}) with its
 * consequences: regional HARVEST_FAILURE events (W1), the payout of the weather-index insurance (W3) and the case of
 * the drought aid (W2).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "drought")
public class Drought extends SavegameScoped {

    /** Insurance outcome: paid / no drought insurance / concluded in or after the first dry month / premium overdue. */
    public static final String PAID = "PAID";
    public static final String NONE = "NONE";
    public static final String TOO_LATE = "TOO_LATE";
    public static final String COVER_SUSPENDED = "COVER_SUSPENDED";

    @Column(name = "first_month_index", nullable = false)
    private long firstMonthIndex;

    @Column(name = "last_month_index", nullable = false)
    private long lastMonthIndex;

    @Column(name = "declared_game_time", nullable = false)
    private long declaredGameTime;

    /** Fill types of the HARVEST_FAILURE events, comma separated (largest area first). */
    @Column(name = "crops", length = 255)
    private String crops;

    @Column(name = "price_events", nullable = false)
    private int priceEvents;

    @Column(name = "insurance_result", nullable = false, length = 32)
    private String insuranceResult;

    @Column(name = "insurance_contract_id")
    private Long insuranceContractId;

    @Column(name = "insured_hectares")
    private Double insuredHectares;

    @Column(name = "insurance_payout")
    private Long insurancePayout;

    @Column(name = "aid_hectares")
    private Double aidHectares;

    @Column(name = "aid_case_id")
    private Long aidCaseId;
}
