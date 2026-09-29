package de.farmpulse.rpsim.tablet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import de.farmpulse.rpsim.api.ApiMapper;
import de.farmpulse.rpsim.api.Views.TaskView;
import de.farmpulse.rpsim.api.Views.TasksView;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.CreditApplicationStatus;
import de.farmpulse.rpsim.domain.CreditDecision;
import de.farmpulse.rpsim.domain.JobApplicationStatus;
import de.farmpulse.rpsim.domain.JobPostingStatus;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.PromptStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.CreditApplicationRepository;
import de.farmpulse.rpsim.repository.GamePromptRepository;
import de.farmpulse.rpsim.repository.JobApplicationRepository;
import de.farmpulse.rpsim.repository.JobPostingRepository;
import de.farmpulse.rpsim.repository.MarketEventRepository;
import de.farmpulse.rpsim.repository.NegotiationRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hof-Tablet app "Aufgaben": collects every open decision of all areas into one list - service cases waiting for the
 * player, contract offers, lease renewals, the bank's counter offer, ringing calls, open negotiations, market offers
 * and job postings with applicants. Read only: the actions stay on the existing endpoints of each area.
 */
@Service
public class TaskService {

    /** Cases that are information with a deadline rather than a decision (announced inspection). */
    private static final EnumSet<CaseKind> RUNNING_WITH_DEADLINE = EnumSet.of(CaseKind.AUTHORITY_INSPECTION);

    private final ServiceCaseRepository cases;
    private final ContractRepository contracts;
    private final CreditApplicationRepository applications;
    private final CommunicationRepository communications;
    private final NegotiationRepository negotiations;
    private final MarketEventRepository marketEvents;
    private final JobPostingRepository postings;
    private final JobApplicationRepository jobApplications;
    private final GamePromptRepository prompts;
    private final ApiMapper mapper;

