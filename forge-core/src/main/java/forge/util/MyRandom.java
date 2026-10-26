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
import java.util.Random;
import java.util.concurrent.Callable;

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
    private static volatile Random random = new SecureRandom();

    /** The random of the game this thread is working for, if any. */
    private static final ThreadLocal<Random> BOUND = new ThreadLocal<>();

    /** Ends a {@link #bind} scope, restoring the binding it replaced. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * <p>
     * percentTrue.<br>
     * If percent is like 30, then 30% of the time it will be true.
     * </p>
     * 
     * @param percent an int.
     * @return a boolean.
     */
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
        MyRandom.random = random;
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
