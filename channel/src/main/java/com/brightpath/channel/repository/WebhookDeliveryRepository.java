package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.WebhookDelivery;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

@Repository
public interface WebhookDeliveryRepository extends PageableRepository<WebhookDelivery, Long> {

    Page<WebhookDelivery> findByEndpointIdOrderByCreatedAtDesc(Long endpointId, Pageable pageable);
}
