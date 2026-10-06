package forge.ai;

import forge.card.mana.ManaCost;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.card.CardView;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.tinylog.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// Heuristic: does the player have any playable action this priority window?
// Bounded by timeoutMs; FP-safe on expiry — unvisited cards are marked actionable.
public final class AvailableActions {

    private AvailableActions() {}

    /** sortByCmc: cheap-cost-first so early-exit hits faster.
     *  Battlefield isn't sorted: activation costs are per-ability, not the permanent's CMC. */
    private record ZoneScan(ZoneType zone, boolean sortByCmc) {}

    private static final List<ZoneScan> SCANS = List.of(
            new ZoneScan(ZoneType.Hand,        true),
            new ZoneScan(ZoneType.Battlefield, false),
            new ZoneScan(ZoneType.Flashback,   true));

    /** Boolean form: early-exits on the first actionable card. */
    public static boolean compute(Player player, long timeoutMs) {
        return withAiController(player, () -> !walk(player, timeoutMs, true).isEmpty());
    }

    /** Set form: walks every card so highlight consumers can mark the actionable subset. */
    public static Set<CardView> collectActionable(Player player, long timeoutMs) {
        return withAiController(player, () -> walk(player, timeoutMs, false));
    }

    /** Run the predictive sweep under an AI controller so cost-adjustment chooseX
     *  dispatches don't prompt (mirrors InputPayMana auto-pay). */
    private static <T> T withAiController(Player player, Supplier<T> body) {
        AtomicReference<T> result = new AtomicReference<>();
        player.runWithController(
                () -> result.set(body.get()),
                new PlayerControllerAi(player.getGame(), player, player.getOriginalLobbyPlayer()));
        return result.get();
    }

    private static Set<CardView> walk(Player player, long timeoutMs, boolean earlyExit) {
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        Set<CardView> actionable = new HashSet<>();
        Set<CardView> visited = new HashSet<>();

        for (ZoneScan scan : SCANS) {
            Iterable<Card> cards = scan.sortByCmc()
                    ? sortedCardsIn(player, scan.zone())
                    : player.getCardsIn(scan.zone());
            for (Card card : cards) {
                if (checkTimeout(deadlineNanos, timeoutMs)) {
                    addUnvisited(actionable, visited, player);
                    return actionable;
                }
                CardView cv = card.getView();
                visited.add(cv);
                if (cardHasActionable(card, player)) {
                    actionable.add(cv);
                    if (earlyExit) return actionable;
                }
            }
        }
        return actionable;
    }

    // Land plays come through with no cost, no targets; spells and activated abilities (incl.
    // hand-activations like Channel) all hit the same shape: not a mana ability, affordable, targetable.
    //
    // Desktop Forge lets a player tap lands freely before casting anything; our click filter
    // used to hide every mana ability (R15), so a land was never offered at priority. A mana
    // ability whose only cost is tapping (a basic land's, a dual's) is offered, and so is one
    // that adds a mana payment to the tap (a Signet's {1},{T}) when that mana can be paid now,
    // from the pool or another source — the same mana ability the payment prompt offers. A mana
    // ability with any other cost (pay life, sacrifice — painlands, Lotus Petal) stays excluded
    // until there's a real case to shape around; the player reaches those through the payment
    // prompt.
    private static boolean cardHasActionable(Card card, Player player) {
        for (SpellAbility sa : card.getAllPossibleAbilities(player, true)) {
            if (sa.isManaAbility()) {
                if (isTapAndManaOnlyManaAbility(sa) && canAfford(sa, player)
                        && ComputerUtilAbility.isFullyTargetable(sa)) {
                    return true;
                }
                continue;
            }
            if (canAfford(sa, player) && ComputerUtilAbility.isFullyTargetable(sa)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTapAndManaOnlyManaAbility(SpellAbility sa) {
        final Cost cost = sa.getPayCosts();
        if (cost == null || !cost.hasSpecificCostType(CostTap.class)) {
            return false;
        }
        for (CostPart part : cost.getCostParts()) {
            if (!(part instanceof CostTap) && !(part instanceof CostPartMana)) {
                return false;
            }
        }
        return true;
    }

    /** Timeout fallback: mark only the cards we never got to evaluate (FP-safe).
     *  Cards we visited and ruled out keep their determination. */
    private static void addUnvisited(Set<CardView> actionable, Set<CardView> visited, Player player) {
        for (ZoneScan scan : SCANS) {
            for (Card c : player.getCardsIn(scan.zone())) {
                CardView cv = c.getView();
                if (!visited.contains(cv)) actionable.add(cv);
            }
        }
    }

    // Sort cheap cards first so cheap-to-validate matches early-exit
    private static Iterable<Card> sortedCardsIn(Player player, ZoneType zone) {
        return player.getCardsIn(zone).stream().sorted(CardLists.CmcComparator).collect(Collectors.toList());
    }

    private static boolean canAfford(SpellAbility sa, Player player) {
        if (sa.getPayCosts() == null || !sa.getPayCosts().hasManaCost()) {
            return true;
        }
        if (ComputerUtilMana.canPayManaCost(sa, player, 0, false)) {
            return true;
        }
        return canAffordWithLife(sa, player);
    }

    /**
     * The AI payer above declines to pay Phyrexian mana with life whenever a script carries an
     * AI hint such as {@code AIPhyrexianPayment$ Never} (Gitaxian Probe does), because that is a
     * strategy choice for the computer. A human may always pay 2 life for a Phyrexian shard, so
     * pay k shards with life (if the player can afford the life) and ask whether mana covers the
     * rest, most life first.
     */
    private static boolean canAffordWithLife(SpellAbility sa, Player player) {
        final ManaCost total = sa.getPayCosts().getTotalMana();
        final int phyrexian = total.getPhyrexianCount();
        for (int k = phyrexian; k >= 1; k--) {
            if (!player.canPayLife(2 * k, false, sa)) {
                continue;
            }
            final ManaCostBeingPaid remaining = new ManaCostBeingPaid(total);
            for (int i = 0; i < k; i++) {
                remaining.payPhyrexian();
            }
            if (remaining.isPaid() || ComputerUtilMana.canPayManaCost(remaining, sa, player, false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkTimeout(long deadlineNanos, long timeoutMs) {
        if (System.nanoTime() < deadlineNanos) {
            return false;
        }
        Logger.warn("AvailableActions: heuristic timed out after {}ms; returning true.", timeoutMs);
        return true;
    }
}
