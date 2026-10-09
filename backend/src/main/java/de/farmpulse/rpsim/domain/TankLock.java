package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D8: a tank lock bought at the workshop for one own vehicle. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tank_lock")
public class TankLock extends SavegameScoped {

    @Column(name = "vehicle_id", nullable = false, length = 64)
    private String vehicleId;

    @Column(name = "bought_game_time", nullable = false)
    private long boughtGameTime;
}
