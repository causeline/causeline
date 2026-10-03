// SPDX-License-Identifier: Apache-2.0
package dev.causeline.test;

import dev.causeline.core.TraceStore;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** Marks where each test starts in the trace store and hands the test its {@link RecordedTraces}. */
final class CauselineExtension implements BeforeEachCallback, ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(CauselineExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        // Before the test body, so traces from earlier tests and from context start-up are left out.
        context.getStore(NAMESPACE).put(RecordedTraces.class, start(context));
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == RecordedTraces.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return context.getStore(NAMESPACE).get(RecordedTraces.class, RecordedTraces.class);
    }

    private static RecordedTraces start(ExtensionContext context) {
        ApplicationContext application = SpringExtension.getApplicationContext(context);
        TraceStore store = application.getBeanProvider(TraceStore.class).getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("Causeline is not active in this test's application context. "
                    + "@CauselineTest sets causeline.enabled=true; check that nothing overrides it and that no "
                    + "production profile (prod*, production) is active.");
        }
        SdkTracerProvider tracing = application.getBeanProvider(SdkTracerProvider.class).getIfAvailable();
        // Spans reach the store through an async queue; a flush moves the finished ones across now.
        Runnable flush = tracing == null ? () -> { } : () -> tracing.forceFlush().join(2, TimeUnit.SECONDS);
        return new RecordedTraces(store, flush);
    }
}
