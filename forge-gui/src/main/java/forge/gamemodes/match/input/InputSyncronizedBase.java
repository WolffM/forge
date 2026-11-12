package forge.gamemodes.match.input;

import forge.util.IHasForgeLog;
import forge.gamemodes.match.Selection;
import forge.gui.FThreads;
import forge.gui.error.BugReporter;
import forge.player.PlayerControllerHuman;

import java.util.concurrent.CountDownLatch;

public abstract class InputSyncronizedBase extends InputBase implements InputSynchronized, IHasForgeLog {

    private static final long serialVersionUID = 8756177361251703052L;
    private final CountDownLatch cdlDone;

    public InputSyncronizedBase(final PlayerControllerHuman controller) {
        super(controller);
        cdlDone = new CountDownLatch(1);
    }

    @Override
    public void awaitLatchRelease() {
        FThreads.assertExecutedByEdt(false);
        netLog.trace("awaitLatchRelease() starting on {}, thread = {}", this.getClass().getSimpleName(), Thread.currentThread().getName());
        try {
            ActGate.of(getController().getGame()).await(cdlDone, getController().getGui()::awaitInput);
        } catch (final InterruptedException e) {
            BugReporter.reportException(e);
        }
        netLog.trace("awaitLatchRelease() UNBLOCKED on {}, thread = {}", this.getClass().getSimpleName(), Thread.currentThread().getName());
    }

    @Override
    public final void relaseLatchWhenGameIsOver() {
        cdlDone.countDown();
    }

    /** Whether this input is done: stopped, or let go at the game's end. */
    public final boolean isDone() {
        return cdlDone.getCount() == 0;
    }

    public void showAndWait() {
        getController().getInputQueue().setInput(this);
        awaitLatchRelease();
    }

    @Override
    public final void stop() {
        // not while this input is being shown: its show walks the views, and the game thread walks them once released
        ActGate.of(getController().getGame()).withViews(this::stopNow);
    }

    private void stopNow() {
        onStop();
        getController().getGui().setSelection(Selection.ofChosen(getController().getKeptMarks()));

        // ensure input won't accept any user actions.
        FThreads.invokeInEdtNowOrLater(this::setFinished);

        // thread irrelevant
        if (getController().getInputQueue().getInput() != null) {
            getController().getInputQueue().removeInput(InputSyncronizedBase.this);
        }
        cdlDone.countDown();
    }

    protected void onStop() { }
}
