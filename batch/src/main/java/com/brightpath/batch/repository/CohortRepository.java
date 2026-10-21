package com.brightpath.batch.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.batch.domain.Cohort;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface CohortRepository extends CrudRepository<Cohort, Long> {

    List<Cohort> findByCourseId(Long courseId);
}
