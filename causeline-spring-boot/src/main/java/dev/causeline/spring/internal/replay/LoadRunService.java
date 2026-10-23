// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import dev.causeline.core.LoadAggregate;
import dev.causeline.core.LoadAggregate.Phase;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceAssembler;
import dev.causeline.core.TraceStore;
import dev.causeline.core.TraceView;
import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.internal.replay.ReplayService.Edits;
import dev.causeline.spring.internal.replay.ReplayService.ReplayException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Load replay (PRD section 8): sends a captured request, or every replayable request of a trace in
 * order (a flow), many times, and reports which span's self time grew under concurrency.
 *
 * <p>Every hit is an ordinary {@link ReplayService} replay, so the target allowlist, auth profiles
 * and edit guards apply unchanged. Hits run on virtual threads, never on a request thread or the
 * event loop. On {@code local}, each hit's trace is folded into a {@link LoadAggregate} and then
 * removed from the store, except the slowest and the failed ones, so a run can't evict the
 * developer's other traces. Results hold names, statuses and timings only, never captured values.
 */
public final class LoadRunService {

    /** Hits sent one at a time before the load, to compare against. */
    public static final int BASELINE_HITS = 5;
    static final long MAX_THINK_NANOS = Duration.ofSeconds(5).toNanos();
    static final int KEPT_RUNS = 5;
    /** Spans that end after the response (async work) get this long to arrive... */
    static final long SETTLE_NANOS = Duration.ofMillis(500).toNanos();
    /** ...and a hit whose server request never arrives is given up after this. */
    static final long TRACE_WAIT_NANOS = Duration.ofSeconds(5).toNanos();
    /** Spans of a removed hit that arrive this late are removed by a second sweep. */
    static final Duration LATE_SWEEP = Duration.ofSeconds(5);

    public enum Mode {
        REQUEST,
        FLOW
    }

    public enum Shape {
        CONCURRENCY,
        RATE
    }

    public enum State {
        RUNNING,
        DONE,
        CANCELLED
    }

    /**
     * @param spanId          the request picked in the replay panel; in FLOW mode, the request
     *                        that {@code body} and {@code edits} apply to
     * @param hits            CONCURRENCY: iterations in total (shared by the ramp steps)
     * @param concurrency     iterations in flight at once
     * @param rate            RATE: iterations started per second
     * @param durationSeconds RATE: how long to keep starting them
     * @param ramp            CONCURRENCY only: step through 1, 2, 4 ... up to {@code concurrency}
     */
    public record Settings(String traceId, String spanId, String target, Mode mode, Shape shape, int hits,
            int concurrency, double rate, int durationSeconds, boolean ramp, boolean confirm, String body,
            Edits edits) {

        public Settings {
            mode = mode == null ? Mode.REQUEST : mode;
            shape = shape == null ? Shape.CONCURRENCY : shape;
            target = target == null ? ReplayService.LOCAL_TARGET : target;
            edits = edits == null ? Edits.NONE : edits;
        }
    }

    /** One request of the run: the picked one, or one step of the flow. */
    public record Step(String spanId, String method, String route, boolean unsafe, long thinkNanos) {
    }

    /** A server request of the flow that has nothing captured to replay. */
    public record Skipped(String spanId, String name) {
    }

    public enum KeptReason {
        SLOWEST,
        FAILED
    }

    public record Kept(String traceId, int step, long durationNanos, int httpStatus, KeptReason reason) {
    }

    /**
     * @param httpStatus 0 when the target could not be reached
     * @param ramp       index of the ramp step; -1 in the baseline
     * @param endNanos   when the response arrived, relative to the run's start
     */
    record Hit(Phase phase, int ramp, int step, String traceId, int httpStatus, long durationNanos, long endNanos,
            String error) {

        boolean failed() {
            return httpStatus == 0 || httpStatus >= 500;
        }
    }

    private final ReplayService replays;
    private final TraceStore traces;
    private final CauselineProperties.Load limits;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final LinkedHashMap<String, Run> runs = new LinkedHashMap<>();
    private Run active;

    public LoadRunService(ReplayService replays, TraceStore traces, CauselineProperties.Load limits) {
        this.replays = replays;
        this.traces = traces;
        this.limits = limits;
    }

    public CauselineProperties.Load limits() {
        return limits;
    }

