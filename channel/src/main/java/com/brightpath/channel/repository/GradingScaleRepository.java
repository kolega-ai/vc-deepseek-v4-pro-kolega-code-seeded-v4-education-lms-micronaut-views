package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.GradingScale;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface GradingScaleRepository extends CrudRepository<GradingScale, Long> {

    List<GradingScale> findAllOrderByMinPercentDesc();
}
