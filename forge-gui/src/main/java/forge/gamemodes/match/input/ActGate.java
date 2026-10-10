package forge.gamemodes.match.input;

import forge.game.Game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * One game's player acts — clicks and button presses from every seat — taken one at a time, in the
 * order they arrived. Two acts side by side ran two Input handlers at once on one game and could
 * leave a view unserializable.
 *
 * An act that waits on an input of its own (a Signet's {1} paid inside a payment) hands the game to
 * the acts that answer that input, and takes it back, ahead of every queued act, once the input is
 * done. Holding the game through that wait froze the seat: the answering click queued behind it.
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
        if (!park(latch)) {
            latch.await();
            return;
        }
        try {
            latch.await();
        } finally {
            unpark(latch);
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

    private synchronized boolean park(final CountDownLatch latch) {
        if (owner != Thread.currentThread()) {
            return false;
        }
        parked.add(latch);
        owner = null;
        notifyAll();
        return true;
    }

    private synchronized void unpark(final CountDownLatch latch) {
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
