// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import dev.causeline.spring.internal.capture.ValueRenderer;
import dev.causeline.spring.internal.tracing.MethodValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;

/**
 * Stops a paused replay at the controller and {@code @Observed} service methods the developer chose,
 * shows their arguments, and runs them with the arguments the developer left or changed.
 *
 * <p>Outside a replay session this costs one thread-local read. It runs innermost, inside the
 * method's own span, so the span shows the arguments the method actually ran with, marked as edited.
 */
@Aspect
public final class ReplayPauseAspect implements Ordered {

    public static final String EDITED = "causeline.replay.edited";

    /** How {@link ValueRenderer} shows a value it won't expand, e.g. {@code "[HttpServletRequest]"}. */
    private static final Pattern BY_TYPE_ONLY = Pattern.compile("^\"\\[[^\\]]*\\]\"$");

    private final ObservationRegistry registry;
    private final ValueRenderer renderer;

    public ReplayPauseAspect(ObservationRegistry registry, ValueRenderer renderer) {
        this.registry = registry;
        this.renderer = renderer;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Around("(@within(org.springframework.web.bind.annotation.RestController)"
            + " || @within(org.springframework.stereotype.Controller)"
            + " || @annotation(io.micrometer.observation.annotation.Observed)"
            + " || @within(io.micrometer.observation.annotation.Observed))"
            + " && !within(dev.causeline.spring..*)")
    public Object pauseIfChosen(ProceedingJoinPoint pjp) throws Throwable {
        ReplaySessions.Session session = ReplaySessions.current();
        if (session == null || !(pjp.getSignature() instanceof MethodSignature signature)) {
            return pjp.proceed();
        }
        Method method = signature.getMethod();
        String className = signature.getDeclaringTypeName();
        int occurrence = session.shouldPause(className, method.getName());
        if (occurrence == 0) {
            return pjp.proceed();
        }
        Object[] original = pjp.getArgs();
        String step = className.substring(className.lastIndexOf('.') + 1) + "." + method.getName();
        Object[] chosen = session.pause(new ReplaySessions.Paused(step, className, method.getName(), occurrence,
                describe(method, original), Instant.now(), method, original));
        if (chosen != original && !Arrays.equals(chosen, original)) {
            Observation current = registry.getCurrentObservation();
            if (current != null) {
                current.highCardinalityKeyValue(MethodValues.ARGUMENTS, renderer.arguments(method, chosen));
                current.highCardinalityKeyValue(EDITED, "true");
            }
        }
        return pjp.proceed(chosen);
    }

    private List<ReplaySessions.Argument> describe(Method method, Object[] values) {
        Parameter[] parameters = method.getParameters();
        List<ReplaySessions.Argument> arguments = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            String json = renderer.value(values[i]);
            arguments.add(new ReplaySessions.Argument(i < parameters.length ? parameters[i].getName() : "arg" + i,
                    method.getGenericParameterTypes()[i].getTypeName(), json, !BY_TYPE_ONLY.matcher(json).matches()));
        }
        return arguments;
    }
}
