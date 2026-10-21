package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.Setting;
import io.micronaut.data.repository.CrudRepository;

@Repository
public interface SettingRepository extends CrudRepository<Setting, String> {
}