    /**
     * Checks the settings and starts the run.
     *
     * @throws ReplayException with the reason the run can't start
     */
    public synchronized Run start(Settings settings) {
        if (active != null && active.state() == State.RUNNING) {
            throw new ReplayException(409, "A load run is already going. Cancel it or wait for it to finish.");
        }
        if (!replays.targets().containsKey(settings.target())) {
            throw new ReplayException(400, "Unknown replay target '" + settings.target() + "'.");
        }
        if (!replays.allowsLoad(settings.target())) {
            throw new ReplayException(403, "Load replay is off for '" + settings.target() + "'. Set causeline.replay.targets."
                    + settings.target() + ".allow-load=true if this environment may be loaded.");
        }
        Plan plan = plan(settings);
        List<Step> steps = steps(settings, plan.skipped());
        long requests = (long) (BASELINE_HITS + plan.iterations()) * steps.size();
        if (requests > limits.maxRequests()) {
            throw new ReplayException(400, "This run would send " + requests + " requests (" + BASELINE_HITS
                    + " baseline + " + plan.iterations() + " under load" + (steps.size() > 1 ? ", × " + steps.size()
                    + " requests per flow" : "") + "); causeline.load.max-requests is " + limits.maxRequests() + ".");
        }
        if (steps.stream().anyMatch(Step::unsafe) && !settings.confirm()) {
            Step unsafe = steps.stream().filter(Step::unsafe).findFirst().orElseThrow();
            throw new ReplayException(409, "This will run " + unsafe.method() + " " + unsafe.route() + " "
                    + (BASELINE_HITS + plan.iterations()) + " times on " + settings.target()
                    + ". Side effects such as orders and payments will repeat "
                    + (BASELINE_HITS + plan.iterations()) + " times. Confirm to continue.");
        }
        for (Step step : steps) {
            replays.check(settings.traceId(), step.spanId(), settings.target(), true, bodyFor(settings, step),
                    editsFor(settings, step));
        }
        Run run = new Run(UUID.randomUUID().toString(), settings, steps, plan, requests, replays.isLocal(settings.target()));
        runs.put(run.id, run);
        while (runs.size() > KEPT_RUNS) {
            runs.remove(runs.keySet().iterator().next());
        }
        active = run;
        threads.execute(run::execute);
        return run;
    }