    public TaskService(ServiceCaseRepository cases, ContractRepository contracts, CreditApplicationRepository applications,
                       CommunicationRepository communications, NegotiationRepository negotiations,
                       MarketEventRepository marketEvents, JobPostingRepository postings,
                       JobApplicationRepository jobApplications, GamePromptRepository prompts, ApiMapper mapper) {
        this.cases = cases;
        this.contracts = contracts;
        this.applications = applications;
        this.communications = communications;
        this.negotiations = negotiations;
        this.marketEvents = marketEvents;
        this.postings = postings;
        this.jobApplications = jobApplications;
        this.prompts = prompts;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public TasksView tasks(Savegame sg) {
        List<TaskView> items = new ArrayList<>();
        for (ServiceCase c : cases.findBySavegameOrderByIdDesc(sg)) {
            boolean awaiting = c.getStatus() == CaseStatus.AWAITING_PLAYER && !"NOT_REFERRED".equals(c.getResolution());
            boolean running = c.getStatus() == CaseStatus.IN_PROGRESS && RUNNING_WITH_DEADLINE.contains(c.getKind());
            if (awaiting || running) {
                items.add(task("case-" + c.getId(), "CASE", c.getKind().name(), c.getDeadlineGameTime(), c.getGameTime())
                        .serviceCase(mapper.serviceCase(c)).build());
            }
        }
        for (Contract c : contracts.findBySavegameOrderByIdDesc(sg)) {
            if (c.getStatus() == ContractStatus.OFFERED) {
                items.add(task("contract-" + c.getId(), "CONTRACT_OFFER", c.getKind().name(), c.getOfferExpiresAtGameTime(),
                        c.getStartedAtGameTime() == null ? 0 : c.getStartedAtGameTime())
                        .contract(mapper.contract(c)).build());
            } else if (c.getStatus() == ContractStatus.ACTIVE && c.getKind() == ContractKind.LEASE
                    && (c.getRenewalAmount() != null || c.getPurchasePrice() != null)) {
                items.add(task("lease-" + c.getId(), "LEASE_RENEWAL", c.getKind().name(), c.getEndsAtGameTime(),
                        c.getStartedAtGameTime() == null ? 0 : c.getStartedAtGameTime())
                        .contract(mapper.contract(c)).build());
            }
        }
        applications.findBySavegameAndStatus(sg, CreditApplicationStatus.DECIDED).stream()
                .filter(a -> a.getDecision() == CreditDecision.COUNTER_OFFER)
                .forEach(a -> items.add(task("credit-" + a.getId(), "CREDIT_COUNTER", "COUNTER_OFFER", null,
                        a.getDecisionVisibleAtGameTime()).application(mapper.application(a)).build()));
        communications.findBySavegameAndChannelAndCallStatus(sg, Channel.CALL, CallStatus.RINGING)
                .forEach(c -> items.add(task("call-" + c.getId(), "CALL", "RINGING", c.getRingDeadlineGameTime(),
                        c.getGameTime()).call(mapper.message(c)).build()));
        negotiations.findBySavegameAndStatus(sg, NegotiationStatus.OPEN)
                .forEach(n -> items.add(task("negotiation-" + n.getId(), "NEGOTIATION", n.getKind().name(),
                        n.getClosesAtGameTime(), 0).negotiation(mapper.negotiation(n)).build()));
        marketEvents.findBySavegameAndStatusIn(sg, EnumSet.of(MarketEventStatus.OFFERED))
                .forEach(e -> items.add(task("market-" + e.getId(), "MARKET_OFFER", e.getEventType().name(),
                        e.getDeadlineGameTime(), e.getStartGameTime()).marketEvent(mapper.marketEvent(e)).build()));
        postings.findBySavegameOrderByIdDesc(sg).stream().filter(p -> p.getStatus() == JobPostingStatus.OPEN).forEach(p -> {
            int pending = (int) jobApplications.findByPostingOrderByIdAsc(p).stream()
                    .filter(a -> a.getStatus() == JobApplicationStatus.PENDING).count();
            if (pending > 0) {
                items.add(task("posting-" + p.getId(), "POSTING", p.getJobRole().name(), null, p.getCreatedAtGameTime())
                        .posting(mapper.posting(p)).pendingApplicants(pending).build());
            }
        });
        items.sort(Comparator.comparing((TaskView t) -> t.deadlineGameTime() == null ? Long.MAX_VALUE : t.deadlineGameTime())
                .thenComparing(TaskView::gameTime, Comparator.reverseOrder()));
        int waiting = prompts.findBySavegameAndStatusInAndExpiresGameTimeGreaterThanEqualOrderByIdAsc(sg,
                EnumSet.of(PromptStatus.OPEN), sg.getCurrentGameTime()).size();
        return new TasksView(items, waiting);
    }

    private static Builder task(String key, String type, String kind, Long deadline, long gameTime) {
        return new Builder(key, type, kind, deadline, gameTime);
    }

    /** Small builder so each source sets only its own payload. */
    private static final class Builder {
        private final String key;
        private final String type;
        private final String kind;
        private final Long deadline;
        private final long gameTime;
        private de.farmpulse.rpsim.api.Views.CaseView serviceCase;
        private de.farmpulse.rpsim.api.Views.ContractView contract;
        private de.farmpulse.rpsim.api.Views.CreditApplicationView application;
        private de.farmpulse.rpsim.api.Views.MessageView call;
        private de.farmpulse.rpsim.api.Views.NegotiationView negotiation;
        private de.farmpulse.rpsim.api.Views.MarketEventView marketEvent;
        private de.farmpulse.rpsim.api.Views.JobPostingView posting;
        private Integer pendingApplicants;

        private Builder(String key, String type, String kind, Long deadline, long gameTime) {
            this.key = key;
            this.type = type;
            this.kind = kind;
            this.deadline = deadline;
            this.gameTime = gameTime;
        }

        Builder serviceCase(de.farmpulse.rpsim.api.Views.CaseView v) {
            this.serviceCase = v;
            return this;
        }

        Builder contract(de.farmpulse.rpsim.api.Views.ContractView v) {
            this.contract = v;
            return this;
        }

        Builder application(de.farmpulse.rpsim.api.Views.CreditApplicationView v) {
            this.application = v;
            return this;
        }

        Builder call(de.farmpulse.rpsim.api.Views.MessageView v) {
            this.call = v;
            return this;
        }

        Builder negotiation(de.farmpulse.rpsim.api.Views.NegotiationView v) {
            this.negotiation = v;
            return this;
        }

        Builder marketEvent(de.farmpulse.rpsim.api.Views.MarketEventView v) {
            this.marketEvent = v;
            return this;
        }

        Builder posting(de.farmpulse.rpsim.api.Views.JobPostingView v) {
            this.posting = v;
            return this;
        }

        Builder pendingApplicants(int n) {
            this.pendingApplicants = n;
            return this;
        }

        TaskView build() {
            return new TaskView(key, type, kind, deadline, gameTime, serviceCase, contract, application, call, negotiation,
                    marketEvent, posting, pendingApplicants);
        }
    }
}
