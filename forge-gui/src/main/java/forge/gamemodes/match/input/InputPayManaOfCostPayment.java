package forge.gamemodes.match.input;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPayLife;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;
import forge.util.Localizer;

import java.util.ArrayList;
import java.util.List;

public class InputPayManaOfCostPayment extends InputPayMana {

    public InputPayManaOfCostPayment(final PlayerControllerHuman controller, ManaCostBeingPaid cost, SpellAbility spellAbility, Player payer, ManaConversionMatrix matrix, boolean effect) {
        super(controller, spellAbility, payer, effect);
        manaCost = cost;
        extraMatrix = matrix;
        applyMatrix();

        // CR 118.3c forced cast must use pool mana
        // TODO this introduces a small risk for illegal payments if the human "wastes" enough mana for abilities like Doubling Cube
        if (spellAbility.getPayCosts().isMandatory()) {
            List<Mana> refund = new ArrayList<>();
            mandatory = payer.getManaPool().payManaCostFromPool(new ManaCostBeingPaid(cost), spellAbility, true, refund);
            payer.getManaPool().refundMana(refund);
        }

        // Set Mana cost being paid for SA to be able to reference it later
        player.pushPaidForSA(saPaidFor);
        saPaidFor.setManaCostBeingPaid(manaCost);
    }

    private static final long serialVersionUID = 3467312982164195091L;
    private ManaConversionMatrix extraMatrix;

    @Override
    protected final void onPlayerSelected(Player selected, final ITriggerEvent triggerEvent) {
        if (player == selected) {
            if (player.canPayLife(this.phyLifeToLose + 2, this.effect, saPaidFor)) {
                if (manaCost.payPhyrexian()) {
                    saPaidFor.setSpendPhyrexianMana(true);
                    this.phyLifeToLose += 2;
                    this.showMessage();
                    return;
                } else if (player.hasKeyword("PayLifeInsteadOf:B") && manaCost.hasAnyKind(ManaAtom.BLACK)) {
                    manaCost.decreaseShard(ManaCostShard.BLACK, 1);
                    this.phyLifeToLose += 2;
                    this.showMessage();
                    return;
                }
            }
            final SpellAbility lifeMana = lifeManaAbility();
            if (lifeMana != null) {
                payWithLifeMana(lifeMana);
                return;
            }
            this.showMessage();
        }
    }

    /**
     * A mana ability paid only with life whose card has no place on the table to click: one in the
     * player's Command zone, as Channel's effect is ("Pay 1 life: Add {C}"). The life total is
     * where it is used, one activation per click, as a Phyrexian shard is paid. Null when there is
     * none, or none makes mana the rest of the cost takes, or the life is not there to pay.
     */
    private SpellAbility lifeManaAbility() {
        final byte colorCanUse = colorsPayable();
        if (colorCanUse == 0) {
            return null;
        }
        for (final Card card : player.getCardsIn(ZoneType.Command)) {
            for (final SpellAbility ma : getAllManaAbilities(card)) {
                final int life = lifeOnlyCost(ma.getPayCosts(), ma);
                if (life > 0 && player.canPayLife(this.phyLifeToLose + life, this.effect, ma) && ma.isManaAbilityFor(saPaidFor, colorCanUse)) {
                    return ma;
                }
            }
        }
        return null;
    }

    /** The life a cost made only of life payments takes, or 0 when any other part is in it. */
    private static int lifeOnlyCost(final Cost cost, final SpellAbility sa) {
        if (cost == null) {
            return 0;
        }
        int life = 0;
        for (final CostPart part : cost.getCostParts()) {
            if (!(part instanceof CostPayLife)) {
                return 0;
            }
            life += part.getAbilityAmount(sa);
        }
        return life;
    }

    /**
     * Activate {@code ma} for this payment the way a click on its card does, with its life cost
     * taken as chosen: the click on the life total was the choice, so Forge does not ask again
     * whether to pay it.
     */
    private void payWithLifeMana(final SpellAbility ma) {
        game.getAction().invoke(() -> {
            final Cost cost = ma.getPayCosts();
            final boolean wasMandatory = cost.isMandatory();
            cost.setMandatory(true);
            try {
                activateManaAbility(ma.getHostCard(), ma);
            } finally {
                cost.setMandatory(wasMandatory);
            }
        });
    }

    @Override
    protected void done() {
        if (this.phyLifeToLose > 0) {
            player.payLife(this.phyLifeToLose, saPaidFor, this.effect);
        }
    }

    @Override
    protected void onCancel() {
        stop();
    }

    @Override
    protected String getMessage() { 
        final String displayMana = manaCost.toString(false, player.getManaPool());
        final StringBuilder msg = new StringBuilder();
        final Localizer localizer = Localizer.getInstance();

        applyMatrix();

        if (messagePrefix != null) {
            msg.append(messagePrefix).append("\n");
        }
        if (FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_DETAILED_SPELLDESC_IN_PROMPT)) {
            if (saPaidFor.isSpell()) {
                msg.append(saPaidFor.getStackDescription().replace("(Targeting ERROR)", "")).append("\n\n");
            } else {
                msg.append(saPaidFor.getHostCard()).append(" - ").append(saPaidFor.toString()).append("\n\n");
            }
        }
        msg.append(localizer.getMessage("lblPayManaCost")).append(" ").append(displayMana);
        if (this.phyLifeToLose > 0) {
            msg.append(" ").append(String.format(localizer.getMessage("lblLifePaidForPhyrexianMana"), this.phyLifeToLose));
        }

        boolean isLifeInsteadBlack = player.hasKeyword("PayLifeInsteadOf:B") && manaCost.hasAnyKind(ManaAtom.BLACK);

        if (manaCost.containsPhyrexianMana() || isLifeInsteadBlack) {
            StringBuilder sb = new StringBuilder();
            if (manaCost.containsPhyrexianMana() && !isLifeInsteadBlack) {
                sb.append(localizer.getMessage("lblClickOnYourLifeTotalToPayLifeForPhyrexianMana"));
            } else if (!manaCost.containsPhyrexianMana() && isLifeInsteadBlack) {
                sb.append(localizer.getMessage("lblClickOnYourLifeTotalToPayLifeForBlackMana"));
            } else if (manaCost.containsPhyrexianMana() && isLifeInsteadBlack) {
                sb.append(localizer.getMessage("lblClickOnYourLifeTotalToPayLifeForPhyrexianOrBlackMana"));
            }
            msg.append("\n(").append(sb).append(")");
        } else {
            final SpellAbility lifeMana = lifeManaAbility();
            if (lifeMana != null) {
                msg.append("\n(").append(localizer.getMessage("lblClickOnYourLifeTotalToPayLifeForMana", lifeOnlyCost(lifeMana.getPayCosts(), lifeMana))).append(")");
            }
        }

        return msg.toString();
    }

    private void applyMatrix() {
        if (extraMatrix == null) {
            return;
        }

        player.getManaPool().applyCardMatrix(extraMatrix);
    }
}
