package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.CourseResource;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface CourseResourceRepository extends CrudRepository<CourseResource, Long> {

    List<CourseResource> findByCourseIdOrderByIdAsc(Long courseId);
}
