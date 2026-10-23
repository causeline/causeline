// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import dev.causeline.core.Insight.Rule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rule-based analysis of an assembled trace (PRD section 7). No AI, no heuristics beyond these rules. */
public final class Insights {

    static final long NOTABLE_MIN_NANOS = 100_000_000;
    static final int REPEATED_QUERY_MIN = 10;
    /** Lazy loading shows up with as few rows as a page of results, so SELECTs are flagged sooner. */
    static final int REPEATED_SELECT_MIN = 5;
    static final long SLOW_CLIENT_MIN_NANOS = 100_000_000;
    /** A request counts as following the previous one when it starts this soon after it ended. */
    static final long WATERFALL_MAX_GAP_NANOS = 50_000_000;
    static final long WATERFALL_MIN_NANOS = 100_000_000;
    /** Loading, data, done: three or four renders per action are normal. */
    static final int RENDER_STORM_MIN = 5;
    /** Set by the React SDK's useTracedState. */
    static final String AFTER_UNMOUNT_ATTRIBUTE = "causeline.state.after_unmount";
    /** HTTP calls shorter than this inside a transaction are not worth restructuring code for. */
    static final long CALL_IN_TRANSACTION_MIN_NANOS = 100_000_000;

    private Insights() {
    }

    public static List<Insight> analyze(List<TraceView.Row> rows, long traceDurationNanos) {
        List<Insight> insights = new ArrayList<>();
        if (rows.isEmpty() || traceDurationNanos <= 0) {
            return insights;
        }
        selfTimeRules(rows, traceDurationNanos, insights);
        repeatedQueries(rows, insights);
        slowClientHandling(rows, insights);
        handledExceptions(rows, insights);
        new Transactions(rows).analyze(insights);
        requestWaterfalls(rows, insights);
        renderStorms(rows, insights);
        stateUpdatesAfterUnmount(rows, insights);
        SecurityChecks.analyze(rows, insights);
        return insights;
    }

