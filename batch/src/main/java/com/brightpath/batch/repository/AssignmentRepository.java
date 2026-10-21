package com.brightpath.batch.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.batch.domain.Assignment;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AssignmentRepository extends CrudRepository<Assignment, Long> {

    @Query("SELECT a FROM Assignment a WHERE a.status = :status AND a.dueAt < :cutoff")
    List<Assignment> findOverdue(String status, Instant cutoff);

    @Query("SELECT a FROM Assignment a WHERE a.status = :status AND a.dueAt > :now AND a.dueAt < :windowEnd")
    List<Assignment> findDueSoon(String status, Instant now, Instant windowEnd);
}