    public synchronized Optional<Run> get(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    /** The last runs, newest first. */
    public synchronized List<Run> recent() {
        List<Run> list = new ArrayList<>(runs.values());
        java.util.Collections.reverse(list);
        return list;
    }

    /** Stops starting new hits; those in flight finish. */
    public synchronized boolean cancel(String id) {
        Run run = runs.get(id);
        if (run == null) {
            return false;
        }
        run.cancel();
        return true;
    }

    /** @param ramp concurrency of each ramp step, and the iterations it runs */
    record Plan(int iterations, List<int[]> ramp, List<Skipped> skipped) {
    }

    private Plan plan(Settings s) {
        if (s.concurrency() < 1 || s.concurrency() > limits.maxConcurrency()) {
            throw new ReplayException(400, "Concurrency must be between 1 and " + limits.maxConcurrency()
                    + " (causeline.load.max-concurrency).");
        }
        List<int[]> ramp = new ArrayList<>();
        if (s.shape() == Shape.RATE) {
            if (s.ramp()) {
                throw new ReplayException(400, "A ramp steps through concurrency; it can't be combined with a fixed rate.");
            }
            if (!(s.rate() > 0) || s.rate() > 1_000) {
                throw new ReplayException(400, "The rate must be above 0 and at most 1000 per second.");
            }
            if (s.durationSeconds() < 1 || s.durationSeconds() > limits.maxDuration().toSeconds()) {
                throw new ReplayException(400, "The duration must be between 1 and " + limits.maxDuration().toSeconds()
                        + " seconds (causeline.load.max-duration).");
            }
            int iterations = (int) Math.ceil(s.rate() * s.durationSeconds());
            ramp.add(new int[] {s.concurrency(), iterations});
            return new Plan(iterations, ramp, new ArrayList<>());
        }
        if (s.hits() < 1) {
            throw new ReplayException(400, "Send at least one hit.");
        }
        if (!s.ramp()) {
            ramp.add(new int[] {s.concurrency(), s.hits()});
            return new Plan(s.hits(), ramp, new ArrayList<>());
        }
        List<Integer> levels = new ArrayList<>();
        for (int c = 1; c < s.concurrency(); c *= 2) {
            levels.add(c);
        }
        levels.add(s.concurrency());
        int perStep = Math.max(1, s.hits() / levels.size());
        levels.forEach(c -> ramp.add(new int[] {c, perStep}));
        return new Plan(perStep * levels.size(), ramp, new ArrayList<>());
    }

    private List<Step> steps(Settings s, List<Skipped> skipped) {
        List<Span> spans = traces.get(s.traceId()).orElseThrow(() -> new ReplayException(404, "Trace not found."));
        Map<String, Span> byId = new HashMap<>();
        spans.forEach(span -> byId.put(span.spanId(), span));
        if (s.mode() == Mode.REQUEST) {
            ReplayRecord record = replays.record(s.traceId(), s.spanId()).orElseThrow(() -> new ReplayException(404,
                    "Nothing captured to replay this request. It may be older than the replay buffer."));
            return List.of(step(record, byId.get(s.spanId()), 0));
        }
        Map<String, ReplayRecord> records = new HashMap<>();
        replays.records(s.traceId()).forEach(r -> records.put(r.spanId(), r));
        List<Span> requests = spans.stream()
                .filter(span -> span.kind() == SpanKind.REQUEST && !"browser".equals(span.source()))
                .sorted(Comparator.comparingLong(Span::startTimeUnixNano))
                .toList();
        List<Step> steps = new ArrayList<>();
        Span previous = null;
        for (Span request : requests) {
            ReplayRecord record = records.get(request.spanId());
            if (record == null) {
                skipped.add(new Skipped(request.spanId(), request.name()));
                continue;
            }
            // Think time: the user's pause between the previous response and this request.
            long think = previous == null ? 0
                    : Math.min(MAX_THINK_NANOS, Math.max(0, request.startTimeUnixNano() - previous.endTimeUnixNano()));
            steps.add(step(record, request, think));
            previous = request;
        }
        if (steps.isEmpty()) {
            throw new ReplayException(404, "This trace has no replayable requests.");
        }
        return steps;
    }

    private static Step step(ReplayRecord record, Span span, long thinkNanos) {
        String route = span == null ? record.path() : span.attributes().getOrDefault("http.route", record.path());
        return new Step(record.spanId(), record.method(), route, record.isUnsafe(), thinkNanos);
    }

    private static String bodyFor(Settings s, Step step) {
        return step.spanId().equals(s.spanId()) ? s.body() : null;
    }

    private static Edits editsFor(Settings s, Step step) {
        return step.spanId().equals(s.spanId()) ? s.edits() : Edits.NONE;
    }

    /** One load run: sends the hits, folds their traces, and answers the UI's questions about it. */
    public final class Run {

        private final String id;
        private final Settings settings;
        private final List<Step> steps;
        private final Plan plan;
        private final long plannedRequests;
        private final boolean analyzed;
        private final Instant startedAt = Instant.now();
        private final long startNanos = System.nanoTime();
        private final long deadlineNanos;
        private final List<LoadAggregate> aggregates = new ArrayList<>();
        private final List<Hit> hits = new ArrayList<>();
        private final ConcurrentLinkedQueue<Hit> pending = new ConcurrentLinkedQueue<>();
        private final List<List<Kept>> slowest = new ArrayList<>();
        private final List<Kept> keptFailed = new ArrayList<>();
        private final Set<String> removed = ConcurrentHashMap.newKeySet();
        private volatile State state = State.RUNNING;
        private volatile String stopReason;
        private volatile boolean stopping;
        private volatile boolean cancelled;
        private volatile long loadStartNanos = -1;
        private volatile long finishedNanos = -1;
        private int notSent;
        private int tracesMissing;

        Run(String id, Settings settings, List<Step> steps, Plan plan, long plannedRequests, boolean analyzed) {
            this.id = id;
            this.settings = settings;
            this.steps = List.copyOf(steps);
            this.plan = plan;
            this.plannedRequests = plannedRequests;
            this.analyzed = analyzed;
            this.deadlineNanos = startNanos + limits.maxDuration().toNanos();
            for (int i = 0; i < steps.size(); i++) {
                aggregates.add(new LoadAggregate());
                slowest.add(new ArrayList<>());
            }
        }

        public String id() {
            return id;
        }

        public State state() {
            return state;
        }

        void cancel() {
            cancelled = true;
            stop("Cancelled.");
        }

        void stop(String reason) {
            if (!stopping) {
                stopReason = reason;
                stopping = true;
            }
        }

        private boolean stopped() {
            if (!stopping && System.nanoTime() > deadlineNanos) {
                stop("Stopped at causeline.load.max-duration (" + limits.maxDuration().toSeconds() + " s).");
            }
            return stopping;
        }

        void execute() {
            Thread folder = analyzed ? Thread.ofVirtual().name("causeline-load-traces").start(this::foldTraces) : null;
            try {
                for (int i = 0; i < BASELINE_HITS && !stopped(); i++) {
                    iteration(Phase.BASELINE, -1);
                }
                loadStartNanos = System.nanoTime() - startNanos;
                for (int r = 0; r < plan.ramp().size() && !stopped(); r++) {
                    int[] level = plan.ramp().get(r);
                    if (settings.shape() == Shape.RATE) {
                        byRate(r, level[0], level[1]);
                    } else {
                        byConcurrency(r, level[0], level[1]);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop("Interrupted.");
            } catch (RuntimeException e) {
                stop("Stopped: " + e.getMessage());
            } finally {
                finishedNanos = System.nanoTime() - startNanos;
                state = cancelled ? State.CANCELLED : State.DONE;
                if (folder != null) {
                    try {
                        folder.join(TRACE_WAIT_NANOS / 1_000_000 + 1_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                synchronized (LoadRunService.this) {
                    if (active == this) {
                        active = null;
                    }
                }
                threads.execute(this::lateSweep);
            }
        }

        private void byConcurrency(int ramp, int concurrency, int iterations) throws InterruptedException {
            Semaphore inFlight = new Semaphore(concurrency);
            for (int i = 0; i < iterations && !stopped(); i++) {
                boolean acquired = false;
                while (!acquired && !stopped()) {
                    acquired = inFlight.tryAcquire(100, TimeUnit.MILLISECONDS);
                }
                if (!acquired) {
                    break;
                }
                threads.execute(() -> {
                    try {
                        iteration(Phase.LOAD, ramp);
                    } finally {
                        inFlight.release();
                    }
                });
            }
            inFlight.acquire(concurrency);
        }

        private void byRate(int ramp, int concurrency, int iterations) throws InterruptedException {
            Semaphore inFlight = new Semaphore(concurrency);
            long interval = (long) (1_000_000_000L / settings.rate());
            long begin = System.nanoTime();
            for (int i = 0; i < iterations && !stopped(); i++) {
                long wait = begin + i * interval - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
                if (stopped()) {
                    break;
                }
                if (!inFlight.tryAcquire()) {
                    synchronized (this) {
                        notSent++;
                    }
                    continue;
                }
                threads.execute(() -> {
                    try {
                        iteration(Phase.LOAD, ramp);
                    } finally {
                        inFlight.release();
                    }
                });
            }
            inFlight.acquire(concurrency);
        }

        /** One pass: the request, or each request of the flow with the user's pauses between them. */
        private void iteration(Phase phase, int ramp) {
            for (int k = 0; k < steps.size(); k++) {
                Step step = steps.get(k);
                if (k > 0) {
                    if (stopping) {
                        return;
                    }
                    try {
                        TimeUnit.NANOSECONDS.sleep(step.thinkNanos());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                send(phase, ramp, k, step);
            }
        }

        private void send(Phase phase, int ramp, int k, Step step) {
            long begin = System.nanoTime();
            Hit hit;
            try {
                ReplayService.Outcome outcome = replays.replay(settings.traceId(), step.spanId(), settings.target(), true,
                        bodyFor(settings, step), editsFor(settings, step), Map.of(), ReplayService.TIMEOUT, id -> { });
                hit = new Hit(phase, ramp, k, outcome.replayTraceId(), outcome.httpStatus(), outcome.durationNanos(),
                        System.nanoTime() - startNanos, null);
            } catch (ReplayException e) {
                hit = new Hit(phase, ramp, k, null, 0, System.nanoTime() - begin, System.nanoTime() - startNanos,
                        e.getMessage());
            }
            synchronized (this) {
                hits.add(hit);
            }
            if (analyzed && hit.traceId() != null) {
                pending.add(hit);
            }
        }

        /** Folds each hit's trace into the figures once its spans have arrived, then lets it go. */
        private void foldTraces() {
            while (true) {
                boolean finished = finishedNanos >= 0;
                long now = System.nanoTime() - startNanos;
                for (var it = pending.iterator(); it.hasNext(); ) {
                    Hit hit = it.next();
                    long age = now - hit.endNanos();
                    Optional<List<Span>> spans = traces.get(hit.traceId());
                    boolean arrived = spans.isPresent()
                            && spans.get().stream().anyMatch(s -> s.kind() == SpanKind.REQUEST);
                    if (arrived && age >= SETTLE_NANOS) {
                        fold(hit, TraceAssembler.assemble(hit.traceId(), spans.get()));
                        it.remove();
                    } else if (age >= TRACE_WAIT_NANOS) {
                        synchronized (this) {
                            tracesMissing++;
                        }
                        remove(hit.traceId());
                        it.remove();
                    }
                }
                if (finished && pending.isEmpty()) {
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void fold(Hit hit, TraceView view) {
            aggregates.get(hit.step()).add(hit.phase(), view);
            boolean failed = hit.failed() || view.spans().stream()
                    .anyMatch(r -> r.kind() == SpanKind.REQUEST && r.status() == SpanStatus.ERROR);
            Kept candidate = new Kept(hit.traceId(), hit.step(), hit.durationNanos(), hit.httpStatus(),
                    failed ? KeptReason.FAILED : KeptReason.SLOWEST);
            String drop = hit.traceId();
            synchronized (this) {
                if (failed) {
                    if (keptFailed.size() < limits.keepFailed()) {
                        keptFailed.add(candidate);
                        drop = null;
                    }
                } else if (hit.phase() == Phase.LOAD && limits.keepSlowest() > 0) {
                    List<Kept> kept = slowest.get(hit.step());
                    if (kept.size() < limits.keepSlowest()) {
                        kept.add(candidate);
                        drop = null;
                    } else {
                        Kept fastest = kept.stream().min(Comparator.comparingLong(Kept::durationNanos)).orElseThrow();
                        if (fastest.durationNanos() < hit.durationNanos()) {
                            kept.remove(fastest);
                            kept.add(candidate);
                            drop = fastest.traceId();
                        }
                    }
                }
            }
            if (drop != null) {
                remove(drop);
            }
        }

        private void remove(String traceId) {
            removed.add(traceId);
            traces.remove(traceId);
            replays.forget(traceId);
        }

        /** Spans of removed hits that ended late (async work) would otherwise come back as stray traces. */
        private void lateSweep() {
            try {
                Thread.sleep(LATE_SWEEP);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            removed.forEach(traceId -> {
                traces.remove(traceId);
                replays.forget(traceId);
            });
        }

        public synchronized View view() {
            long now = finishedNanos >= 0 ? finishedNanos : System.nanoTime() - startNanos;
            List<StepView> stepViews = new ArrayList<>();
            for (int k = 0; k < steps.size(); k++) {
                int index = k;
                List<Hit> mine = hits.stream().filter(h -> h.step() == index).toList();
                List<Hit> baseline = mine.stream().filter(h -> h.phase() == Phase.BASELINE).toList();
                List<Hit> load = mine.stream().filter(h -> h.phase() == Phase.LOAD).toList();
                List<RampView> rampViews = new ArrayList<>();
                if (plan.ramp().size() > 1) {
                    for (int r = 0; r < plan.ramp().size(); r++) {
                        int level = r;
                        rampViews.add(new RampView(plan.ramp().get(r)[0],
                                stats(load.stream().filter(h -> h.ramp() == level).toList())));
                    }
                }
                List<Kept> kept = new ArrayList<>(slowest.get(k));
                kept.sort(Comparator.comparingLong(Kept::durationNanos).reversed());
                keptFailed.stream().filter(f -> f.step() == index).forEach(kept::add);
                Step step = steps.get(k);
                stepViews.add(new StepView(step.spanId(), step.method(), step.route(), step.unsafe(),
                        step.thinkNanos() / 1_000_000, stats(baseline), stats(load), rampViews, statuses(baseline),
                        statuses(load), errors(mine), timeline(load), analyzed ? aggregates.get(k).report() : null,
                        kept));
            }
            return new View(id, state, stopReason, settings.traceId(), settings.spanId(), settings.target(),
                    settings.mode(), settings.shape(), settings.hits(), settings.concurrency(), settings.rate(),
                    settings.durationSeconds(), settings.ramp(), analyzed, startedAt.toString(), now / 1_000_000,
                    plannedRequests, hits.size(), notSent, tracesMissing, pending.size(), plan.skipped(), stepViews);
        }

        private Stats stats(List<Hit> list) {
            if (list.isEmpty()) {
                return null;
            }
            List<Long> durations = list.stream().map(Hit::durationNanos).sorted().toList();
            long first = list.stream().mapToLong(h -> h.endNanos() - h.durationNanos()).min().orElse(0);
            long last = list.stream().mapToLong(Hit::endNanos).max().orElse(0);
            double seconds = Math.max(1e-3, (last - first) / 1e9);
            return new Stats(list.size(), LoadAggregate.percentile(durations, 0.50),
                    LoadAggregate.percentile(durations, 0.95), LoadAggregate.percentile(durations, 0.99),
                    durations.getLast(), list.size() / seconds);
        }

        private static Map<String, Integer> statuses(List<Hit> list) {
            Map<String, Integer> counts = new TreeMap<>();
            list.forEach(h -> counts.merge(h.httpStatus() == 0 ? "unreachable" : String.valueOf(h.httpStatus()), 1,
                    Integer::sum));
            return counts;
        }

        private static Map<String, Integer> errors(List<Hit> list) {
            Map<String, Integer> counts = new TreeMap<>();
            list.stream().filter(h -> h.error() != null).forEach(h -> counts.merge(h.error(), 1, Integer::sum));
            return counts;
        }

        /** Hits and p95 per second of the load, so a slowdown over time is visible. */
        private List<Bucket> timeline(List<Hit> load) {
            if (load.isEmpty() || loadStartNanos < 0) {
                return List.of();
            }
            Map<Integer, List<Long>> bySecond = new TreeMap<>();
            load.forEach(h -> bySecond.computeIfAbsent((int) Math.max(0, (h.endNanos() - loadStartNanos) / 1_000_000_000L),
                    s -> new ArrayList<>()).add(h.durationNanos()));
            List<Bucket> buckets = new ArrayList<>();
            bySecond.forEach((second, durations) -> buckets.add(new Bucket(second, durations.size(),
                    LoadAggregate.percentile(durations.stream().sorted().toList(), 0.95))));
            return buckets;
        }
    }

    /** Latencies in nanoseconds, as the server's response time seen by the client. */
    public record Stats(int count, Long p50, Long p95, Long p99, Long max, double perSecond) {
    }

    public record RampView(int concurrency, Stats stats) {
    }

    public record Bucket(int second, int hits, Long p95) {
    }

    /**
     * @param report span-by-span figures; null when the target's traces are not available here
     * @param kept   traces still in the store: the slowest, then the failed
     */
    public record StepView(String spanId, String method, String route, boolean unsafe, long thinkMillis,
            Stats baseline, Stats load, List<RampView> ramp, Map<String, Integer> baselineStatuses,
            Map<String, Integer> loadStatuses, Map<String, Integer> errors, List<Bucket> timeline,
            LoadAggregate.Report report, List<Kept> kept) {
    }

    /**
     * @param analyzed        the hits' traces are in this application's store (target {@code local})
     * @param sent            requests answered or failed so far
     * @param notSent         RATE: iterations that could not start because {@code concurrency} were in flight
     * @param tracesMissing   hits whose server request never reached the store
     * @param tracesPending   hits whose trace is still being waited for
     */
    public record View(String id, State state, String stopReason, String traceId, String spanId, String target,
            Mode mode, Shape shape, int hits, int concurrency, double rate, int durationSeconds, boolean ramp,
            boolean analyzed, String startedAt, long elapsedMillis, long plannedRequests, int sent, int notSent,
            int tracesMissing, int tracesPending, List<Skipped> skipped, List<StepView> steps) {
    }
}
