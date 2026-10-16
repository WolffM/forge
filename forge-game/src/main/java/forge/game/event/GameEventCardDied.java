package forge.game.event;

import forge.game.card.CardView;

/**
 * A permanent went to its owner's graveyard from the battlefield, and why. Fired once the move has
 * happened, so a destroy that was replaced or regenerated never fires it.
 */
public record GameEventCardDied(CardView card, Cause cause) implements GameEvent {

    public enum Cause {
        /** Destroyed by an effect: a spell or ability said "destroy". */
        DESTROYED,
        /** Destroyed by the state-based action for lethal damage (rule 704.5g). */
        LETHAL_DAMAGE,
        /** Destroyed by the state-based action for deathtouch damage (rule 704.5h). */
        DEATHTOUCH,
        /** Put into the graveyard for toughness 0 or less (rule 704.5f). */
        ZERO_TOUGHNESS,
        /** Put into the graveyard by another state-based action: the legend, world or planeswalker rule, an aura with nothing to enchant. */
        STATE_RULE,
        /** Sacrificed. */
        SACRIFICED
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return card + " died: " + cause;
    }
}
