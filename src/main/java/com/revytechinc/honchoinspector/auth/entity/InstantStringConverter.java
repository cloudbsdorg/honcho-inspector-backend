package com.revytechinc.honchoinspector.auth.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;

/**
 * Converts {@link Instant} ↔ ISO-8601 string for fields mapped to
 * TEXT columns on SQLite (which has no native TIMESTAMP type). Reads
 * parse via {@link Instant#parse} (ISO-8601); writes format via
 * {@link Instant#toString} which is the canonical UTC form.
 *
 * Auto-applied to any field of type {@code Instant} that declares
 * {@code @Convert(InstantStringConverter.class)}.
 */
@Converter(autoApply = false)
public class InstantStringConverter implements AttributeConverter<Instant, String> {
    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return attribute == null ? null : attribute.toString();
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        return dbData == null || dbData.isBlank() ? null : Instant.parse(dbData);
    }
}
