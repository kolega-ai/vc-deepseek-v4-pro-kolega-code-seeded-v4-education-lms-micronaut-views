package com.brightpath.batch.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.batch.domain.Notification;
import io.micronaut.data.repository.CrudRepository;

@Repository
public interface NotificationRepository extends CrudRepository<Notification, Long> {
}
