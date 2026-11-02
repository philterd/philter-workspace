/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store.sqlite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.mapping.Document;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fills every field of every stored entity, saves it, reads it back, and compares. Catches a
 * model field the JSON mapping can't carry, such as a new type without a no-argument constructor.
 */
class SqliteEntityRoundTripTest {

    @TempDir
    Path dir;

    private SqliteTestStore db;
    private int counter;

    @BeforeEach
    void open() throws Exception {
        db = new SqliteTestStore(dir);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    @Test
    void everyEntityRoundTrips() throws Exception {
        final ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));
        int checked = 0;
        for (BeanDefinition d : scanner.findCandidateComponents("ai.philterd.workspace.model")) {
            final Class<?> type = Class.forName(d.getBeanClassName());
            final Object entity = populate(type, 0);
            final CollectionInfo info = db.store.info(type);
            if (info.versionField != null) {
                info.setVersion(entity, null);
            }
            db.store.save(entity);
            final Object read = db.store.findById(type, info.id(entity)).orElseThrow();
            // Field by field, so a field the JSON mapping drops on both sides still fails.
            assertThat(read).as(type.getSimpleName()).usingRecursiveComparison().isEqualTo(entity);
            checked++;
        }
        assertEquals(30, checked);
    }

    private Object populate(final Class<?> type, final int depth) throws Exception {
        if (type.isRecord()) {
            final RecordComponent[] components = type.getRecordComponents();
            final Object[] args = new Object[components.length];
            final Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                args[i] = value(components[i].getType(), components[i].getGenericType(), depth + 1);
            }
            final Constructor<?> c = type.getDeclaredConstructor(types);
            c.setAccessible(true);
            return c.newInstance(args);
        }
        final Constructor<?> c = type.getDeclaredConstructor();
        c.setAccessible(true);
        final Object o = c.newInstance();
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            for (Field f : t.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                f.set(o, value(f.getType(), f.getGenericType(), depth + 1));
            }
        }
        return o;
    }

    private Object value(final Class<?> type, final Type generic, final int depth) throws Exception {
        final int n = ++counter;
        if (type == String.class) return "v" + n;
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return n;
        if (type == long.class || type == Long.class) return (long) n;
        if (type == double.class || type == Double.class) return n + 0.5;
        if (type == float.class || type == Float.class) return n + 0.5f;
        if (type == Instant.class) return Instant.parse("2026-01-02T03:04:05.678Z").plusSeconds(n);
        if (type == LocalDateTime.class) return LocalDateTime.of(2026, 1, 2, 3, 4, 5, 678_000_000).plusSeconds(n);
        if (type == LocalDate.class) return LocalDate.of(2026, 1, 2).plusDays(n);
        if (type == byte[].class) return new byte[]{1, 2, (byte) n};
        if (type == int[].class) return new int[]{n, n + 1};
        if (type.isEnum()) return type.getEnumConstants()[0];
        if (List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type)) {
            final Type element = ((ParameterizedType) generic).getActualTypeArguments()[0];
            final Object item = value(raw(element), element, depth);
            return List.class.isAssignableFrom(type) ? new ArrayList<>(List.of(item)) : new HashSet<>(Set.of(item));
        }
        if (Map.class.isAssignableFrom(type)) {
            final Type v = ((ParameterizedType) generic).getActualTypeArguments()[1];
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("k" + n, raw(v) == Object.class ? "x" + n : value(raw(v), v, depth));
            return m;
        }
        if (depth > 4) return null;
        return populate(type, depth);
    }

    private static Class<?> raw(final Type t) {
        return t instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) t;
    }
}
