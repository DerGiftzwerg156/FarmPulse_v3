package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.2 R32-I3: one consideration of an investor package. Which fields are used depends on the type:
 * <ul>
 *   <li>W1 {@code quantity} = litres over the term, {@code minPerYear} = minimum per FS25 year, {@code deliveredTotal};
 *   W2 / W3 {@code quantity} = litres per month (W3 = a milk sort of a stable); A1 {@code quantity} = animals of
 *   {@code subType} per year; P2 {@code quantity} = litres per year at {@code unitPrice} (EUR per 1000 l).</li>
 *   <li>R1 / R2 {@code rate} = share of the operating result / of the amount per FS25 year.</li>
 *   <li>A2 minimum health, A3 {@code hectares} of {@code fillType} (fruit type) per year, A4 {@code target} area or
 *   animals ({@code targetKind}) by the end of the first term year, P1 {@code consents} = farmland ids the investor
 *   agreed to sell (comma separated).</li>
 * </ul>
 * {@code unitPrice} is the market price (EUR per 1000 l) or the game value of one animal the package was valued with.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "investor_obligation")
public class InvestorObligation extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id")
    private InvestorContract contract;

    @Column(name = "type", nullable = false, length = 4)
    private String type;

    @Column(name = "main", nullable = false)
    private boolean main;

    @Column(name = "fill_type", length = 64)
    private String fillType;

    @Column(name = "sub_type", length = 64)
    private String subType;

    @Column(name = "quantity")
    private Long quantity;

    @Column(name = "min_per_year")
    private Long minPerYear;

    @Column(name = "hectares")
    private Double hectares;

    @Column(name = "rate")
    private Double rate;

    @Column(name = "target")
    private Double target;

    @Column(name = "target_kind", length = 16)
    private String targetKind;

    @Column(name = "unit_price")
    private Double unitPrice;

    @Column(name = "value_per_year", nullable = false)
    private long valuePerYear;

    @Column(name = "total_value", nullable = false)
    private long totalValue;

    @Column(name = "delivered_total", nullable = false)
    private long deliveredTotal;

    @Column(name = "consents", length = 1000)
    private String consents;

    @Column(name = "fulfilled", nullable = false)
    private boolean fulfilled;
}
