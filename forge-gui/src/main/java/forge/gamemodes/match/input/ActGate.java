package forge.gamemodes.match.input;

import forge.game.Game;
import forge.player.PlayerControllerHuman;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * One game's player acts — clicks and button presses from every seat — taken one at a time, in the
 * order they arrived. Two acts side by side ran two Input handlers at once on one game and could
 * leave a view unserializable.
 *
 * An act that waits on an input of its own (a Signet's {1} paid inside a payment) hands the game to
 * the acts that answer that input, and takes it back, ahead of every queued act, once the input is
 * done. Holding the game through that wait froze the seat: the answering click queued behind it.
 *
 * An act that comes between two inputs — the last one done, the game not yet waiting on the next —
 * is held until the game waits on an input again, then taken by it. Taken at once, it found no
 * input and was dropped: a creature clicked right after a land play never cast.
 *
 * The game's views are walked by one of the threads that are not the game's own at a time
 * ({@link #withViews}): a prompt being shown on the EDT, and an act, whose input's stop is what
 * releases the game thread. An act answers a prompt within milliseconds, and its stop walked the
 * views, and released the game thread to walk them, while the EDT still walked them to show that
 * prompt: whichever thread was iterating died with a ConcurrentModificationException.
 */
public final class ActGate {

    private static final Map<Game, ActGate> GATES = new WeakHashMap<>();

    public static ActGate of(final Game game) {
        synchronized (GATES) {
            return GATES.computeIfAbsent(game, g -> new ActGate());
        }
    }

    private long issued;
    private long serving;
    private Thread owner;
    /** Acts waiting to go ahead of the queue ({@link #runFirst}): a queued act waits for them too. */
    private int first;
    /** The inputs that acts handed the game back to wait on, by the latch each releases. */
    private final List<CountDownLatch> parked = new ArrayList<>();
    /** Every input some thread is waiting on — the game thread's and the parked acts' — by its latch. */
    private final List<CountDownLatch> awaited = new ArrayList<>();
    private final ReentrantLock views = new ReentrantLock();

    /** The longest an act that came between two inputs is held for the next one. */
    private static final long BETWEEN_INPUTS_MS = 5_000;

    /** A seat's act, in its turn ({@link #run}), once the game waits on an input ({@link #settle}). */
    public void act(final PlayerControllerHuman seat, final long ticket, final Runnable act) {
        run(ticket, () -> settleThenRun(seat, act));
    }

    /** A seat's act that goes ahead of every queued one ({@link #runFirst}): a concede. */
    public void actFirst(final PlayerControllerHuman seat, final Runnable act) {
        runFirst(() -> settleThenRun(seat, act));
    }

    private void settleThenRun(final PlayerControllerHuman seat, final Runnable act) {
        settle(() -> {
            if (seat.getGame().isGameOver()) {
                return true;
            }
            final Input input = seat.getInputProxy().getInput();
            return input != null && !(input instanceof InputLockUI);
        }, BETWEEN_INPUTS_MS);
        // The game thread no longer waits on an input, and walks the views to send the game's end:
        // an act that comes after it has nothing to answer, and its walk died beside that one
        if (seat.getGame().isGameOver()) {
            return;
        }
        withViews(act);
    }

    /**
     * Runs {@code walk} while no other thread but the game's walks the views. Reentrant: a prompt's
     * show can stop its own input, and an act's stop is inside the act.
     */
    public void withViews(final Runnable walk) {
        views.lock();
        try {
            walk.run();
        } finally {
            views.unlock();
        }
    }

    /** Taken on the thread that received the act, so tickets follow arrival order. */
    public synchronized long ticket() {
        return issued++;
    }

    public void run(final long ticket, final Runnable act) {
        enter(ticket);
        try {
            act.run();
        } finally {
            leave();
        }
    }

    /**
     * An act that takes the game ahead of every queued one, as soon as no act holds it: a concede.
     * It still runs one at a time with the rest. Queued behind a seat's own presses it waited for
     * each of them, up to its hold apiece, and a seat that had pressed a re-armed button again and
     * again conceded into a game that went on without it.
     */
    public void runFirst(final Runnable act) {
        enterFirst();
        try {
            act.run();
        } finally {
            leave();
        }
    }

    /** How a thread waits for an input's latch: the GUI's own way ({@code IGuiGame.awaitInput}). */
    @FunctionalInterface
    public interface Waiter {
        void await(CountDownLatch latch) throws InterruptedException;
    }

    /** Waits for an input's latch; an act holding the game hands it back for the wait. */
    public void await(final CountDownLatch latch) throws InterruptedException {
        await(latch, CountDownLatch::await);
    }

    /** Waits for an input's latch the way {@code waiter} does; an act holding the game hands it back for the wait. */
    public void await(final CountDownLatch latch, final Waiter waiter) throws InterruptedException {
        // An act waiting on an input lets that input be shown, and the acts that answer it walk
        final int held = views.getHoldCount();
        for (int i = 0; i < held; i++) {
            views.unlock();
        }
        final boolean parkedHere = startWait(latch);
        try {
            waiter.await(latch);
        } finally {
            endWait(latch, parkedHere);
            for (int i = 0; i < held; i++) {
                views.lock();
            }
        }
    }

    /**
     * Holds the act that holds the game while it came between two inputs: until {@code inputLive},
     * or the game waits on any input, or {@code capMs} passes.
     */
    public synchronized void settle(final BooleanSupplier inputLive, final long capMs) {
        final long until = System.currentTimeMillis() + capMs;
        while (!inputLive.getAsBoolean() && !waitingOnInput()) {
            final long left = until - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            try {
                wait(left);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void enter(final long ticket) {
        boolean interrupted = false;
        while (ticket != serving || owner != null || first > 0 || resumable()) {
            try {
                wait();
            } catch (final InterruptedException e) {
                interrupted = true;
            }
        }
        serving++;
        owner = Thread.currentThread();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void enterFirst() {
        boolean interrupted = false;
        first++;
        while (owner != null || resumable()) {
            try {
                wait();
            } catch (final InterruptedException e) {
                interrupted = true;
            }
        }
        first--;
        owner = Thread.currentThread();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void leave() {
        owner = null;
        notifyAll();
    }

    private synchronized boolean startWait(final CountDownLatch latch) {
        awaited.add(latch);
        notifyAll();
        if (owner != Thread.currentThread()) {
            return false;
        }
        parked.add(latch);
        owner = null;
        return true;
    }

    private synchronized void endWait(final CountDownLatch latch, final boolean parkedHere) {
        awaited.remove(latch);
        if (!parkedHere) {
            return;
        }
        boolean interrupted = false;
        while (owner != null) {
            try {
                wait();
            } catch (final InterruptedException e) {
                interrupted = true;
            }
        }
        parked.remove(latch);
        owner = Thread.currentThread();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean waitingOnInput() {
        for (final CountDownLatch latch : awaited) {
            if (latch.getCount() > 0) {
                return true;
            }
        }
        return false;
    }

    /** An input an act waits on is done: that act resumes before any queued act starts. */
    private boolean resumable() {
        for (final CountDownLatch latch : parked) {
            if (latch.getCount() == 0) {
                return true;
            }
        }
        return false;
    }
}
