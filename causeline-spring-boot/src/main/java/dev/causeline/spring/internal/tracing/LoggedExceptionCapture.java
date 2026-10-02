// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Attaches {@link LoggedExceptionAppender} to Logback's root logger once the application context is
 * ready, and removes it on shutdown. Does nothing when Logback is not the active logging backend.
 */
public final class LoggedExceptionCapture implements SmartInitializingSingleton, DisposableBean {

    private final LoggedExceptionAppender appender;
    private Logger root;

    public LoggedExceptionCapture(LoggedExceptionAppender appender) {
        this.appender = appender;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            appender.setContext(context);
            appender.start();
            root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.addAppender(appender);
        }
    }

    @Override
    public void destroy() {
        if (root != null) {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
