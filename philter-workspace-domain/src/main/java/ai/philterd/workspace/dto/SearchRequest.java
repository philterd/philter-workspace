/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /api/v1/search}. The query travels in the body, not the URL, so it stays out of access logs
 * and browser history (#78). {@code offset} defaults to 0 and {@code size} to 10.
 */
public record SearchRequest(@NotBlank String q, @Min(0) Integer offset, Integer size) {
}
