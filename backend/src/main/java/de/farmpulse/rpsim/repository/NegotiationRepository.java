package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.AssetType;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NegotiationRepository extends JpaRepository<Negotiation, Long> {

    boolean existsBySavegameAndAssetTypeAndAssetIdAndStatus(Savegame savegame, AssetType assetType, String assetId,
                                                            NegotiationStatus status);

    List<Negotiation> findBySavegameOrderByIdDesc(Savegame savegame);

    List<Negotiation> findBySavegameAndStatus(Savegame savegame, NegotiationStatus status);

    List<Negotiation> findBySaleGroupId(String saleGroupId);
}
