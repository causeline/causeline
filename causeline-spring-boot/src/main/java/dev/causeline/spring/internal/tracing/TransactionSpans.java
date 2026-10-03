// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import dev.causeline.core.SpanKind;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.TransactionStatus;

/**
 * A {@link SpanKind#TRANSACTION} span from each transaction's begin to its commit or rollback, so
 * the timeline shows which queries ran in it and how it ended. Registered with every transaction
 * manager in the context, including ones the application defines itself.
 *
 * <p>For thread-bound transactions the span is made current, so the queries inside nest under it.
 * Reactive transactions get the span without the nesting, since they have no thread to bind to.
 */
public final class TransactionSpans implements TransactionExecutionListener, BeanPostProcessor {

    static final String OUTCOME = "db.transaction.outcome";

    private record Open(Observation observation, Observation.Scope scope) {
    }

    private final Supplier<ObservationRegistry> registry;
    private final Map<TransactionExecution, Open> open = new ConcurrentHashMap<>();

    /** @param registry looked up when first needed: a post-processor must not create beans early */
    public TransactionSpans(Supplier<ObservationRegistry> registry) {
        this.registry = Once.of(registry);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ConfigurableTransactionManager manager
                && !manager.getTransactionExecutionListeners().contains(this)) {
            manager.addListener(this);
        }
        return bean;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        // Only traced work, and only transactions that really began (not ones joining an outer one).
        ObservationRegistry observations = registry.get();
        if (beginFailure != null || !transaction.isNewTransaction() || observations == null
                || observations.getCurrentObservation() == null) {
            return;
        }
        try {
            Observation observation = Observation.createNotStarted("causeline.transaction", observations)
                    .contextualName("Transaction " + shortName(transaction.getTransactionName()))
                    .lowCardinalityKeyValue(SpanMapper.KIND_ATTRIBUTE, SpanKind.TRANSACTION.name())
                    .lowCardinalityKeyValue("db.transaction.read_only", Boolean.toString(transaction.isReadOnly()))
                    .start();
            Observation.Scope scope = transaction instanceof TransactionStatus ? observation.openScope() : null;
            open.put(transaction, new Open(observation, scope));
        } catch (RuntimeException e) {
            // Tracing must never get in the way of the transaction.
        }
    }

    @Override
    public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
        finish(transaction, commitFailure == null ? "commit" : "commit failed", commitFailure);
    }

    @Override
    public void afterRollback(TransactionExecution transaction, Throwable rollbackFailure) {
        finish(transaction, "rollback", rollbackFailure);
    }

    private void finish(TransactionExecution transaction, String outcome, Throwable failure) {
        Open span = open.remove(transaction);
        if (span == null) {
            return;
        }
        try {
            if (span.scope() != null) {
                span.scope().close();
            }
            Observation observation = span.observation();
            observation.highCardinalityKeyValue(OUTCOME, outcome);
            if (!outcome.equals("commit")) {
                observation.contextualName(observation.getContext().getContextualName() + " (" + outcome + ")");
            }
            if (failure != null) {
                observation.error(failure);
            }
            observation.stop();
        } catch (RuntimeException e) {
            // Tracing must never get in the way of the transaction.
        }
    }

    /** {@code com.shop.OrderService.createOrder} becomes {@code OrderService.createOrder}. */
    static String shortName(String transactionName) {
        if (transactionName == null || transactionName.isBlank()) {
            return "(unnamed)";
        }
        int method = transactionName.lastIndexOf('.');
        int type = method <= 0 ? -1 : transactionName.lastIndexOf('.', method - 1);
        return type < 0 ? transactionName : transactionName.substring(type + 1);
    }
}
