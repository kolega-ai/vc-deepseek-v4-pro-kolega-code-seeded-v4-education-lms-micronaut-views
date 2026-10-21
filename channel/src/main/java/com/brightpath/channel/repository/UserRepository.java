package com.brightpath.channel.repository;

import io.micronaut.data.annotation.Repository;
import com.brightpath.channel.domain.User;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends PageableRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    List<User> findByRole(String role);

    long countByRole(String role);

    @Query(value = """
            SELECT u FROM User u
            WHERE (:q IS NULL
                   OR lower(u.username) LIKE lower(concat('%', cast(:q as text), '%'))
                   OR lower(u.email) LIKE lower(concat('%', cast(:q as text), '%'))
                   OR lower(u.displayName) LIKE lower(concat('%', cast(:q as text), '%')))
              AND (:role IS NULL OR u.role = :role)
              AND (:enabled IS NULL OR u.enabled = :enabled)
            ORDER BY u.displayName
            """,
            countQuery = """
            SELECT count(u) FROM User u
            WHERE (:q IS NULL
                   OR lower(u.username) LIKE lower(concat('%', cast(:q as text), '%'))
                   OR lower(u.email) LIKE lower(concat('%', cast(:q as text), '%'))
                   OR lower(u.displayName) LIKE lower(concat('%', cast(:q as text), '%')))
              AND (:role IS NULL OR u.role = :role)
              AND (:enabled IS NULL OR u.enabled = :enabled)
            """)
    Page<User> search(@Nullable String q, @Nullable String role, @Nullable Boolean enabled, Pageable pageable);
}
