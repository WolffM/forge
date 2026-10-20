package forge.game.cost;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public abstract class CostDecisionMakerBase implements ICostVisitor<PaymentDecision> {

    protected final Player player;
    protected final SpellAbility ability;
    protected final Card source;
    private boolean effect;

    public CostDecisionMakerBase(Player player0, boolean effect0, SpellAbility ability0, Card source0) {
        player = player0;
        effect = effect0;
        ability = ability0;
        source = source0;
    }

    public Player getPlayer() { return player; }
    public abstract boolean paysRightAfterDecision();

    /**
     * Whether this payer chooses what a cost sacrifices or discards before the ability's targets are
     * chosen, the order MTG Arena asks them in. Only the choice moves: the objects are still paid in
     * rule 601.2h, after the targets (601.2c), and are offered as targets as the rules allow.
     */
    public boolean choosesCostObjectsFirst() {
        return false;
    }
    public boolean isEffect() {
        return effect;
    }
}
