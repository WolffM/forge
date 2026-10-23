package forge.game.mulligan;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class LondonMulligan extends AbstractMulligan {
    public LondonMulligan(Player p, boolean firstMullFree) {
        super(p, firstMullFree);
    }

    @Override
    public boolean canMulligan() {
        // Another mulligan must leave a bottom the redrawn hand can pay: seven cards cover seven at most,
        // so the eighth mulligan is never offered (it asked for eight of seven and could not be answered).
        return !kept && tuckCardsAfter(timesMulliganed + 1) <= handSizeAfterNextMulligan();
    }

    @Override
    public int handSizeAfterNextMulligan() {
        return player.getMaxHandSize();
    }

    @Override
    public void mulliganDraw() {
        // London order: draw the new hand and let the player decide on it. The cards
        // go to the bottom only once the player keeps (see keep()).
        player.drawCards(handSizeAfterNextMulligan());
    }

    @Override
    public void keep() {
        super.keep();
        int tuckingCards = tuckCardsDuringMulligan();
        if (tuckingCards <= 0) {
            return;
        }
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        for (final Card c : player.getController().tuckCardsViaMulligan(hand, tuckingCards)) {
            player.getGame().getAction().moveToLibrary(c, -1, null);
        }
    }

    @Override
    public int tuckCardsDuringMulligan() {
        return tuckCardsAfter(timesMulliganed);
    }

    /** The cards a kept hand puts on the bottom after {@code mulligans} mulligans: one each, the first free if it is. */
    private int tuckCardsAfter(int mulligans) {
        if (mulligans == 0) {
            return 0;
        }

        int extraCard = firstMulliganFree ? 1 : 0;
        return mulligans - extraCard;
    }
}
