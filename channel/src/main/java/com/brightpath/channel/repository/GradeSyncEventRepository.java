package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.GradeSyncEvent;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface GradeSyncEventRepository extends CrudRepository<GradeSyncEvent, Long> {

    List<GradeSyncEvent> findByStatus(String status);
}