    private static void handledExceptions(List<TraceView.Row> rows, List<Insight> insights) {
        Map<String, TraceView.Row> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.spanId(), r));
        rows.stream()
                .filter(r -> r.kind() == SpanKind.EXCEPTION
                        && "true".equals(r.attributes().get(TraceAssembler.HANDLED_ATTRIBUTE)))
                .forEach(r -> {
                    TraceView.Row parent = byId.get(r.parentSpanId());
                    String where = parent == null ? "" : " in " + parent.name()
                            + " (" + ms(parent.durationNanos()) + ")";
                    String level = r.attributes().getOrDefault("log.level", "WARN");
                    insights.add(new Insight(Rule.HANDLED_EXCEPTION, r.spanId(),
                            "Handled exception: " + r.name() + where + ", caught and logged at " + level
                                    + "; the request continued",
                            0));
                });
    }

    /**
     * Root spans are left out: their self time is whatever the instrumentation below them did not
     * cover, and a one-span trace would otherwise always be "100% bottleneck".
     */
    private static void selfTimeRules(List<TraceView.Row> rows, long total, List<Insight> insights) {
        List<TraceView.Row> candidates = rows.stream().filter(r -> r.depth() > 0).toList();
        if (candidates.isEmpty()) {
            return;
        }
        TraceView.Row top = candidates.stream().max(Comparator.comparingLong(TraceView.Row::selfNanos)).orElseThrow();
        boolean primary = top.selfNanos() * 2 >= total;
        if (primary) {
            insights.add(new Insight(Rule.PRIMARY_BOTTLENECK, top.spanId(),
                    "Potential bottleneck: " + top.name() + " · " + ms(top.selfNanos()) + " · " + share(top, total) + "%",
                    share(top, total)));
        }
        candidates.stream()
                .filter(r -> !(primary && r == top))
                .filter(r -> r.selfNanos() * 4 >= total && r.selfNanos() >= NOTABLE_MIN_NANOS)
                .sorted(Comparator.comparingLong(TraceView.Row::selfNanos).reversed())
                .forEach(r -> insights.add(new Insight(Rule.NOTABLE, r.spanId(),
                        "Notable: " + r.name() + " · " + ms(r.selfNanos()) + " · " + share(r, total) + "%",
                        share(r, total))));
    }

    /**
     * Identical statements under one parent. For SELECTs the label names where they ran and the query
     * that most likely produced the rows they load (the "1" of N+1), and how to load them together.
     */
    private static void repeatedQueries(List<TraceView.Row> rows, List<Insight> insights) {
        Map<String, TraceView.Row> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.spanId(), r));
        Map<String, List<TraceView.Row>> groups = new LinkedHashMap<>();
        rows.stream()
                .filter(r -> r.kind() == SpanKind.DATABASE)
                .forEach(r -> groups.computeIfAbsent(r.parentSpanId() + "|" + statementOf(r), k -> new ArrayList<>()).add(r));
        groups.values().forEach(group -> {
            TraceView.Row first = group.getFirst();
            boolean select = first.name().startsWith("SELECT");
            if (group.size() < (select ? REPEATED_SELECT_MIN : REPEATED_QUERY_MIN)) {
                return;
            }
            TraceView.Row parent = byId.get(first.parentSpanId());
            String where = parent == null ? "" : " in " + parent.name();
            String label;
            if (select) {
                TraceView.Row before = queryBefore(rows, first);
                String table = first.attributes().getOrDefault("db.collection.name", first.name().substring(6).trim());
                label = "N+1 query: " + first.name() + " ran " + group.size() + " times" + where
                        + (before == null ? "" : ", once per row of " + before.name())
                        + ". Load " + table + " in the same query: JOIN FETCH or @EntityGraph on the repository method,"
                        + " or @BatchSize / hibernate.default_batch_fetch_size";
            } else {
                label = "Repeated query × " + group.size() + ": " + first.name() + where
                        + " (consider a batch statement or saveAll)";
            }
            insights.add(new Insight(Rule.REPEATED_QUERY, first.spanId(), label, 0));
        });
    }

    /** Statements that differ only in their values count as the same query. */
    private static String statementOf(TraceView.Row row) {
        String prepared = row.attributes().get("db.query.statement");
        return prepared != null ? prepared : row.name();
    }

    /** The last query that finished before the repeated ones started, from a different statement. */
    private static TraceView.Row queryBefore(List<TraceView.Row> rows, TraceView.Row first) {
        TraceView.Row best = null;
        for (TraceView.Row r : rows) {
            if (r.kind() == SpanKind.DATABASE && r.offsetNanos() + r.durationNanos() <= first.offsetNanos()
                    && !statementOf(r).equals(statementOf(first)) && r.name().startsWith("SELECT")
                    && (best == null || r.offsetNanos() > best.offsetNanos())) {
                best = r;
            }
        }
        return best;
    }

    private static void slowClientHandling(List<TraceView.Row> rows, List<Insight> insights) {
        List<TraceView.Row> stateUpdates = rows.stream().filter(r -> r.kind() == SpanKind.STATE_UPDATE).toList();
        if (stateUpdates.isEmpty()) {
            return;
        }
        rows.stream()
                .filter(r -> r.kind() == SpanKind.REQUEST && "browser".equals(r.source()))
                .forEach(request -> {
                    long end = request.offsetNanos() + request.durationNanos();
                    stateUpdates.stream()
                            .filter(s -> s.offsetNanos() >= end)
                            .mapToLong(TraceView.Row::offsetNanos)
                            .min()
                            .ifPresent(next -> {
                                long gap = next - end;
                                if (gap >= SLOW_CLIENT_MIN_NANOS) {
                                    insights.add(new Insight(Rule.SLOW_CLIENT_HANDLING, request.spanId(),
                                            "Slow client handling: " + ms(gap)
                                                    + " between the response to " + request.name()
                                                    + " and the next state update",
                                            0));
                                }
                            });
                });
    }

    /**
     * GET requests the browser made one after another within one action. Only GETs: the order of
     * requests that change data is usually deliberate.
     */
    private static void requestWaterfalls(List<TraceView.Row> rows, List<Insight> insights) {
        Map<String, TraceView.Row> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.spanId(), r));
        Map<String, List<TraceView.Row>> byAction = new LinkedHashMap<>();
        rows.stream()
                .filter(r -> r.kind() == SpanKind.REQUEST && "browser".equals(r.source()) && r.parentSpanId() != null)
                .forEach(r -> byAction.computeIfAbsent(r.parentSpanId(), k -> new ArrayList<>()).add(r));
        byAction.forEach((actionId, requests) -> {
            requests.sort(Comparator.comparingLong(TraceView.Row::offsetNanos));
            List<TraceView.Row> run = new ArrayList<>();
            for (TraceView.Row request : requests) {
                boolean follows = !run.isEmpty()
                        && request.offsetNanos() >= end(run.getLast())
                        && request.offsetNanos() - end(run.getLast()) <= WATERFALL_MAX_GAP_NANOS;
                if (!isGet(request) || !follows) {
                    waterfall(run, byId.get(actionId), insights);
                    run.clear();
                }
                if (isGet(request)) {
                    run.add(request);
                }
            }
            waterfall(run, byId.get(actionId), insights);
        });
    }

    private static void waterfall(List<TraceView.Row> run, TraceView.Row action, List<Insight> insights) {
        long total = run.stream().mapToLong(TraceView.Row::durationNanos).sum();
        if (run.size() < 2 || total < WATERFALL_MIN_NANOS) {
            return;
        }
        long slowest = run.stream().mapToLong(TraceView.Row::durationNanos).max().orElse(0);
        insights.add(new Insight(Rule.REQUEST_WATERFALL, run.getFirst().spanId(),
                "Request waterfall: " + run.size() + " requests ran one after another"
                        + (action == null ? "" : " in " + action.name()) + " ("
                        + String.join(", ", run.stream().map(TraceView.Row::name).toList()) + "), " + ms(total)
                        + " in total. If none needs the previous one's answer, start them together (Promise.all): about "
                        + ms(slowest),
                0));
    }

    private static boolean isGet(TraceView.Row request) {
        return "GET".equals(request.attributes().get("http.request.method")) || request.name().startsWith("GET ");
    }

    private static long end(TraceView.Row row) {
        return row.offsetNanos() + row.durationNanos();
    }

    /** RENDER spans come from CauselineProfiler: one per commit of the wrapped tree during an action. */
    private static void renderStorms(List<TraceView.Row> rows, List<Insight> insights) {
        Map<String, TraceView.Row> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.spanId(), r));
        Map<String, List<TraceView.Row>> renders = new LinkedHashMap<>();
        rows.stream()
                .filter(r -> r.kind() == SpanKind.RENDER)
                .forEach(r -> renders.computeIfAbsent(r.parentSpanId() + "|" + r.name(), k -> new ArrayList<>()).add(r));
        renders.values().forEach(group -> {
            if (group.size() < RENDER_STORM_MIN) {
                return;
            }
            TraceView.Row first = group.getFirst();
            TraceView.Row action = byId.get(first.parentSpanId());
            long total = group.stream().mapToLong(TraceView.Row::durationNanos).sum();
            insights.add(new Insight(Rule.RENDER_STORM, first.spanId(),
                    "Re-render storm: " + first.name() + " rendered " + group.size() + " times"
                            + (action == null ? "" : " during " + action.name()) + " (" + ms(total)
                            + " of rendering). Each separate state update renders again: set related state together,"
                            + " or keep it in one reducer",
                    0));
        });
    }

    private static void stateUpdatesAfterUnmount(List<TraceView.Row> rows, List<Insight> insights) {
        rows.stream()
                .filter(r -> r.kind() == SpanKind.STATE_UPDATE && "true".equals(r.attributes().get(AFTER_UNMOUNT_ATTRIBUTE)))
                .collect(java.util.stream.Collectors.toMap(TraceView.Row::name, r -> r, (first, second) -> first, LinkedHashMap::new))
                .values()
                .forEach(r -> insights.add(new Insight(Rule.STATE_UPDATE_AFTER_UNMOUNT, r.spanId(),
                        "State update after unmount: " + r.name() + " was set after its component had unmounted."
                                + " The request or timer that set it outlived the component; cancel it in the effect's"
                                + " cleanup (AbortController, clearTimeout)",
                        0)));
    }

    /**
     * Spring transaction rules. A span belongs to a transaction when the transaction is one of its
     * ancestors (thread-bound transactions nest what runs in them), or, for reactive transactions,
     * which can't nest, when it ran during the transaction under the span that opened it.
     */
    private static final class Transactions {

        private static final Set<String> WRITES = Set.of("INSERT", "UPDATE", "DELETE", "MERGE");

        private final List<TraceView.Row> rows;
        private final Map<String, TraceView.Row> byId = new LinkedHashMap<>();
        private final List<TraceView.Row> transactions;

        Transactions(List<TraceView.Row> rows) {
            this.rows = rows;
            rows.forEach(r -> byId.put(r.spanId(), r));
            this.transactions = rows.stream().filter(r -> r.kind() == SpanKind.TRANSACTION).toList();
        }

        void analyze(List<Insight> insights) {
            writesNotAtomic(insights);
            if (transactions.isEmpty()) {
                return;
            }
            queriesAfterTransaction(insights);
            transactionsAcrossCalls(insights);
        }

        /** Writes of one request spread over several transactions (or none): a failure leaves half of them behind. */
        private void writesNotAtomic(List<Insight> insights) {
            Map<String, List<TraceView.Row>> writesByUnit = new LinkedHashMap<>();
            rows.stream()
                    .filter(r -> r.kind() == SpanKind.DATABASE && WRITES.contains(verb(r)))
                    // Work handed to another thread (@Async) is expected to have its own transaction.
                    .filter(r -> end(r) <= end(unitOf(r)))
                    .forEach(r -> writesByUnit.computeIfAbsent(unitOf(r).spanId(), k -> new ArrayList<>()).add(r));
            writesByUnit.forEach((unitId, writes) -> {
                Set<String> contexts = new LinkedHashSet<>();
                for (TraceView.Row write : writes) {
                    TraceView.Row transaction = transactionOf(write);
                    // A statement outside any transaction commits by itself.
                    contexts.add(transaction == null ? write.spanId() : transaction.spanId());
                }
                if (contexts.size() < 2) {
                    return;
                }
                List<String> names = writes.stream().map(TraceView.Row::name).distinct().toList();
                insights.add(new Insight(Rule.WRITES_NOT_ATOMIC, writes.getFirst().spanId(),
                        "Writes not in one transaction: " + writes.size() + " statements (" + String.join(", ", names)
                                + ") were committed separately in " + byId.get(unitId).name()
                                + ". If a later one fails, the earlier ones stay. Put @Transactional on the method"
                                + " that does them together",
                        0));
            });
        }

        /** The open-session-in-view signature: SELECTs under the controller or the request itself, once the transaction is over. */
        private void queriesAfterTransaction(List<Insight> insights) {
            Map<String, List<TraceView.Row>> lateByUnit = new LinkedHashMap<>();
            Map<String, TraceView.Row> endedBefore = new LinkedHashMap<>();
            for (TraceView.Row query : rows) {
                if (query.kind() != SpanKind.DATABASE || !verb(query).equals("SELECT") || transactionOf(query) != null) {
                    continue;
                }
                TraceView.Row owner = byId.get(query.parentSpanId());
                if (owner == null || (owner.kind() != SpanKind.CONTROLLER && owner.kind() != SpanKind.REQUEST)) {
                    continue;
                }
                TraceView.Row unit = unitOf(query);
                transactions.stream()
                        .filter(t -> unitOf(t) == unit && end(t) <= query.offsetNanos())
                        .reduce((first, second) -> second)
                        .ifPresent(transaction -> {
                            lateByUnit.computeIfAbsent(unit.spanId(), k -> new ArrayList<>()).add(query);
                            endedBefore.putIfAbsent(unit.spanId(), transaction);
                        });
            }
            lateByUnit.forEach((unitId, queries) -> {
                TraceView.Row first = queries.getFirst();
                List<String> names = queries.stream().map(TraceView.Row::name).distinct().toList();
                insights.add(new Insight(Rule.QUERY_AFTER_TRANSACTION, first.spanId(),
                        "Lazy loading after the transaction: " + queries.size()
                                + (queries.size() == 1 ? " query (" : " queries (") + String.join(", ", names) + ") ran in "
                                + byId.get(first.parentSpanId()).name() + " after " + endedBefore.get(unitId).name()
                                + " had ended, kept working by spring.jpa.open-in-view. Load what the response needs"
                                + " inside the transaction: JOIN FETCH, @EntityGraph or a DTO",
                        0));
            });
        }

        /** A transaction holds a pooled connection; waiting for another service while holding it starves the pool under load. */
        private void transactionsAcrossCalls(List<Insight> insights) {
            for (TraceView.Row transaction : transactions) {
                List<TraceView.Row> calls = rows.stream()
                        .filter(r -> r.kind() == SpanKind.HTTP_CLIENT && transactionOf(r) == transaction)
                        .toList();
                long waiting = calls.stream().mapToLong(TraceView.Row::durationNanos).sum();
                if (waiting < CALL_IN_TRANSACTION_MIN_NANOS) {
                    continue;
                }
                insights.add(new Insight(Rule.TRANSACTION_ACROSS_CALL, transaction.spanId(),
                        "Transaction open during an HTTP call: " + transaction.name() + " lasted "
                                + ms(transaction.durationNanos()) + ", " + ms(waiting) + " of it waiting for "
                                + calls.getFirst().name() + (calls.size() > 1 ? " and " + (calls.size() - 1) + " more" : "")
                                + ". It holds a database connection the whole time; make the call before or after"
                                + " the transaction",
                        0));
            }
        }

        private TraceView.Row transactionOf(TraceView.Row row) {
            for (TraceView.Row up = parent(row); up != null; up = parent(up)) {
                if (up.kind() == SpanKind.TRANSACTION) {
                    return up;
                }
            }
            for (TraceView.Row transaction : transactions) {
                boolean during = row.offsetNanos() >= transaction.offsetNanos() && end(row) <= end(transaction);
                if (during && transaction.parentSpanId() != null && hasAncestor(row, transaction.parentSpanId())) {
                    return transaction;
                }
            }
            return null;
        }

        /** The server request (or received message) a span belongs to; the trace's root when there is none. */
        private TraceView.Row unitOf(TraceView.Row row) {
            TraceView.Row top = row;
            for (TraceView.Row up = parent(row); up != null; up = parent(up)) {
                if (up.kind() == SpanKind.REQUEST || up.kind() == SpanKind.MESSAGE) {
                    return up;
                }
                top = up;
            }
            return top;
        }

        private boolean hasAncestor(TraceView.Row row, String spanId) {
            for (TraceView.Row up = parent(row); up != null; up = parent(up)) {
                if (up.spanId().equals(spanId)) {
                    return true;
                }
            }
            return false;
        }

        private TraceView.Row parent(TraceView.Row row) {
            return row.parentSpanId() == null ? null : byId.get(row.parentSpanId());
        }

        private static long end(TraceView.Row row) {
            return row.offsetNanos() + row.durationNanos();
        }

        /** SQL spans are named "VERB table". */
        private static String verb(TraceView.Row row) {
            int space = row.name().indexOf(' ');
            return space < 0 ? row.name() : row.name().substring(0, space);
        }
    }

    private static int share(TraceView.Row row, long total) {
        return (int) Math.round(row.selfNanos() * 100.0 / total);
    }

    private static String ms(long nanos) {
        long ms = Math.round(nanos / 1_000_000.0);
        return ms + " ms";
    }
}
