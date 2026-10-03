// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Replays that stop at chosen steps, like breakpoints, so the developer can see and change a
 * method's arguments before it runs.
 *
 * <p>A session is created through the token-protected API and named in the replayed request's
 * {@value #HEADER} header. The servlet capture filter binds it to the request thread
 * ({@link #bind}); {@code ReplayPauseAspect} then counts calls to each method and, at a chosen
 * step, parks the thread until the developer continues, or {@link #pauseTimeout} passes. Nothing
 * else ever waits: requests without the header, threads the request hands work to, and WebFlux
 * applications are never paused.
 */
public final class ReplaySessions {

    public static final String HEADER = "X-Causeline-Replay-Session";

    static final int MAX_SESSIONS = 20;
    static final Duration KEEP = Duration.ofMinutes(30);

    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    private final Duration pauseTimeout;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public ReplaySessions(Duration pauseTimeout) {
        this.pauseTimeout = pauseTimeout;
    }

    public Duration pauseTimeout() {
        return pauseTimeout;
    }

    /** A step to stop at: the {@code occurrence}-th call (from 1) of {@code className.method}. */
    public record Breakpoint(String className, String method, int occurrence) {

        String key() {
            return className + "." + method;
        }
    }

    public enum State {
        /** The replay request is on its way or running. */
        RUNNING,
        /** Stopped at a step, waiting for the developer. */
        PAUSED,
        /** The replay finished; {@link Session#outcome()} has the result. */
        DONE,
        FAILED
    }

    /**
     * One argument of the paused method.
     *
     * @param json     its current value, as JSON
     * @param editable false for values shown by type only (requests, streams, files)
     */
    public record Argument(String name, String type, String json, boolean editable) {
    }

    /** Where the replay stopped. {@code method} and {@code values} stay on the server. */
    public record Paused(String step, String className, String methodName, int occurrence, List<Argument> arguments,
            Instant since, Method method, Object[] values) {
    }

    public synchronized Session create(String traceId, String spanId, List<Breakpoint> breakpoints) {
        Instant cutoff = Instant.now().minus(KEEP);
        sessions.values().removeIf(s -> s.created.isBefore(cutoff) && s.state != State.PAUSED);
        if (sessions.size() >= MAX_SESSIONS) {
            sessions.remove(sessions.keySet().iterator().next());
        }
        Session session = new Session(UUID.randomUUID().toString(), traceId, spanId, breakpoints, pauseTimeout);
        sessions.put(session.id, session);
        return session;
    }

    public synchronized Optional<Session> get(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    /** Called by the capture filter for a request carrying {@value #HEADER}; false when the session is unknown. */
    public boolean bind(String id) {
        Optional<Session> session = get(id);
        session.ifPresent(CURRENT::set);
        return session.isPresent();
    }

    public static void unbind() {
        CURRENT.remove();
    }

    /** The session of the request running on this thread, or null: the common case, and the cheap one. */
    public static Session current() {
        return CURRENT.get();
    }

    /** A replay with breakpoints. Its methods are called from the request thread and from the API. */
    public static final class Session {

        private final String id;
        private final String traceId;
        private final String spanId;
        private final Map<String, Set<Integer>> stops = new HashMap<>();
        private final Map<String, Integer> calls = new HashMap<>();
        private final Duration pauseTimeout;
        private final Instant created = Instant.now();
        private final List<String> edited = new ArrayList<>();
        private volatile State state = State.RUNNING;
        private volatile Paused paused;
        private volatile CompletableFuture<Object[]> resume;
        private volatile boolean skipRest;
        private volatile String replayTraceId;
        private volatile ReplayService.Outcome outcome;
        private volatile String error;

        Session(String id, String traceId, String spanId, List<Breakpoint> breakpoints, Duration pauseTimeout) {
            this.id = id;
            this.traceId = traceId;
            this.spanId = spanId;
            this.pauseTimeout = pauseTimeout;
            for (Breakpoint b : breakpoints) {
                stops.computeIfAbsent(b.key(), k -> new java.util.HashSet<>()).add(b.occurrence());
            }
        }

        public String id() {
            return id;
        }

        public String traceId() {
            return traceId;
        }

        public String spanId() {
            return spanId;
        }

        public State state() {
            return state;
        }

        public Paused paused() {
            return paused;
        }

        public String replayTraceId() {
            return replayTraceId;
        }

        public ReplayService.Outcome outcome() {
            return outcome;
        }

        public String error() {
            return error;
        }

        /** Steps whose arguments were changed, e.g. {@code OrderService.createOrder #1}. */
        public synchronized List<String> edited() {
            return List.copyOf(edited);
        }

        /** Counts this call and says whether the replay should stop here; returns the occurrence, or 0. */
        public synchronized int shouldPause(String className, String method) {
            if (state == State.DONE || state == State.FAILED) {
                return 0;
            }
            String key = className + "." + method;
            int occurrence = calls.merge(key, 1, Integer::sum);
            Set<Integer> wanted = stops.get(key);
            return !skipRest && wanted != null && wanted.contains(occurrence) ? occurrence : 0;
        }

        /**
         * Parks the request thread at this step until the developer continues or the pause times out.
         *
         * @return the arguments to run the method with: changed ones, or the originals
         */
        public Object[] pause(Paused step) {
            CompletableFuture<Object[]> next = new CompletableFuture<>();
            synchronized (this) {
                resume = next;
                paused = step;
                state = State.PAUSED;
            }
            try {
                Object[] values = next.get(pauseTimeout.toMillis(), TimeUnit.MILLISECONDS);
                return values == null ? step.values() : values;
            } catch (TimeoutException e) {
                return step.values(); // nobody answered: carry on unchanged, as a debugger detach would
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return step.values();
            } catch (ExecutionException e) {
                return step.values();
            } finally {
                synchronized (this) {
                    paused = null;
                    resume = null;
                    if (state == State.PAUSED) {
                        state = State.RUNNING;
                    }
                }
            }
        }

        /**
         * Continues a paused replay.
         *
         * @param values   the arguments to use; null to keep the originals
         * @param skipRest true to run the rest of the replay without stopping again
         * @return false when the replay was not paused
         */
        public synchronized boolean resume(Object[] values, boolean skipRest, boolean changed) {
            if (state != State.PAUSED || resume == null || paused == null) {
                return false;
            }
            this.skipRest = this.skipRest || skipRest;
            if (changed) {
                edited.add(paused.step() + " #" + paused.occurrence());
            }
            resume.complete(values);
            return true;
        }

        public void started(String replayTraceId) {
            this.replayTraceId = replayTraceId;
        }

        public void finished(ReplayService.Outcome outcome) {
            this.outcome = outcome;
            this.state = State.DONE;
        }

        public void failed(String error) {
            this.error = error;
            this.state = State.FAILED;
            CompletableFuture<Object[]> pending = resume;
            if (pending != null) {
                pending.complete(null);
            }
        }
    }
}
