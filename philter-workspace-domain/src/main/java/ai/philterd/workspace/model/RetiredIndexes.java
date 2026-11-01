/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indexes an entity no longer declares and that each store drops at startup. Both stores create a declared index
 * only when its name is missing and never drop one, so an index that must stop applying, such as a unique index that
 * was relaxed, is named here. Each value is the index's name as MongoDB gives it: the field name for
 * {@code @Indexed}, or the {@code @CompoundIndex} name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RetiredIndexes {
    String[] value();
}
