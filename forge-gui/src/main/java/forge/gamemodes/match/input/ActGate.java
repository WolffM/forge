package forge.gamemodes.match.input;

import forge.game.Game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
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
    /** The inputs that acts handed the game back to wait on, by the latch each releases. */
    private final List<CountDownLatch> parked = new ArrayList<>();
    /** Every input some thread is waiting on — the game thread's and the parked acts' — by its latch. */
    private final List<CountDownLatch> awaited = new ArrayList<>();

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

    /** Waits for an input's latch; an act holding the game hands it back for the wait. */
    public void await(final CountDownLatch latch) throws InterruptedException {
        final boolean parkedHere = startWait(latch);
        try {
            latch.await();
        } finally {
            endWait(latch, parkedHere);
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
        while (ticket != serving || owner != null || resumable()) {
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
