package forge.gamemodes.match;

import forge.game.card.CardView;
import forge.game.spellability.StackItemView;
import forge.trackable.TrackableObject;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * What the waiting prompt marks: the cards (with how many to pick), players and stack items it accepts, what is already
 * chosen, and the cards worth acting on. A card listed twice in {@code actionable} is marked more strongly.
 * {@code input} names the input that sent it ({@code InputBase.getSelectionId}), 0 for none: a client that hears
 * it late can tell a selection for the input it already has from one for the next.
 */
public record Selection(List<CardView> cards, int min, int max, List<TrackableObject> targets,
                        List<TrackableObject> chosen, List<CardView> actionable, int input) implements Serializable {
    @Serial
    private static final long serialVersionUID = 2L;

    public Selection(final List<CardView> cards, final int min, final int max, final List<TrackableObject> targets,
                     final List<TrackableObject> chosen, final List<CardView> actionable) {
        this(cards, min, max, targets, chosen, actionable, 0);
    }

    public static final Selection NONE = new Selection(List.of(), 0, 0, List.of(), List.of(), List.of());

    public static Selection ofChosen(final List<? extends TrackableObject> chosen) {
        return new Selection(List.of(), 0, 0, List.of(), new ArrayList<>(chosen), List.of());
    }

    public Selection withChosen(final List<? extends TrackableObject> more) {
        if (more.isEmpty()) {
            return this;
        }
        final List<TrackableObject> all = new ArrayList<>(chosen);
        all.addAll(more);
        return new Selection(cards, min, max, targets, all, actionable, input);
    }

    public Selection withActionable(final List<CardView> actionable) {
        return new Selection(cards, min, max, targets, chosen, actionable, input);
    }

    public Selection forInput(final int input) {
        return new Selection(cards, min, max, targets, chosen, actionable, input);
    }

    public boolean offersStackItems() {
        return targets.stream().anyMatch(StackItemView.class::isInstance);
    }
}
