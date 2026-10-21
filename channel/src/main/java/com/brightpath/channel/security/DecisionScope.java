package com.brightpath.channel.security;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

/** One course scope granted by the decision tier (scopeRole: STUDENT, INSTRUCTOR or TA). */
@Serdeable
@Introspected
public record DecisionScope(Long courseId, String scopeRole) {
}
