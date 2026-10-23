// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.internal.replay.LoadRunService;
import dev.causeline.spring.internal.replay.ReplayService;
import dev.causeline.spring.internal.replay.ReplayService.ReplayException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Load replay endpoints behind the UI's Load tab. Protected by the access token like all of
 * /causeline/api. Starting a run only checks it and hands it to virtual threads, so no endpoint
 * waits on the network.
 */
@RestController
@RequestMapping("/causeline/api/load")
public class LoadApiController {

    private final LoadRunService runs;

    public LoadApiController(LoadRunService runs) {
        this.runs = runs;
    }

    /** The configured limits, so the form can say what is allowed. */
    @GetMapping("/limits")
    public Limits limits() {
        CauselineProperties.Load limits = runs.limits();
        return new Limits(limits.maxRequests(), limits.maxConcurrency(), limits.maxDuration().toSeconds(),
                LoadRunService.BASELINE_HITS);
    }

    @GetMapping
    public List<LoadRunService.View> recent() {
        return runs.recent().stream().map(LoadRunService.Run::view).toList();
    }

    @PostMapping
    public LoadRunService.View start(@RequestBody StartRequest request) {
        return runs.start(request.settings()).view();
    }

    @GetMapping("/{id}")
    public ResponseEntity<LoadRunService.View> run(@PathVariable("id") String id) {
        return runs.get(id).map(run -> ResponseEntity.ok(run.view())).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<LoadRunService.View> cancel(@PathVariable("id") String id) {
        if (!runs.cancel(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(runs.get(id).orElseThrow().view());
    }

    @ExceptionHandler(ReplayException.class)
    ResponseEntity<Map<String, String>> refused(ReplayException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    public record Limits(int maxRequests, int maxConcurrency, long maxDurationSeconds, int baselineHits) {
    }

    /**
     * @param mode  {@code REQUEST} or {@code FLOW}
     * @param shape {@code CONCURRENCY} or {@code RATE}
     * @param path  like the replay request: edits for the picked request
     */
    public record StartRequest(String traceId, String spanId, String target, LoadRunService.Mode mode,
            LoadRunService.Shape shape, Integer hits, Integer concurrency, Double rate, Integer durationSeconds,
            Boolean ramp, Boolean confirm, String body, String path, String query, Map<String, String> headers) {

        LoadRunService.Settings settings() {
            return new LoadRunService.Settings(traceId, spanId, target, mode, shape, hits == null ? 100 : hits,
                    concurrency == null ? 10 : concurrency, rate == null ? 0 : rate,
                    durationSeconds == null ? 0 : durationSeconds, Boolean.TRUE.equals(ramp),
                    Boolean.TRUE.equals(confirm), body, new ReplayService.Edits(path, query, headers));
        }
    }
}
