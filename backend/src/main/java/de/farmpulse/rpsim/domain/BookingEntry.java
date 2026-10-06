package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Booking statement ("Kontoauszug", owner decisions 2026-10-06): one entry of {@code farm_facts.bookings} - a single
 * booking (purchase / sale of vehicles, buildings, fields, every tool booking) or the sum of a game day per money type
 * (sales also per fill type and sell point). {@code seq} is the running number of the mod.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "booking_entry")
public class BookingEntry extends SavegameScoped {

    /** Shop vehicle purchase / sale waiting for the vehicle to appear / disappear in the export. */
    public static final String MATCH_PENDING = "PENDING";
    /** Exactly one waiting purchase / sale: the names belong to it. */
    public static final String MATCH_MATCHED = "MATCHED";
    /** Several purchases / sales waited at the same time: the names are shown, but not assigned. */
    public static final String MATCH_AMBIGUOUS = "AMBIGUOUS";
    /** No vehicle appeared / disappeared within the window. */
    public static final String MATCH_NONE = "NONE";

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "booking_year", nullable = false)
    private int year;

    @Column(name = "period", nullable = false)
    private int period;

    @Column(name = "day_in_period")
    private Integer dayInPeriod;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "booking_count", nullable = false)
    private int count = 1;

    @Column(name = "single_entry", nullable = false)
    private boolean single;

    @Column(name = "liters")
    private Long liters;

    @Column(name = "fill_type", length = 64)
    private String fillType;

    @Column(name = "sell_point", length = 128)
    private String sellPoint;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "vehicle_match", length = 16)
    private String vehicleMatch;

    @Column(name = "vehicle_names", length = 1000)
    private String vehicleNames;

    @Column(name = "match_exports", nullable = false)
    private int matchExports;
}
