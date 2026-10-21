package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.ApiKey;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends CrudRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByPrefix(String prefix);

    List<ApiKey> findAllOrderByCreatedAtDesc();
}
