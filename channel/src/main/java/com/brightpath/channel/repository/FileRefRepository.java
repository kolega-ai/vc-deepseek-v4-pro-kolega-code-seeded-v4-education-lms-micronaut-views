package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.FileRef;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface FileRefRepository extends CrudRepository<FileRef, Long> {

    List<FileRef> findBySubmissionId(Long submissionId);

    List<FileRef> findByOwnerId(Long ownerId);
}
