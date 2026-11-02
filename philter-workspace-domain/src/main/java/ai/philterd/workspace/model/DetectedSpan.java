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

/**
 * One of Philter's detections as it arrived (#86), kept on a document selected for blind double review so the
 * second reviewer starts from the detections rather than from the first review. Holds no text: the text is read
 * from the document at its location.
 */
public class DetectedSpan {

    private String type;
    private double confidence;
    private Location location;

    public DetectedSpan() {
    }

    public DetectedSpan(final String type, final double confidence, final Location location) {
        this.type = type;
        this.confidence = confidence;
        this.location = location;
    }

    public String getType() { return type; }
    public void setType(final String type) { this.type = type; }

    public double getConfidence() { return confidence; }
    public void setConfidence(final double confidence) { this.confidence = confidence; }

    public Location getLocation() { return location; }
    public void setLocation(final Location location) { this.location = location; }
}
