package com.brightpath.batch.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.batch.domain.GradeSyncEvent;
import io.micronaut.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;

@Repository
public interface GradeSyncEventRepository extends CrudRepository<GradeSyncEvent, Long> {

    List<GradeSyncEvent> findByStatusIn(Collection<String> statuses);
}
