package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.Lesson;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface LessonRepository extends CrudRepository<Lesson, Long> {

    List<Lesson> findByModuleIdOrderByPositionAsc(Long moduleId);

    List<Lesson> findByCourseId(Long courseId);

    void deleteByModuleId(Long moduleId);
}
