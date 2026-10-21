package com.brightpath.batch.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.batch.domain.EmailOutbox;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface EmailOutboxRepository extends CrudRepository<EmailOutbox, Long> {

    List<EmailOutbox> findByStatus(String status);
}
