package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.SubmissionFile;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface SubmissionFileRepository extends CrudRepository<SubmissionFile, Long> {

    List<SubmissionFile> findBySubmissionId(Long submissionId);

    List<SubmissionFile> findByFileId(Long fileId);

    void deleteBySubmissionId(Long submissionId);
}
