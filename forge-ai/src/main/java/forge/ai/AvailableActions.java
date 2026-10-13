package forge.ai;

import forge.card.mana.ManaCost;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CardLists;
import forge.game.card.CardView;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
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

    /*
     * Every entry point is a prediction the game can do without, and each runs on the game thread
     * (PlayerControllerHuman, every priority window and every click). So none may throw: a card
     * script this sweep reads wrongly once killed a live match (Nykthos, Shrine to Nyx, 2026-10-11 —
     * its "{2},{T}: Choose a color" mana ability has no mana part of its own). A failure is logged
     * with its stack and answered the FP-safe way this class already answers a timeout: assume
     * playable. "Nothing is playable" would auto-pass a player's window; "everything is" only
     * highlights too much.
     */

    /** Boolean form: early-exits on the first actionable card. */
    public static boolean compute(Player player, long timeoutMs) {
        try {
            return withAiController(player, () -> !walk(player, timeoutMs, true).actionable().isEmpty());
        } catch (RuntimeException e) {
            Logger.error(e, "AvailableActions.compute failed; answering 'actions available'");
            return true;
        }
    }

    /**
     * One full walk: {@code actionable} is every card the highlight marks; {@code plays} is the
     * subset with something to take besides a mana ability — a spell, a land drop, a non-mana
     * ability that is affordable, targetable and allowed. A card in {@code actionable} but not in
     * {@code plays} is offered for its mana alone (or for the undo of a mana tap): a Forest, a
     * Signet, a Celestial Colonnade whose animation its controller cannot pay for. Both sets are
     * read off the same {@link #canTake} test, on the game thread, so a reader elsewhere never
     * has to run the AI controller swap itself.
     */
    public record Scan(Set<CardView> actionable, Set<CardView> plays) {}

    /** Set form: walks every card so highlight consumers can mark the actionable subset. */
    public static Set<CardView> collectActionable(Player player, long timeoutMs) {
        return scan(player, timeoutMs).actionable();
    }

    /** {@link #collectActionable}, with the cards that are a play besides their mana. */
    public static Scan scan(Player player, long timeoutMs) {
        try {
            return withAiController(player, () -> walk(player, timeoutMs, false));
        } catch (RuntimeException e) {
            Logger.error(e, "AvailableActions.scan failed; marking every card actionable and a play");
            Set<CardView> all = new HashSet<>();
            addUnvisited(all, new HashSet<>(), player);
            return new Scan(all, all);
        }
    }

    /** The abilities a click on a card offers out of {@code abilities}: the ones Forge would take
     *  now, by the same test the highlight uses. A pick from this list is never refused once made. */
    public static List<SpellAbility> offered(Player player, List<SpellAbility> abilities) {
        try {
            return withAiController(player, () -> abilities.stream()
                    .filter(sa -> canTake(sa, player))
                    .collect(Collectors.toList()));
        } catch (RuntimeException e) {
            Logger.error(e, "AvailableActions.offered failed; offering every ability");
            return abilities;
        }
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

    /**
     * Every card with something the player would take, and among them the plays (see {@link Scan}).
     * {@code earlyExit} stops at the first actionable card and records no plays.
     */
    private static Scan walk(Player player, long timeoutMs, boolean earlyExit) {
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        Set<CardView> actionable = new HashSet<>();
        Set<CardView> plays = new HashSet<>();
        Set<CardView> visited = new HashSet<>();

        for (ZoneScan scan : SCANS) {
            Iterable<Card> cards = scan.sortByCmc()
                    ? sortedCardsIn(player, scan.zone())
                    : player.getCardsIn(scan.zone());
            for (Card card : cards) {
                if (checkTimeout(deadlineNanos, timeoutMs)) {
                    // Unvisited: FP-safe, as the highlight is — a play until shown otherwise.
                    Set<CardView> unvisited = new HashSet<>();
                    addUnvisited(unvisited, visited, player);
                    actionable.addAll(unvisited);
                    plays.addAll(unvisited);
                    return new Scan(actionable, plays);
                }
                CardView cv = card.getView();
                visited.add(cv);
                if (cardHasActionable(card, player)) {
                    actionable.add(cv);
                    if (earlyExit) return new Scan(actionable, plays);
                    if (cardHasPlay(card, player)) {
                        plays.add(cv);
                    }
                }
            }
        }
        return new Scan(actionable, plays);
    }

    /** Something the player would take on this card that is not a mana ability. */
    private static boolean cardHasPlay(Card card, Player player) {
        for (SpellAbility sa : card.getAllPossibleAbilities(player, true)) {
            if (!sa.isManaAbility() && canTake(sa, player)) {
                return true;
            }
        }
        return false;
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
    //
    // A permanent tapped for mana whose mana still floats is offered too: a click on it untaps it
    // (InputPassPriority.onCardSelected → PlayerControllerHuman.tryUndo), in any order.
    private static boolean cardHasActionable(Card card, Player player) {
        if (card.isTapped() && player.getGame().getStack().canUndo(player)
                && player.getGame().getStack().undoableBy(card) != null) {
            return true;
        }
        for (SpellAbility sa : card.getAllPossibleAbilities(player, true)) {
            if (sa.isManaAbility() && !isTapAndManaOnlyManaAbility(sa)) {
                continue;
            }
            if (canTake(sa, player)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canTake(SpellAbility sa, Player player) {
        return canAfford(sa, player) && ComputerUtilAbility.isFullyTargetable(sa) && passesRestrictions(sa, player);
    }

    // canPlay() leaves out the CantBeCast and CantBeActivated statics ("You can't cast this spell
    // during your first, second, or third turns of the game"): Forge asks them only once the play
    // has begun (PlaySpellAbility.playAbility, checkRestrictions), and refuses it there. Ask them
    // here the way the AI does before it plays (AiController.canPlaySa): of a spell as it would
    // stand on the stack.
    private static boolean passesRestrictions(SpellAbility sa, Player player) {
        Card host = sa.getHostCard();
        if (sa.isSpell()) {
            // The copy is the cost, and most spells meet no CantBeCast static at all.
            if (!anyCantBeCast(host, player)) {
                return true;
            }
            host = CardCopyService.getLKICopy(host);
            host.setLKICMC(-1);
            host.setLastKnownZone(player.getGame().getStackZone());
            host.setCastFrom(sa.getHostCard().getZone());
        }
        return sa.checkRestrictions(host, player);
    }

    /** Whether a CantBeCast static exists where StaticAbilityCantBeCast.cantBeCastAbility looks. */
    private static boolean anyCantBeCast(Card host, Player player) {
        for (StaticAbility stAb : host.getStaticAbilities()) {
            if (stAb.checkMode(StaticAbilityMode.CantBeCast)) {
                return true;
            }
        }
        for (Card c : player.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            for (StaticAbility stAb : c.getStaticAbilities()) {
                if (stAb.checkMode(StaticAbilityMode.CantBeCast)) {
                    return true;
                }
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
        return canAffordWithManaRocks(sa, player) || canAffordWithLife(sa, player);
    }

    /**
     * The AI payer leaves out every mana ability that itself costs mana
     * ({@code ComputerUtilMana.getAIPlayableMana}: "the AI will miscalculate"), so a Signet
     * ({1},{T}: add {U}{R}) never counts toward what a player can pay. Count each one the player
     * can activate here, one at a time: its own mana cost joins the total, and the mana it makes
     * pays toward it; then ask the payer about the rest.
     */
    private static boolean canAffordWithManaRocks(SpellAbility sa, Player player) {
        final ManaCostBeingPaid cost = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, player, true, 0, false);
        boolean added = false;
        for (Card rock : player.getCardsIn(ZoneType.Battlefield)) {
            if (rock == sa.getHostCard()) {
                continue;
            }
            for (SpellAbility ma : rock.getManaAbilities()) {
                ma.setActivatingPlayer(player);
                // A mana ability whose mana comes from a sub-ability has no mana part of its own
                // (Nykthos, Shrine to Nyx: AB$ ChooseColor | Cost$ 2 T | SubAbility$ DBMana). It is
                // not a plain rock, so it is left out like any other this cannot read.
                final AbilityManaPart part = ma.getManaPart();
                if (part == null || !isTapAndManaOnlyManaAbility(ma) || !ma.getPayCosts().hasManaCost() || !ma.canPlay()
                        || part.isComboMana() || part.isAnyMana() || part.isSpecialMana()) {
                    continue;
                }
                cost.addManaCost(ma.getPayCosts().getTotalMana());
                final int amount = ma.amountOfManaGenerated(false);
                for (String colour : part.mana(ma).split(" ")) {
                    for (int i = 0; i < amount; i++) {
                        cost.ai_payMana(colour, player.getManaPool());
                    }
                }
                added = true;
                break;
            }
            if (added && (cost.isPaid() || ComputerUtilMana.canPayManaCost(cost, sa, player, false))) {
                return true;
            }
        }
        return false;
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
