package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.DiscussionComment;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@Repository
public interface DiscussionCommentRepository extends CrudRepository<DiscussionComment, Long> {

    List<DiscussionComment> findByThreadIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long threadId);

    long countByThreadIdAndDeletedAtIsNull(Long threadId);
}
