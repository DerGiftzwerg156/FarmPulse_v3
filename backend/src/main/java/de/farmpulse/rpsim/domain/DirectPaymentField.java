package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.1 R31-B1: one field of an area payment application - the declared crop (or BRACHE) and, after an
 * on-site check, the main crop found in the crop history and whether the field repeats the crop of the year before.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "direct_payment_field")
public class DirectPaymentField extends SavegameScoped {

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Column(name = "field_name", length = 255)
    private String fieldName;

    @Column(name = "hectares", nullable = false)
    private double hectares;

    @Column(name = "declared_crop", nullable = false, length = 64)
    private String declaredCrop;

    @Column(name = "actual_crop", length = 64)
    private String actualCrop;

    @Column(name = "rotation_repeat", nullable = false)
    private boolean rotationRepeat;
}
