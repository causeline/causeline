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
    /** A message sent or received (Kafka, RabbitMQ). */
    MESSAGE,
    /** A log line written while a span was active: a point event under that span. */
    LOG,
    /** A database transaction, from begin to commit or rollback. */
    TRANSACTION,
    /** A cache lookup, write or eviction (Spring Cache). */
    CACHE
}
