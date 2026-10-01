package de.farmpulse.rpsim.contract;

import de.farmpulse.rpsim.bypass.VanillaBypassService;
import de.farmpulse.rpsim.club.ClubService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.credit.AnnualReviewService;
import de.farmpulse.rpsim.credit.CollateralService;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.neighbor.NeighborMissionService;
import de.farmpulse.rpsim.neighbor.NeighborTradeService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.tax.TaxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The player's decisions on contracts and service cases, dispatched to the service of the contract / case kind. Used by
 * the buttons in the browser and - Roadmap V2 R2-F2 - by the answers given in the game, so there is no second business
 * logic.
 */
@Service
public class ContractActions {

    private final ContractRepository contracts;
    private final ServiceCaseRepository cases;
    private final InsuranceService insurance;
    private final HuntingService hunting;
    private final LivestockService livestock;
    private final LeaseService lease;
    private final MaintenanceService maintenance;
    private final VanillaBypassService bypass;
    private final TaxService tax;
    private final ClubService clubs;
    private final NeighborTradeService trade;
    private final NeighborMissionService neighborMissions;
    private final CollateralService collateral;
    private final AnnualReviewService annualReview;
    private final de.farmpulse.rpsim.neighbor.FarmShopService farmShop;
    private final de.farmpulse.rpsim.drought.DroughtService drought;

    public ContractActions(ContractRepository contracts, ServiceCaseRepository cases, InsuranceService insurance,
                           HuntingService hunting, LivestockService livestock, LeaseService lease,
                           MaintenanceService maintenance, VanillaBypassService bypass, TaxService tax, ClubService clubs,
                           NeighborTradeService trade, NeighborMissionService neighborMissions,
                           CollateralService collateral, AnnualReviewService annualReview,
                           de.farmpulse.rpsim.neighbor.FarmShopService farmShop,
                           de.farmpulse.rpsim.drought.DroughtService drought) {
        this.farmShop = farmShop;
        this.drought = drought;
        this.trade = trade;
        this.collateral = collateral;
        this.annualReview = annualReview;
        this.neighborMissions = neighborMissions;
        this.contracts = contracts;
        this.cases = cases;
        this.insurance = insurance;
        this.hunting = hunting;
        this.livestock = livestock;
        this.lease = lease;
        this.maintenance = maintenance;
        this.bypass = bypass;
        this.tax = tax;
        this.clubs = clubs;
    }

    @Transactional
    public Contract accept(Savegame sg, Long id) {
        return switch (contract(sg, id).getKind()) {
            case INSURANCE -> insurance.accept(sg, id);
            case LEASE -> lease.accept(sg, id);
            case MAINTENANCE -> maintenance.accept(sg, id);
            case TAX_ADVISOR -> tax.acceptAdvisor(sg, id);
            default -> throw unsupported();
        };
    }

    @Transactional
    public Contract decline(Savegame sg, Long id) {
        return switch (contract(sg, id).getKind()) {
            case INSURANCE -> insurance.decline(sg, id);
            case LEASE -> lease.decline(sg, id);
            case MAINTENANCE -> maintenance.decline(sg, id);
            case TAX_ADVISOR -> tax.declineAdvisor(sg, id);
            default -> throw unsupported();
        };
    }

    @Transactional
    public Contract cancel(Savegame sg, Long id) {
        return switch (contract(sg, id).getKind()) {
            case INSURANCE -> insurance.cancel(sg, id);
            case LEASE -> lease.cancel(sg, id);
            case MAINTENANCE -> maintenance.cancel(sg, id);
            case TAX_ADVISOR -> tax.cancelAdvisor(sg, id);
            default -> throw unsupported();
        };
    }

    /** TODO T-22: renew a lease at the rent offered one month before the end. */
    @Transactional
    public Contract renew(Savegame sg, Long id) {
        return switch (contract(sg, id).getKind()) {
            case LEASE -> lease.renew(sg, id);
            default -> throw unsupported();
        };
    }

    /** TODO T-22: buy the leased field at the owner's offer. */
    @Transactional
    public Contract buy(Savegame sg, Long id) {
        return switch (contract(sg, id).getKind()) {
            case LEASE -> lease.buy(sg, id);
            default -> throw unsupported();
        };
    }

    @Transactional
    public ServiceCase acceptCase(Savegame sg, Long id) {
        return switch (serviceCase(sg, id).getKind()) {
            case WILDLIFE_DAMAGE -> hunting.accept(sg, id);
            case LIVESTOCK_OFFER -> livestock.accept(sg, id);
            case COMPENSATION_CLAIM -> bypass.pay(sg, id); // R2-D2: pay the compensation
            case TAX_BILL -> tax.pay(sg, id); // R2-E1: pay by button
            case INVITATION -> clubs.acceptInvitation(sg, id); // R2-E4: RSVP
            case GOODS_OFFER, GOODS_REQUEST -> trade.accept(sg, id); // R3-H3 / R3-H4
            case NEIGHBOR_MISSION -> neighborMissions.accept(sg, id); // R3-H5
            case COLLATERAL_CLAIM -> collateral.payClaim(sg, id); // R3-K1: pay the claimed Sondertilgung
            case ANNUAL_REVIEW -> annualReview.attend(sg, id); // R3-K3: "Termin wahrnehmen"
            case ANNUAL_REVIEW_OFFER -> annualReview.acceptOffer(sg, id); // R3-K3: accept the rate cut
            case FARM_SHOP_ORDER -> farmShop.accept(sg, id); // R3-M3: deliver
            case DROUGHT_AID -> drought.apply(sg, id); // R3-W2: "Antrag stellen"
            default -> throw unsupported();
        };
    }

    @Transactional
    public ServiceCase counterCase(Savegame sg, Long id, long amount) {
        return switch (serviceCase(sg, id).getKind()) {
            case WILDLIFE_DAMAGE -> hunting.counter(sg, id, amount);
            default -> throw unsupported();
        };
    }

    @Transactional
    public ServiceCase measureCase(Savegame sg, Long id) {
        return switch (serviceCase(sg, id).getKind()) {
            case WILDLIFE_DAMAGE -> hunting.measure(sg, id);
            default -> throw unsupported();
        };
    }

    @Transactional
    public ServiceCase declineCase(Savegame sg, Long id) {
        return switch (serviceCase(sg, id).getKind()) {
            case WILDLIFE_DAMAGE -> hunting.decline(sg, id);
            case LIVESTOCK_OFFER -> livestock.decline(sg, id);
            case COMPENSATION_CLAIM -> bypass.decline(sg, id);
            case INVITATION -> clubs.declineInvitation(sg, id);
            case SPONSORING_REQUEST -> clubs.declineSponsoring(sg, id);
            case GOODS_OFFER, GOODS_REQUEST -> trade.decline(sg, id); // R3-H3 / R3-H4
            case NEIGHBOR_MISSION -> neighborMissions.decline(sg, id); // R3-H5
            case ANNUAL_REVIEW -> annualReview.decline(sg, id); // R3-K3: without consequence
            case ANNUAL_REVIEW_OFFER -> annualReview.declineOffer(sg, id);
            case FARM_SHOP_ORDER -> farmShop.decline(sg, id); // R3-M3
            default -> throw unsupported();
        };
    }

    public ServiceCase serviceCase(Savegame sg, Long id) {
        return cases.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
    }

    public Contract contract(Savegame sg, Long id) {
        return contracts.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("contract " + id));
    }

    private static BusinessRuleException unsupported() {
        return new BusinessRuleException("UNSUPPORTED_ACTION", "Diese Aktion ist für diesen Vertrag nicht möglich.");
    }
}
