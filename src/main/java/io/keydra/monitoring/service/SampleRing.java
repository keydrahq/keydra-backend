package io.keydra.monitoring.service;

import io.keydra.engine.MetricsSample;
import java.util.ArrayList;
import java.util.List;

/**
 * The last N readings for one target.
 *
 * <p>A ring rather than a growing list: a dashboard shows a window, and a sampler left running for
 * a week would otherwise hold a week of readings for a chart that draws the last hour. Bounded
 * memory is the point, so the oldest reading is dropped rather than the newest refused.
 *
 * <p>Synchronised because the sampler writes from a scheduler thread while requests read from event
 * loops. The critical sections are two array writes; a lock-free structure here would be more code
 * for no measurable gain.
 */
public final class SampleRing {

    private final MetricsSample[] samples;
    private int next;
    private int size;

    public SampleRing(int capacity) {
        this.samples = new MetricsSample[capacity];
    }

    public synchronized void add(MetricsSample sample) {
        samples[next] = sample;
        next = (next + 1) % samples.length;
        if (size < samples.length) {
            size++;
        }
    }

    /** Oldest first, which is the order a chart plots. */
    public synchronized List<MetricsSample> toList() {
        List<MetricsSample> ordered = new ArrayList<>(size);
        int start = (next - size + samples.length) % samples.length;
        for (int i = 0; i < size; i++) {
            ordered.add(samples[(start + i) % samples.length]);
        }
        return ordered;
    }

    public synchronized MetricsSample latest() {
        return size == 0 ? null : samples[(next - 1 + samples.length) % samples.length];
    }

    public synchronized int size() {
        return size;
    }
}
