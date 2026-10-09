package de.farmpulse.rpsim.investor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.InvestorContract;
import de.farmpulse.rpsim.domain.InvestorDelivery;
import de.farmpulse.rpsim.domain.InvestorObligation;
import de.farmpulse.rpsim.domain.InvestorPayment;
import de.farmpulse.rpsim.domain.InvestorPeriod;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.InvestorContractRepository;
import de.farmpulse.rpsim.repository.InvestorDeliveryRepository;
import de.farmpulse.rpsim.repository.InvestorObligationRepository;
import de.farmpulse.rpsim.repository.InvestorPaymentRepository;
import de.farmpulse.rpsim.repository.InvestorPeriodRepository;
import org.springframework.stereotype.Component;

/**
 * Roadmap V3.2 R32-I: read-only views of the investor contracts for other areas, from the repositories only (no
 * service dependency, so the bank, the farm report, the farm holidays and the field sale can use it without a cycle).
 * <ul>
 *   <li>I6 bank view (owner decision 2026-10-08): while a silent partnership runs, its amount counts as asset (paid-in
 *   equity); a subordinated loan counts as asset and with {@code subordinated-debt-factor} as debt. An unpaid
 *   repayment (claim) counts as asset and debt, open compensations and payouts as debt.</li>
 *   <li>P1 veto: the sale of an own field in the tool needs the investor's consent first.</li>
 *   <li>P3 holiday flat: the periods kept free for the investor within the term.</li>
 *   <li>I6 farm report: section "Investoren" of an FS25 year.</li>
 * </ul>
 */
@Component
public class InvestorLedger {

    private final InvestorContractRepository contracts;
    private final InvestorObligationRepository obligations;
    private final InvestorPeriodRepository periods;
    private final InvestorDeliveryRepository deliveries;
    private final InvestorPaymentRepository payments;
    private final RpsimProperties props;

    public InvestorLedger(InvestorContractRepository contracts, InvestorObligationRepository obligations,
                          InvestorPeriodRepository periods, InvestorDeliveryRepository deliveries,
                          InvestorPaymentRepository payments, RpsimProperties props) {
        this.contracts = contracts;
        this.obligations = obligations;
        this.periods = periods;
        this.deliveries = deliveries;
        this.payments = payments;
        this.props = props;
    }

    private RpsimProperties.Investor cfg() {
        return props.getFormulas().getInvestor();
    }

    public List<InvestorContract> active(Savegame sg) {
        return contracts.findBySavegameAndStatusOrderByIdAsc(sg, InvestorContract.ACTIVE);
    }

    /**
     * True while the capital of an active contract is in the farm. An extension takes over in the predecessor's last
     * month, where the predecessor would have been repaid (no money flows).
     */
    boolean capitalIn(Savegame sg, InvestorContract c) {
        if (!InvestorContract.ACTIVE.equals(c.getStatus()) || c.isRepaymentDue()) {
            return false;
        }
        return c.getExtensionOf() == null || contracts.findById(c.getExtensionOf())
                .map(p -> !InvestorContract.ACTIVE.equals(p.getStatus()) || p.isRepaymentDue()).orElse(true);
    }

    /** I6: assets the bank view adds (EUR). */
    public double bankAssets(Savegame sg) {
        double sum = 0;
        for (InvestorContract c : active(sg)) {
            if (capitalIn(sg, c)) {
                sum += c.getAmount();
            }
        }
        for (InvestorPayment p : payments.findBySavegameOrderByIdAsc(sg)) {
            if (InvestorPayment.REPAYMENT.equals(p.getKind()) && unpaid(p)) {
                sum += p.getAmount();
            }
        }
        return sum;
    }

    /** I6: debt the bank view adds (EUR). */
    public double bankDebt(Savegame sg) {
        double sum = 0;
        for (InvestorContract c : active(sg)) {
            if (capitalIn(sg, c) && !c.silent()) {
                sum += c.getAmount() * cfg().getSubordinatedDebtFactor();
            }
        }
        for (InvestorPayment p : payments.findBySavegameOrderByIdAsc(sg)) {
            if (unpaid(p)) {
                sum += p.getAmount();
            }
        }
        return sum;
    }

    static boolean unpaid(InvestorPayment p) {
        return InvestorPayment.OPEN.equals(p.getStatus()) || InvestorPayment.CLAIMED.equals(p.getStatus());
    }

    /** Open and claimed payments to investors (EUR). */
    public long unpaidTotal(Savegame sg) {
        return payments.findBySavegameOrderByIdAsc(sg).stream().filter(InvestorLedger::unpaid)
                .mapToLong(InvestorPayment::getAmount).sum();
    }

    public String kindLabel(String kindKey) {
        RpsimProperties.Investor.Kind k = cfg().getKinds().get(kindKey);
        return k == null || k.getLabel() == null ? kindKey : k.getLabel();
    }

