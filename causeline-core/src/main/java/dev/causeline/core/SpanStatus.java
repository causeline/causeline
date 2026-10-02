// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

/** Outcome of a span, mirroring the OpenTelemetry status codes. */
public enum SpanStatus {
    OK,
    ERROR,
    UNSET
}
