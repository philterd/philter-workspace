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

import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A span in a blind second review (#86): the second reviewer's own copy, kept apart from the document's spans so
 * neither review sees or overwrites the other's decisions until they are reconciled. Nothing that reads a document's
 * spans (finalization, risk, reports, search) sees these.
 */
@Document(collection = "blind_review_spans")
public class BlindReviewSpan extends Span {

    public BlindReviewSpan() {
    }
}
