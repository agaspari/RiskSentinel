package com.risksentinel.core.ops;

import org.slf4j.MDC;

import java.util.Map;
import java.util.Objects;

/**
 * Helpers to carry SLF4J MDC across executor / thread boundaries.
 *
 * <p>The default executor service implementations do not propagate MDC: a
 * {@link Runnable} scheduled on one thread starts on a worker thread with
 * whatever MDC that worker happens to have. Wrap tasks with
 * {@link #wrap(Runnable)} at the submission site to capture the caller's MDC
 * and restore it for the duration of the task.
 *
 * <p>Restoration is symmetric — the previous MDC on the worker thread is
 * preserved and re-instated when the task returns, so subsequent tasks on
 * the same worker are not contaminated.
 */
public final class MdcPropagation {

    private MdcPropagation() {}

    public static Runnable wrap(Runnable delegate) {
        Objects.requireNonNull(delegate, "delegate");
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            applyMdc(capturedMdc);
            try {
                delegate.run();
            } finally {
                applyMdc(previousMdc);
            }
        };
    }

    private static void applyMdc(Map<String, String> snapshot) {
        if (snapshot == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
    }
}
