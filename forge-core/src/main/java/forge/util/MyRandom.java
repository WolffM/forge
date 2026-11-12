/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.util;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * <p>
 * MyRandom class.<br>
 * Preferably all Random numbers should be retrieved using this wrapper class
 * </p>
 *
 * <p>Every game draws from its own {@code Random} (see {@code Match.getRandom()}), so several
 * games can share one JVM without consuming each other's randomness and a seeded game is
 * reproducible. Code runs "for a game" when its thread is <em>bound</em> to that game's
 * {@code Random}: {@link #bind} does it for a scope, and {@link #propagate} carries the
 * submitting thread's binding into a task handed to another thread or a pool, at submission
 * (pool threads are reused, so a binding inherited at thread creation would be wrong).
 * {@link #getRandom()} answers the bound {@code Random}, or the JVM-wide one outside any game
 * (deck generation, drafts, booster packs).</p>
 *
 * @author Forge
 * @version $Id$
 */
public class MyRandom {
    /** The JVM-wide random, for code that runs outside any game. */
    private static volatile Random random = new CountingRandom(new SecureRandom());

    /** Every Random method (nextInt, nextBoolean, shuffle, ...) funnels through next(bits), so this counts every draw. */
    private static final class CountingRandom extends Random {
        private final Random delegate;
        private final AtomicLong calls = new AtomicLong();
        CountingRandom(Random delegate) { super(0); this.delegate = delegate; }
        @Override protected int next(int bits) {
            count();
            return delegate.nextInt() >>> (32 - bits);
        }
        @Override public void setSeed(long seed) {
            if (delegate != null) delegate.setSeed(seed);   // null during super(0)
        }
        // Every public draw goes to the delegate's own method, so a delegate that overrides one (a host's rigged
        // nextInt(bound)) is still asked; Random's versions would only reach the delegate through next(bits).
        private void count() { calls.incrementAndGet(); if (TRACE) { trace(); } }
        @Override public int nextInt() { count(); return delegate.nextInt(); }
        @Override public int nextInt(final int bound) { count(); return delegate.nextInt(bound); }
        @Override public int nextInt(final int origin, final int bound) { count(); return delegate.nextInt(origin, bound); }
        @Override public long nextLong() { count(); return delegate.nextLong(); }
        @Override public long nextLong(final long bound) { count(); return delegate.nextLong(bound); }
        @Override public long nextLong(final long origin, final long bound) { count(); return delegate.nextLong(origin, bound); }
        @Override public boolean nextBoolean() { count(); return delegate.nextBoolean(); }
        @Override public float nextFloat() { count(); return delegate.nextFloat(); }
        @Override public double nextDouble() { count(); return delegate.nextDouble(); }
        @Override public double nextGaussian() { count(); return delegate.nextGaussian(); }
        @Override public void nextBytes(final byte[] bytes) { count(); delegate.nextBytes(bytes); }
    }

    /** {@code r}, wrapped so its draws are counted ({@link #callCount}); the stream it draws is unchanged. */
    public static Random counted(final Random r) {
        return r instanceof CountingRandom ? r : new CountingRandom(r);
    }

    /**
     * Number of draws made so far from the random this thread draws from ({@link #getRandom}): the bound game's own
     * random inside a game, the JVM-wide one outside. Undo reads it to detect that randomness was consumed. A random
     * that was bound without {@link #counted} (a simulation's private stream) reports 0.
     */
    public static long callCount() {
        return getRandom() instanceof CountingRandom c ? c.calls.get() : 0;
    }

    // Debug only (-Dforge.undo.debug=true): tally draws by thread and call site, to find who consumes randomness.
    private static final boolean TRACE = Boolean.getBoolean("forge.undo.debug");
    private static final Map<String, LongAdder> SITES = new ConcurrentHashMap<>();

    private static void trace() {
        final StringBuilder key = new StringBuilder(Thread.currentThread().getName()).append(" <- ");
        int shown = 0;
        for (final StackTraceElement e : new Throwable().getStackTrace()) {
            final String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("forge.util.MyRandom")) { continue; }
            key.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName())
                    .append(':').append(e.getLineNumber()).append(shown < 3 ? " < " : "");
            if (++shown == 4) { break; }
        }
        SITES.computeIfAbsent(key.toString(), k -> new LongAdder()).increment();
    }

    /** Debug only: the {@code top} busiest draw sites since the last call, as text; clears the tally. */
    public static String drainTrace(final int top) {
        final StringBuilder sb = new StringBuilder();
        SITES.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(top)
                .forEach(e -> sb.append("\n    ").append(e.getValue().sum()).append("x ").append(e.getKey()));
        SITES.clear();
        return sb.toString();
    }

    /** The random of the game this thread is working for, if any. */
    private static final ThreadLocal<Random> BOUND = new ThreadLocal<>();

    /** Ends a {@link #bind} scope, restoring the binding it replaced. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static boolean percentTrue(final int percent) {
        return percent > MyRandom.getRandom().nextInt(100);
    }

    /**
     * The random of the game this thread works for, or the JVM-wide random outside any game.
     *
     * @return the random
     */
    public static Random getRandom() {
        final Random bound = BOUND.get();
        return bound != null ? bound : MyRandom.random;
    }

    /** The random this thread is bound to, or {@code null} when it works for no game. */
    public static Random getBound() {
        return BOUND.get();
    }

    /**
     * Sets the JVM-wide random, which code outside any game draws from. A thread bound to a
     * game keeps drawing from that game's random; to swap it, {@link #bind} another.
     * @param random the random
     */
    public static void setRandom(Random random) {
        MyRandom.random = counted(random);
    }

    /**
     * Binds this thread to {@code r} until the returned scope is closed; {@code null} unbinds
     * it for the scope. Scopes nest: closing one restores the binding it replaced.
     */
    public static Scope bind(final Random r) {
        final Random prev = BOUND.get();
        set(r);
        return () -> set(prev);
    }

    private static void set(final Random r) {
        if (r == null) {
            BOUND.remove();
        } else {
            BOUND.set(r);
        }
    }

    /** {@code task}, run bound to {@code r} on whatever thread runs it. */
    public static Runnable bindTo(final Random r, final Runnable task) {
        return () -> {
            try (Scope ignored = bind(r)) {
                task.run();
            }
        };
    }

    /** {@code task}, run bound to {@code r} on whatever thread runs it. */
    public static <T> Callable<T> bindTo(final Random r, final Callable<T> task) {
        return () -> {
            try (Scope ignored = bind(r)) {
                return task.call();
            }
        };
    }

    /** {@code task}, run with the binding the calling thread has now, wherever it runs later. */
    public static Runnable propagate(final Runnable task) {
        return bindTo(BOUND.get(), task);
    }

    /** {@code task}, run with the binding the calling thread has now, wherever it runs later. */
    public static <T> Callable<T> propagate(final Callable<T> task) {
        return bindTo(BOUND.get(), task);
    }

    public static int[] splitIntoRandomGroups(final int value, final int numGroups) {
        int[] groups = new int[numGroups];
        final Random r = getRandom();

        for (int i = 0; i < value; i++) {
            groups[r.nextInt(numGroups)]++;
        }

        return groups;
    }
}
