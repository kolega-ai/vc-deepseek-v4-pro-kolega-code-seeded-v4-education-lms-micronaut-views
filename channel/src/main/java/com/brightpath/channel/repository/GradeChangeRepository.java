package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.GradeChange;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface GradeChangeRepository extends CrudRepository<GradeChange, Long> {

    List<GradeChange> findBySubmissionIdOrderByCreatedAtDesc(Long submissionId);
}
