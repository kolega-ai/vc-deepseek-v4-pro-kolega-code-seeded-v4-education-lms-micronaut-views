package com.brightpath.coremock.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.coremock.domain.WebhookReceived;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface WebhookReceivedRepository extends CrudRepository<WebhookReceived, Long> {

    List<WebhookReceived> findAllOrderByReceivedAtDesc();
}
