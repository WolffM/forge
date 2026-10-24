package forge.ai;

import forge.card.mana.ManaCost;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CardLists;
import forge.game.card.CardView;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.spellability.TargetRestrictions;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.Arrays;
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
     *
     * {@code animates} is the subset of {@code plays} that are a play because they can make
     * themselves a creature that could still fight this turn ({@link #animatesSelf},
     * {@link #couldFight}): a Mutavault before its controller's attack, an Inkmoth Nexus before
     * the other player's blocks.
     */
    public record Scan(Set<CardView> actionable, Set<CardView> plays, Set<CardView> animates) {}

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
            return new Scan(all, all, new HashSet<>());
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
        Set<CardView> animates = new HashSet<>();
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
                    return new Scan(actionable, plays, animates);
                }
                CardView cv = card.getView();
                visited.add(cv);
                if (cardHasActionable(card, player)) {
                    actionable.add(cv);
                    if (earlyExit) return new Scan(actionable, plays, animates);
                    addPlays(card, player, plays, animates);
                }
            }
        }
        return new Scan(actionable, plays, animates);
    }

    /**
     * Whether the card is a play: something the player would take on it that is not a mana ability.
     * An ability that only makes its own permanent a creature is a play only while that creature
     * could still fight ({@link #couldFight}); one that does more besides (Creeping Tar Pit's
     * unblockability, a Vehicle's counters) is a play for the rest wherever it can be taken.
     */
    private static void addPlays(Card card, Player player, Set<CardView> plays, Set<CardView> animates) {
        for (SpellAbility sa : card.getAllPossibleAbilities(player, true)) {
            if (sa.isManaAbility() || !canTake(sa, player)) {
                continue;
            }
            if (animatesSelf(sa)) {
                if (couldFight(sa, player)) {
                    animates.add(card.getView());
                } else if (sa.getSubAbility() == null) {
                    continue;
                }
            }
            plays.add(card.getView());
        }
    }

    /**
     * An ability that makes its own permanent a creature ({@code ApiType.Animate} with no target,
     * {@code Defined$ Self} or none, {@code Types$} naming Creature): a man-land, a Vehicle's Crew,
     * a Gideon's 0.
     */
    private static boolean animatesSelf(SpellAbility sa) {
        return sa.getApi() == ApiType.Animate && !sa.usesTargeting()
                && (!sa.hasParam("Defined") || "Self".equals(sa.getParam("Defined")))
                && sa.hasParam("Types") && Arrays.asList(sa.getParam("Types").split(",")).contains("Creature");
    }

    /**
     * Whether the permanent {@code sa} animates could still attack (its controller's turn, before
     * attackers are declared, not summoning sick) or block (the other turn, before blockers are
     * declared) once the ability is paid for. Not when it is tapped, already a creature, or tapped
     * by the cost itself. Its own mana never pays for it: the payer {@link #canAfford} asks leaves
     * out every mana ability of the card being paid for ({@code ComputerUtilMana.chooseManaAbility},
     * as Forge's Auto pays), and so does {@link #canAffordWithManaRocks}.
     *
     * A tapped permanent animated for something besides combat (an Ashnod's Altar) is a use this
     * gives up.
     */
    private static boolean couldFight(SpellAbility sa, Player player) {
        final Card host = sa.getHostCard();
        if (host.isTapped() || host.isCreature() || (sa.getPayCosts() != null && sa.getPayCosts().hasTapCost())) {
            return false;
        }
        final PhaseHandler ph = player.getGame().getPhaseHandler();
        final PhaseType phase = ph.getPhase();
        if (phase == null) {
            return true;
        }
        if (ph.isPlayerTurn(player)) {
            return !host.hasSickness() && phase.isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS);
        }
        return phase.isBefore(PhaseType.COMBAT_DECLARE_BLOCKERS);
    }

    // Land plays come through with no cost, no targets; spells and activated abilities (incl.
    // hand-activations like Channel) all hit the same shape: affordable, targetable, allowed.
    //
    // Desktop Forge lets a player activate a mana ability at any priority, so every one Forge would
    // take now is offered, whatever its cost: a land's {T}, a Signet's {1},{T} when that {1} can be
    // paid, Mana Confluence's life, Lion's Eye Diamond's sacrifice and discard.
    //
    // A permanent tapped for mana whose mana still floats is offered too: a click on it untaps it
    // (InputPassPriority.onCardSelected -> PlayerControllerHuman.tryUndo), in any order.
    private static boolean cardHasActionable(Card card, Player player) {
        if (card.isTapped() && player.getGame().getStack().canUndo(player)
                && player.getGame().getStack().undoableBy(card) != null) {
            return true;
        }
        for (SpellAbility sa : card.getAllPossibleAbilities(player, true)) {
            if (canTake(sa, player)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canTake(SpellAbility sa, Player player) {
        return canAfford(sa, player) && isTargetable(sa) && passesRestrictions(sa, player);
    }

    /** Candidates tried per ability before the answer is assumed (FP-safe, as a timeout is). */
    private static final int TARGET_TRIES = 64;

    /**
     * Whether every part of the chain that must target has enough candidates. A part's candidates
     * can depend on what an earlier part targets (Searing Blaze: "target creature that player
     * controls", Creature.ControlledBy ParentTargetedController), which no candidate count can see
     * with nothing targeted yet. So each earlier part is given each of its candidates in turn, the
     * way the player will pick it; resetTargets gives each try a fresh choice, so the originals are
     * put back untouched.
     */
    private static boolean isTargetable(SpellAbility sa) {
        final List<SpellAbility> chain = new ArrayList<>();
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            if (part.usesTargeting() && part.getMinTargets() > 0) {
                chain.add(part);
            }
        }
        if (chain.isEmpty()) {
            return true;
        }
        final List<TargetChoices> before = new ArrayList<>();
        for (SpellAbility part : chain) {
            before.add(part.getTargets());
        }
        try {
            return isTargetable(chain, 0, new int[] {TARGET_TRIES});
        } finally {
            for (int i = 0; i < chain.size(); i++) {
                chain.get(i).setTargets(before.get(i));
            }
        }
    }

    private static boolean isTargetable(List<SpellAbility> chain, int at, int[] tries) {
        final SpellAbility part = chain.get(at);
        final TargetRestrictions tgt = part.getTargetRestrictions();
        if (tgt.getNumCandidates(part) < part.getMinTargets()) {
            return false;
        }
        if (at == chain.size() - 1) {
            return true;
        }
        final List<GameEntity> candidates = tgt.getAllCandidates(part);
        if (candidates.isEmpty()) {
            // Its candidates are spells on the stack, which no later part reads.
            return isTargetable(chain, at + 1, tries);
        }
        for (GameEntity candidate : candidates) {
            if (--tries[0] < 0) {
                return true;
            }
            part.resetTargets();
            part.getTargets().add(candidate);
            if (isTargetable(chain, at + 1, tries)) {
                return true;
            }
        }
        return false;
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
        return canAffordWithManaRocks(sa, player) || canAffordWithLife(sa, player) || canAffordWithLifeMana(sa, player);
    }

    /**
     * The AI payer reads mana sources on the battlefield and in hand only
     * ({@code ComputerUtilMana.getAvailableManaSources}), and each at most once. A mana ability in
     * the player's Command zone paid only with life (Channel's effect: "Pay 1 life: Add {C}") is
     * neither, and the payment input uses it from the life total
     * ({@code InputPayManaOfCostPayment.onPlayerSelected}) as many times as there is life to pay.
     * Activate each in thought while the life lasts and the cost still takes its mana; then ask the
     * payer about the rest.
     */
    private static boolean canAffordWithLifeMana(SpellAbility sa, Player player) {
        final ManaCostBeingPaid cost = ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, player, true, 0, false);
        int life = 0;
        boolean added = false;
        for (Card card : player.getCardsIn(ZoneType.Command)) {
            for (SpellAbility ma : card.getManaAbilities()) {
                ma.setActivatingPlayer(player);
                final AbilityManaPart part = ma.getManaPart();
                final int each = lifeOnlyCost(ma);
                if (part == null || each <= 0 || !ma.canPlay() || part.isComboMana() || part.isAnyMana() || part.isSpecialMana()) {
                    continue;
                }
                final int amount = ma.amountOfManaGenerated(false);
                while (!cost.isPaid() && player.canPayLife(life + each, false, ma)) {
                    boolean paid = false;
                    for (String colour : part.mana(ma).split(" ")) {
                        for (int i = 0; i < amount; i++) {
                            paid |= cost.ai_payMana(colour, player.getManaPool());
                        }
                    }
                    if (!paid) {
                        break;
                    }
                    life += each;
                    added = true;
                }
            }
        }
        return added && (cost.isPaid() || ComputerUtilMana.canPayManaCost(cost, sa, player, false));
    }

    /** The life a mana ability's cost takes when life is all it takes, else 0. */
    private static int lifeOnlyCost(SpellAbility ma) {
        final Cost cost = ma.getPayCosts();
        if (cost == null) {
            return 0;
        }
        int life = 0;
        for (CostPart part : cost.getCostParts()) {
            if (!(part instanceof CostPayLife)) {
                return 0;
            }
            life += part.getAbilityAmount(ma);
        }
        return life;
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
