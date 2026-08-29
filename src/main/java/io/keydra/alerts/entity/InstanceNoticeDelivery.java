package io.keydra.alerts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One place Keydra's own troubles are announced.
 *
 * <p>The same destinations the alert rules use, chosen from the same page: a second list of
 * channels would be a second place to rotate a token.
 *
 * <p>Cascades with the delivery, unlike a rule's. A rule pointing at a channel is refused while it
 * points at it, because a rule that quietly started firing into nothing is a rule that stopped
 * working; this is a list of who to tell, and removing a channel from Keydra's estate is removing
 * it from this list too.
 */
@Entity
@Table(name = "instance_notice_delivery")
public class InstanceNoticeDelivery {

    @Id
    @Column(name = "delivery_id", nullable = false)
    public Long deliveryId;
}
