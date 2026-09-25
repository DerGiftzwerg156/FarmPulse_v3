package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationOffer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NegotiationOfferRepository extends JpaRepository<NegotiationOffer, Long> {

    List<NegotiationOffer> findByNegotiationOrderByIdAsc(Negotiation negotiation);
}
