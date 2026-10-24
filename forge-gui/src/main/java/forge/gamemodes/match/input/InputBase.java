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
package forge.gamemodes.match.input;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;
import forge.util.Localizer;

import java.util.List;

/**
 * <p>
 * Abstract Input class.
 * </p>
 *
 * @author Forge
 * @version $Id: InputBase.java 24769 2014-02-09 13:56:04Z Hellfish $
 */
public abstract class InputBase implements java.io.Serializable, Input {
    private static final long serialVersionUID = -2531867688249685076L;

    private final PlayerControllerHuman controller;
    public InputBase(final PlayerControllerHuman controller0) {
        controller = controller0;
    }
    public final PlayerControllerHuman getController() {
        return controller;
    }
    @Override
    public PlayerView getOwner() {
        final Player owner = getController().getPlayer();
        return owner == null ? null : owner.getView();
    }

    private boolean finished = false;
    protected final boolean isFinished() { return finished; }
    protected final void setFinished() {
        finished = true;

        if (allowAwaitNextInput()) {
            controller.awaitNextInput();
        }
    }

    protected boolean allowAwaitNextInput() {
        return false;
    }

    // showMessage() is always the first method called
    @Override
    public final void showMessageInitial() {
        finished = false;
        controller.cancelAwaitNextInput();
        if (!offersCards()) {
            controller.pushUntappable();
        }
        showMessage();
    }

    /**
     * Whether this input publishes the cards a click would act on itself ({@code
     * PlayerControllerHuman.pushActionableCards}, which counts the permanents a click would untap).
     * Every other input offers just those ({@link #selectCard}).
     */
    protected boolean offersCards() {
        return false;
    }

    protected abstract void showMessage();

    @Override
    public final void selectPlayer(final Player player, final ITriggerEvent triggerEvent) {
        if (isFinished()) { return; }
        onPlayerSelected(player, triggerEvent);
    }

    @Override
    public boolean selectAbility(final SpellAbility ab) {
        return false;
    }

    @Override
    public final void selectButtonCancel() {
        if (isFinished()) { return; }
        onCancel();
    }

    @Override
    public final void selectButtonOK() {
        if (isFinished()) { return; }
        onOk();
    }

    /**
     * A permanent this player tapped for mana untaps on a click in every input, as strictly as Forge
     * undoes it ({@code PlayerControllerHuman.tryUndo}: its mana unspent, nothing triggered) — while a
     * spell's targets are chosen, its cost paid, a confirm open. Unless the input takes that card
     * itself (a tapped land as a target or a sacrifice); at priority the untap comes first, since the
     * click's other use there is the permanent's own ability ({@link #untapsFirst}).
     */
    @Override
    public final boolean selectCard(final Card c, final List<Card> otherCardsToSelect, final ITriggerEvent triggerEvent) {
        if (isFinished()) { return false; }
        if ((untapsFirst() || getActivateAction(c) == null) && controller.tryUndo(c)) {
            return true;
        }
        return onCardSelected(c, otherCardsToSelect, triggerEvent);
    }

    /** Whether a click untaps before this input's own use of the card ({@link #selectCard}). */
    protected boolean untapsFirst() {
        return false;
    }

    protected boolean onCardSelected(final Card c, final List<Card> otherCardsToSelect, final ITriggerEvent triggerEvent) {
        return false;
    }
    protected void onPlayerSelected(final Player player, final ITriggerEvent triggerEvent) {}
    protected void onCancel() {}
    protected void onOk() {}

    protected final void showMessage(final String message) {
        showMessage(message, (CardView) null);
    }
    protected final void showMessage(final String message, final SpellAbilityView sav) {
        showMessage(message, sav.getHostCard());
    }
    protected final void showMessage(final String message, final CardView card) {
        controller.getGui().showPromptMessage(getOwner(), message, card);
    }

    protected String getTurnPhasePriorityMessage(final Game game) {
        final PhaseHandler ph = game.getPhaseHandler();
        final StringBuilder sb = new StringBuilder();
        Localizer localizer = Localizer.getInstance();
        sb.append(localizer.getMessage("lblPriority")).append(": ").append(ph.getPriorityPlayer()).append("\n");
        sb.append(localizer.getMessage("lblTurn")).append(": ").append(ph.getTurn()).append(" (").append(ph.getPlayerTurn()).append(")");

        if (!game.isNeitherDayNorNight()) {
            sb.append("  [");

            String dayLabel = game.isDay() ? "Day" : "Night";

            sb.append(Localizer.getInstance().getMessage("lbl" + dayLabel));
            sb.append("]");
        }

        sb.append("\n");
        sb.append(localizer.getMessage("lblPhase")).append(": ").append(ph.getPhase().nameForUi).append("\n");
        sb.append(localizer.getMessage("lblStack")).append(": ");
        if (!game.getStack().isEmpty()) {
            sb.append(game.getStack().size()).append(" ").append(localizer.getMessage("lbltoResolve"));
        } else {
            sb.append(localizer.getMessage("lblEmpty"));
        }
        if (FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_SHOW_STORM_COUNT_IN_PROMPT)) {
            int stormCount = game.getView().getStormCount();
            if (stormCount > 0) {
                sb.append("\n").append(localizer.getMessage("lblStormCount")).append(": ").append(stormCount);
            }
        }

        if (controller.macros() != null) {
            boolean isRecording = controller.macros().isRecording();
            String pbText = controller.macros().playbackText();
            if (pbText != null) {
                sb.append("\n");
                if (isRecording) {
                    sb.append("Macro Recording -- ");
                } else {
                    sb.append("Macro Playback -- ");
                }

                sb.append(pbText);
            } else if (isRecording) {
                sb.append("\n").append("Macro Recording -- ");
            }
        }

        return sb.toString();
    }
}