    /** I6 annual review: the advisor names the investors who joined in the year and open claims (German text). */
    public String reviewNote(Savegame sg, List<ReportLine> lines) {
        List<String> parts = new ArrayList<>();
        if (lines != null) {
            for (ReportLine l : lines) {
                if (l.capital() > 0) {
                    parts.add("Neu eingestiegen ist " + kindLabel(l.kind()) + " mit "
                            + java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY).format(l.capital())
                            + " € (" + (InvestorContract.SILENT.equals(l.capitalType())
                            ? "stille Beteiligung, für uns Eigenkapital" : "Nachrangdarlehen, für uns eine Schuld") + ").");
                }
            }
        }
        long unpaid = unpaidTotal(sg);
        if (unpaid > 0) {
            parts.add("Offen sind Forderungen von Investoren über "
                    + java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY).format(unpaid) + " €.");
        }
        return parts.isEmpty() ? "" : String.join(" ", parts) + " "; // ends with a blank (fallback template)
    }

    // ------------------------------------------------------------------------------------------ P1 veto

    /** P1: active contracts with a veto on field sales that did not agree to the sale of this field yet. */
    public List<InvestorObligation> vetoes(Savegame sg, int farmlandId) {
        List<InvestorObligation> out = new ArrayList<>();
        for (InvestorContract c : active(sg)) {
            for (InvestorObligation o : obligations.findByContractOrderByIdAsc(c)) {
                if (InvestorConsideration.P1.name().equals(o.getType()) && !consented(o, farmlandId)) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    static boolean consented(InvestorObligation o, int farmlandId) {
        return o.getConsents() != null && Arrays.stream(o.getConsents().split(","))
                .anyMatch(s -> s.strip().equals(String.valueOf(farmlandId)));
    }

    /** Called before a sale offer of an own field (like the bank's Grundschuld, R3-K1). */
    public void requireSellable(Savegame sg, int farmlandId) {
        if (!vetoes(sg, farmlandId).isEmpty()) {
            throw new BusinessRuleException("FIELD_INVESTOR_VETO", "Ein Investor hat ein Vetorecht beim Feldverkauf. "
                    + "Bitte zuerst in der App „Bank“ unter „Investoren“ seine Zustimmung für Feld " + farmlandId
                    + " einholen.");
        }
    }

    // ------------------------------------------------------------------------------------------ P3 holiday flat

    /** P3: the holiday flat is kept for an investor in the month (FS25 period) with this month index. */
    public boolean holidayFlatReserved(Savegame sg, long monthIndex, int period) {
        if (!cfg().getHolidayPeriods().contains(period)) {
            return false;
        }
        for (InvestorContract c : active(sg)) {
            if (monthIndex < c.getStartMonthIndex() || monthIndex > c.getEndMonthIndex()) {
                continue;
            }
            if (obligations.findByContractOrderByIdAsc(c).stream()
                    .anyMatch(o -> InvestorConsideration.P3.name().equals(o.getType()))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------ farm report

    /** One delivered consideration of a year (goods and milk in litres, animals in head). */
    public record DeliveredLine(String type, String what, long quantity) {
    }

    /** I6 farm report: one investor of the year. */
    public record ReportLine(Long contractId, String investor, String kind, long amount, String capitalType,
                             String status, List<DeliveredLine> delivered, long capital, long payouts,
                             long compensations, long repayments, int breaches) {
    }

    /** Contracts that ran in the FS25 year (accepted before its end, not ended before it). */
    public List<ReportLine> reportLines(Savegame sg, int year) {
        List<ReportLine> out = new ArrayList<>();
        for (InvestorContract c : contracts.findBySavegameOrderByIdDesc(sg)) {
            if (c.getAcceptedGameTime() == null) {
                continue;
            }
            List<InvestorPayment> ps = payments.findByContractOrderByIdAsc(c).stream()
                    .filter(p -> Objects.equals(p.getPaymentYear(), year)).toList();
            List<DeliveredLine> delivered = new ArrayList<>();
            int breaches = 0;
            for (InvestorObligation o : obligations.findByContractOrderByIdAsc(c)) {
                long q = deliveries.findByObligationOrderByIdAsc(o).stream()
                        .filter(d -> InvestorDelivery.DONE.equals(d.getStatus()) && Objects.equals(d.getDeliveryYear(), year))
                        .mapToLong(InvestorDelivery::getQuantity).sum();
                if (q > 0) {
                    delivered.add(new DeliveredLine(o.getType(), o.getSubType() != null ? o.getSubType() : o.getFillType(), q));
                }
                breaches += (int) periods.findByObligationOrderByPeriodKeyAsc(o).stream()
                        .filter(p -> p.isBreach() && p.getPeriodYear() == year).count();
            }
            boolean ran = c.getStartYear() <= year && c.endYear() >= year;
            if (!ran && ps.isEmpty() && delivered.isEmpty()) {
                continue;
            }
            out.add(new ReportLine(c.getId(), c.getCharacter() == null ? null : c.getCharacter().getName(), c.getKind(),
                    c.getAmount(), c.getCapitalType(), c.getStatus(), delivered, sum(ps, InvestorPayment.CAPITAL),
                    sum(ps, InvestorPayment.PAYOUT), sum(ps, InvestorPayment.COMPENSATION),
                    sum(ps, InvestorPayment.REPAYMENT), breaches));
        }
        return out;
    }

    private static long sum(List<InvestorPayment> ps, String kind) {
        return ps.stream().filter(p -> kind.equals(p.getKind()) && InvestorPayment.BOOKED.equals(p.getStatus()))
                .mapToLong(InvestorPayment::getAmount).sum();
    }

    /** Open periods with a running grace (used by the day check). */
    List<InvestorPeriod> reminded(Savegame sg) {
        return periods.findBySavegameAndStatusOrderByIdAsc(sg, InvestorPeriod.REMINDED);
    }
}
