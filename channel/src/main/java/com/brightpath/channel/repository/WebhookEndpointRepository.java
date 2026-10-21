package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.WebhookEndpoint;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface WebhookEndpointRepository extends CrudRepository<WebhookEndpoint, Long> {

    List<WebhookEndpoint> findByEnabled(boolean enabled);
}
