package com.brightpath.channel.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brightpath.channel.security.AppException;

import java.math.BigDecimal;
import java.util.List;

/** Parses/validates rubric JSON; the rubric lives as JSONB on the assignment. */
public final class RubricSupport {

    public record RubricCriterion(String key, String name, BigDecimal points, String description) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RubricSupport() {
    }

    public static List<RubricCriterion> parse(String json) throws RubricParseException {
        try {
            List<RubricCriterion> criteria = MAPPER.readValue(json, new TypeReference<>() {
            });
            for (int i = 0; i < criteria.size(); i++) {
                if (criteria.get(i).key() == null || criteria.get(i).key().isBlank()) {
                    throw new RubricParseException("criterion " + (i + 1) + " is missing its key");
                }
            }
            return criteria;
        } catch (RubricParseException e) {
            throw e;
        } catch (Exception e) {
            throw new RubricParseException(e.getMessage());
        }
    }

    public static final class RubricParseException extends Exception {
        public RubricParseException(String message) {
            super(message);
        }
    }
}
