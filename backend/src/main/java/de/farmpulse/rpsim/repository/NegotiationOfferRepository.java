package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.NegotiationOffer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NegotiationOfferRepository extends JpaRepository<NegotiationOffer, Long> {
}
