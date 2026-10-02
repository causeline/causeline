// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

/** What a span represents. See PRD section 4. */
public enum SpanKind {
    UI_ACTION,
    REQUEST,
    CONTROLLER,
    SERVICE,
    REPOSITORY,
    DATABASE,
    HTTP_CLIENT,
    EXCEPTION,
    STATE_UPDATE,
    RENDER,
    /** Reserved for messaging instrumentation in V1.1. */
    MESSAGE
}
