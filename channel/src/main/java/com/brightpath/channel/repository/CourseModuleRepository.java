package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.CourseModule;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface CourseModuleRepository extends CrudRepository<CourseModule, Long> {

    List<CourseModule> findByCourseIdOrderByPositionAsc(Long courseId);

    void deleteByCourseId(Long courseId);
}
