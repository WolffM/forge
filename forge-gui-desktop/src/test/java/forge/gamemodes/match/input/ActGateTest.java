package forge.gamemodes.match.input;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * ActGate's order: acts one at a time in arrival order, and a concede ahead of every queued one.
 * A seat that had pressed a re-armed button again and again queued those presses, and its concede
 * waited behind every one of them (cardtable monkey run 42340666519: 146 acts).
 */
public class ActGateTest {

    @Test
    public void aConcedeGoesAheadOfQueuedActsButNotBesideTheOneRunning() throws Exception {
        final ActGate gate = ActGate.of(null);
        final List<String> order = new CopyOnWriteArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        final long first = gate.ticket();
        final Thread a = new Thread(() -> gate.run(first, () -> {
            order.add("held");
            started.countDown();
            try {
                release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.add("held done");
        }));
        a.start();
        Assert.assertTrue(started.await(5, TimeUnit.SECONDS));

        final Thread[] queued = new Thread[3];
        for (int i = 0; i < queued.length; i++) {
            final long ticket = gate.ticket();
            final String name = "press " + i;
            queued[i] = new Thread(() -> gate.run(ticket, () -> order.add(name)));
            queued[i].start();
        }
        final Thread concede = new Thread(() -> gate.runFirst(() -> order.add("concede")));
        concede.start();
        Thread.sleep(300);
        Assert.assertEquals(order, List.of("held"), "nothing runs beside the act that holds the game");

        release.countDown();
        a.join(5_000);
        concede.join(5_000);
        for (final Thread t : queued) {
            t.join(5_000);
        }
        Assert.assertEquals(order, List.of("held", "held done", "concede", "press 0", "press 1", "press 2"));
    }
}
