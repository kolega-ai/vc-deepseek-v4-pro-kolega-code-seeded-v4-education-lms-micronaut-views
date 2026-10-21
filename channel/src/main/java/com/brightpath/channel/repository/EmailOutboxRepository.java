package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.EmailOutbox;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface EmailOutboxRepository extends CrudRepository<EmailOutbox, Long> {

    List<EmailOutbox> findByStatus(String status);
}
