package com.brightpath.batch.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.batch.domain.Setting;
import io.micronaut.data.repository.CrudRepository;

@Repository
public interface SettingRepository extends CrudRepository<Setting, String> {
}
