package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.ImportJob;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface ImportJobRepository extends CrudRepository<ImportJob, Long> {

    List<ImportJob> findAllOrderByCreatedAtDesc();
}
