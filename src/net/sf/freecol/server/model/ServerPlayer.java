/**
 *  Copyright (C) 2002-2016   The FreeCol Team
 *
 *  This file is part of FreeCol.
 *
 *  FreeCol is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  FreeCol is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with FreeCol.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.sf.freecol.server.model;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.sf.freecol.common.FreeColException;
import net.sf.freecol.common.debug.FreeColDebugger;
import net.sf.freecol.common.i18n.Messages;
import net.sf.freecol.common.i18n.NameCache;
import net.sf.freecol.common.model.Ability;
import net.sf.freecol.common.model.AbstractGoods;
import net.sf.freecol.common.model.AbstractUnit;
import net.sf.freecol.common.model.Building;
import net.sf.freecol.common.model.BuildingType;
import net.sf.freecol.common.model.Colony;
import net.sf.freecol.common.model.CombatModel;
import net.sf.freecol.common.model.CombatModel.CombatResult;
import net.sf.freecol.common.model.DiplomaticTrade;
import net.sf.freecol.common.model.Disaster;
import net.sf.freecol.common.model.Effect;
import net.sf.freecol.common.model.Europe;
import net.sf.freecol.common.model.Europe.MigrationType;
import net.sf.freecol.common.model.Event;
import net.sf.freecol.common.model.Force;
import net.sf.freecol.common.model.FoundingFather;
import net.sf.freecol.common.model.FoundingFather.FoundingFatherType;
import net.sf.freecol.common.model.FreeColGameObject;
import net.sf.freecol.common.model.Game;
import net.sf.freecol.common.model.GameOptions;
import net.sf.freecol.common.model.Goods;
import net.sf.freecol.common.model.GoodsContainer;
import net.sf.freecol.common.model.GoodsType;
import net.sf.freecol.common.model.HistoryEvent;
import net.sf.freecol.common.model.IndianSettlement;
import net.sf.freecol.common.model.Location;
import net.sf.freecol.common.model.Market;
import net.sf.freecol.common.model.ModelMessage;
import net.sf.freecol.common.model.ModelMessage.MessageType;
import net.sf.freecol.common.model.Modifier;
import net.sf.freecol.common.model.Monarch;
import net.sf.freecol.common.model.Nation;
import net.sf.freecol.common.model.Player;
import net.sf.freecol.common.model.Role;
import net.sf.freecol.common.model.Settlement;
import net.sf.freecol.common.model.Specification;
import net.sf.freecol.common.model.Stance;
import net.sf.freecol.common.model.StanceTradeItem;
import net.sf.freecol.common.model.StringTemplate;
import net.sf.freecol.common.model.Tension;
import net.sf.freecol.common.model.Tile;
import net.sf.freecol.common.model.TradeRoute;
import net.sf.freecol.common.model.Turn;
import net.sf.freecol.common.model.Unit;
import net.sf.freecol.common.model.UnitChangeType;
import net.sf.freecol.common.model.UnitChangeType.UnitChange;
import net.sf.freecol.common.model.UnitType;
import net.sf.freecol.common.model.WorkLocation;
import net.sf.freecol.common.model.pathfinding.GoalDeciders;
import net.sf.freecol.common.networking.ChooseFoundingFatherMessage;
import net.sf.freecol.common.networking.Connection;
import net.sf.freecol.common.networking.DiplomacyMessage;
import net.sf.freecol.common.networking.DOMMessage;
import net.sf.freecol.common.networking.ErrorMessage;
import net.sf.freecol.common.networking.FirstContactMessage;
import net.sf.freecol.common.networking.IndianDemandMessage;
import net.sf.freecol.common.networking.LootCargoMessage;
import net.sf.freecol.common.networking.MonarchActionMessage;
import net.sf.freecol.common.util.LogBuilder;
import net.sf.freecol.common.util.RandomChoice;
import static net.sf.freecol.common.util.CollectionUtils.*;
import static net.sf.freecol.common.util.RandomUtils.*;
import net.sf.freecol.server.control.ChangeSet;
import net.sf.freecol.server.control.ChangeSet.ChangePriority;
import net.sf.freecol.server.control.ChangeSet.See;

import org.w3c.dom.Element;


/**
 * A {@code Player} with additional (server specific) information.
 *
 * That is: pointers to this player's
 * {@link Connection} and {@link Socket}
 */
public class ServerPlayer extends Player implements ServerModelObject {

    private static final Logger logger = Logger.getLogger(ServerPlayer.class.getName());

    // FIXME: move to options or spec?
    public static final int ALARM_RADIUS = 2;
    public static final int ALARM_TILE_IN_USE = 2;

    // checkForDeath results
    public static final int IS_DEAD = -1;
    public static final int IS_ALIVE = 0;
    public static final int AUTORECRUIT = 1;

    // Penalty for destroying a settlement (Col1)
    public static final int SCORE_SETTLEMENT_DESTROYED = -5;

    // Penalty for destroying a nation (FreeCol extension)
    public static final int SCORE_NATION_DESTROYED = -50;

    // Gold converts to score at 1 pt per 1000 gp (Col1)
    public static final double SCORE_GOLD = 0.001;

    // Score bonus for each founding father (Col1)
    public static final int SCORE_FOUNDING_FATHER = 5;

    // Percentage bonuses for being the 1st,2nd and 3rd player to
    // achieve independence. (Col1)
    public static final int SCORE_INDEPENDENCE_BONUS_FIRST = 100;
    public static final int SCORE_INDEPENDENCE_BONUS_SECOND = 50;
    public static final int SCORE_INDEPENDENCE_BONUS_THIRD = 25;

    // Do not serialize anything below.

    /** The network socket to the player's client. */
    private Socket socket;

    /** The connection for this player. */
    private Connection connection;

    /** An overriding switch indicating connection. */
    private boolean connected = false;

    /** Remaining emigrants to select due to a fountain of youth */
    private int remainingEmigrants = 0;

    /** Players with respect to which stance has changed. */
    private final List<ServerPlayer> stanceDirty = new ArrayList<>();

    /** Accumulate extra trades here. */
    private final List<AbstractGoods> extraTrades = new ArrayList<>();


    /**
     * Trivial constructor required for all ServerModelObjects.
     *
     * @param game The {@code Game} this object belongs to.
     * @param id The object identifier.
     */
    public ServerPlayer(Game game, String id) {
        super(game, id);
    }

    /**
     * Creates a new ServerPlayer.
     *
     * @param game The {@code Game} this object belongs to.
     * @param admin Whether the player is the game administrator or not.
     * @param nation The nation of the {@code Player}.
     * @param socket The socket to the player's client.
     * @param connection The {@code Connection} for the socket.
     */
    public ServerPlayer(Game game, boolean admin, Nation nation,
                        Socket socket, Connection connection) {
        super(game);

        if (nation == null) throw new IllegalArgumentException("Null nation");
        final Specification spec = getSpecification();

        this.name = nation.getRulerName();
        this.admin = admin;
        this.nationId = nation.getId();
        this.immigration = 0;
        if (nation.isUnknownEnemy()) { // virtual "enemy privateer" player
            this.nationType = null;
            this.playerType = PlayerType.COLONIAL;
            this.europe = null;
            this.monarch = null;
            this.gold = 0;
            this.setAI(true);
        } else if (nation.getType() != null) {
            this.nationType = nation.getType();
            try {
                addFeatures(nationType);
            } catch (Throwable error) {
                error.printStackTrace();
            }
            if (nationType.isEuropean()) {
                /*
                 * Setting the amount of gold to
                 * "getGameOptions().getInteger(GameOptions.STARTING_MONEY)"
                 *
                 * just before starting the game. See
                 * "net.sf.freecol.server.control.PreGameController".
                 */
                this.playerType = (nationType.isREF()) ? PlayerType.ROYAL
                    : PlayerType.COLONIAL;
                this.europe = new ServerEurope(game, this);
                initializeHighSeas();
                if (this.playerType == PlayerType.COLONIAL) {
                    this.monarch = new Monarch(game, this);
                    // In BR#2615 misiulo reports that Col1 players start
                    // with 2 crosses.  This is surprising, but you could
                    // argue that some level of religious unrest might
                    // contribute to the fact there is an expedition to
                    // the new world underway.
                    this.immigration = spec.getInteger(GameOptions.PLAYER_IMMIGRATION_BONUS);
                }
                this.gold = 0;
            } else { // indians
                this.playerType = PlayerType.NATIVE;
                this.gold = Player.GOLD_NOT_ACCOUNTED;
            }
        } else {
            throw new IllegalArgumentException("Bogus nation: " + nation);
        }
        this.market = new Market(getGame(), this);
        this.liberty = 0;
        this.currentFather = null;

        this.socket = socket;
        this.setConnection(connection);
    }


    /**
     * Is this player is currently connected to the server?
     *
     * @return True if this player is currently connected to the server.
     */
    public boolean isConnected() {
        return this.connected;
    }

    /**
     * Sets the "connected"-status of this player.
     *
     * This allows an override of the default check of whether
     * this.connection is null, and is used to allow a reconnection
     * after a player logs out.
     *
     * @param connected True if this player should be considered as
     *     connected to the server.
     */
    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    /**
     * Gets the socket of this player.
     * @return The {@code Socket}.
     */
    public Socket getSocket() {
        return this.socket;
    }

    /**
     * Gets the connection of this player.
     *
     * @return The {@code Connection}.
     */
    public Connection getConnection() {
        return this.connection;
    }

    /**
     * Sets the connection of this player.
     *
     * @param connection The {@code Connection}.
     */
    public void setConnection(Connection connection) {
        this.connection = connection;
        this.connected = this.connection != null;
    }

    /**
     * Send a change set to this player.
     *
     * @param cs The {@code ChangeSet} to send.
     */
    public void send(ChangeSet cs) {
        askElement(cs.build(this));
    }

    /**
     * Send a message to the player, and return the resulting message.
     *
     * @param game The {@code Game} to build the return message in.
     * @param request The {@code DOMMessage} to send.
     * @return The resulting {@code DOMMessage}.
     */
    public DOMMessage ask(Game game, DOMMessage request) {
        return (isConnected()) ? this.connection.ask(game, request) : null;
    }
    
    /**
     * Send an element to this player.
     * Do not use (sole use in send() above). This will go away.
     *
     * @param request An {@code Element} containing the update.
     */
    private void askElement(Element request) {
        if (!isConnected()) return;

        while (request != null) {
            Element reply;
            try {
                reply = this.connection.ask(request);
                if (reply == null) break;
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not send \""
                    + request.getTagName() + "\"-message.", e);
                break;
            }

            try {
                request = this.connection.handle(reply);
            } catch (FreeColException fce) {
                logger.log(Level.WARNING, "Exception processing reply \""
                    + reply.getTagName() + "\"-message.", fce);
                break;
            }
        }
    }

    /**
     * Convenience function to create a client error message, log it,
     * and wrap it into a change set.
     *
     * @param message The non-i18n message.
     * @return A new {@code ChangeSet}.
     */
    public ChangeSet clientError(String message) {
        logger.warning(message);
        if (FreeColDebugger.isInDebugMode(FreeColDebugger.DebugMode.COMMS)) {
            Thread.dumpStack();
        }
        ChangeSet cs = new ChangeSet();
        cs.add(See.only(this), ChangeSet.ChangePriority.CHANGE_NORMAL,
               new ErrorMessage(message));
        return cs;
    }

    /**
     * Update the current father.
     *
     * @param ff The {@code FoundingFather} to recruit.
     */
    public void updateCurrentFather(FoundingFather ff) {
        setCurrentFather(ff);
        clearOfferedFathers();
        if (ff != null) {
            logger.finest(getId() + " is recruiting " + ff.getId()
                + " in " + getGame().getTurn());
        }
    }

    /**
     * Get a new trade route.
     *
     * @return The new {@code TradeRoute}.
     */
    public TradeRoute newTradeRoute() {
        TradeRoute route = new TradeRoute(getGame(), getNameForTradeRoute(),
                                          this);
        addTradeRoute(route);
        return route;
    }

    /**
     * Accumulate extra trades.
     *
     * @param ag The {@code AbstractGoods} describing the sale.
     */
    public void addExtraTrade(AbstractGoods ag) {
        extraTrades.add(ag);
    }

    /**
     * Flush the extra trades.
     *
     * @param random A pseudo-random number source.
     */
    public void flushExtraTrades(Random random) {
        while (!extraTrades.isEmpty()) {
            AbstractGoods ag = extraTrades.remove(0);
            propagateToEuropeanMarkets(ag.getType(), ag.getAmount(), random);
        }
    }

    /**
     * Performs initial randomizations for this player.
     *
     * @param random A pseudo-random number source.
     */
    public void randomizeGame(Random random) {
        if (!isEuropean() || isREF() || isUnknownEnemy()) return;
        final Specification spec = getGame().getSpecification();

        // Set initial immigration target
        int i0 = spec.getInteger(GameOptions.INITIAL_IMMIGRATION);
        immigrationRequired = (int)applyModifiers((float)i0, null,
            Modifier.RELIGIOUS_UNREST_BONUS);

        // Add initial gold
        modifyGold(spec.getInteger(GameOptions.STARTING_MONEY));

        // Choose starting immigrants
        ((ServerEurope)getEurope()).initializeMigration(random);

        // Randomize the initial market prices
        Market market = getMarket();
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (GoodsType type : spec.getGoodsTypeList()) {
            String prefix = "model.option."
                + type.getSuffix("model.goods.");
            // these options are not available for all goods types
            if (spec.hasOption(prefix + ".minimumPrice")
                && spec.hasOption(prefix + ".maximumPrice")) {
                int min = spec.getInteger(prefix + ".minimumPrice");
                int max = spec.getInteger(prefix + ".maximumPrice");
                if (max < min) { // User error
                    int bad = min;
                    min = max;
                    max = bad;
                } else if (max == min) continue;
                int add = randomInt(null, null, random, max - min);
                if (add > 0) {
                    market.setInitialPrice(type, min + add);
                    market.update(type);
                    market.flushPriceChange(type);
                    sb.append(", ").append(type.getId())
                        .append(" -> ").append(min + add);
                    changed = true;
                }
            }
        }
        if (changed) {
            logger.finest("randomizeGame(" + getId() + ") initial prices: "
                + sb.toString().substring(2));
        }
    }

    /**
     * Checks if this player has died.
     *
     * @return Negative if the player is dead, zero if they are ok,
     *      positive non-zero if the server should auto-recruit
     *      colonist units to keep this player alive.
     */
    public int checkForDeath() {
        if (isUnknownEnemy()) return IS_ALIVE;
        final Specification spec = getGame().getSpecification();
        /*
         * Die if: (isNative && (no colonies or units))
         *      || ((rebel or independent) && !(has coastal colony))
         *      || (isREF && !(rebel nation left) && (all units in Europe))
         *      || ((no units in New World)
         *         && ((year > 1600) || (cannot get a unit from Europe)))
         */
        switch (getPlayerType()) {
        case NATIVE: // All natives units are viable
            return (getUnitCount() == 0) ? IS_DEAD : IS_ALIVE;

        case COLONIAL: // Handle the hard case below
            break;

        case REBEL: case INDEPENDENT:
            // Post-declaration European player needs a coastal colony
            // and can not hope for resupply from Europe.
            return (getNumberOfPorts() > 0) ? IS_ALIVE : IS_DEAD;

        case ROYAL:
            return (getRebels().isEmpty()) ? IS_DEAD : IS_ALIVE;

        case UNDEAD:
            return (getUnitCount() == 0) ? IS_DEAD : IS_ALIVE;

        default:
            throw new IllegalStateException("Bogus player type");
        }

        // Quick check for a colony.  Do not log, this is the common case.
        if (any(getColonies())) return IS_ALIVE;

        // Do not kill the observing player during a debug run.
        if (!isAI() && FreeColDebugger.getDebugRunTurns() >= 0) return IS_ALIVE;

        // Do not kill the unknown enemy!
        if (isUnknownEnemy()) return IS_ALIVE;

        // Traverse player units, look for valid carriers, colonists,
        // carriers with units, carriers with goods.
        boolean hasCarrier = false, hasColonist = false, hasEmbarked = false,
            hasGoods = false;
        for (Unit unit : getUnitList()) {
            if (unit.isCarrier()) {
                if (unit.hasGoodsCargo()) hasGoods = true;
                hasCarrier = true;
                continue;
            }

            // Must be able to found new colony or capture units
            if (!unit.isColonist() && !unit.isOffensiveUnit()) continue;
            hasColonist = true;

            // Verify if unit is in new world, or on a carrier in new world
            Unit carrier;
            if ((carrier = unit.getCarrier()) != null) {
                if (carrier.hasTile()) {
                    logger.info(getName() + " alive, unit " + unit.getId()
                        + " (embarked) on map.");
                    return IS_ALIVE;
                }
                hasEmbarked = true;
            }
            if (unit.hasTile() && !unit.isInMission()) {
                logger.info(getName() + " alive, unit " + unit.getId()
                    + " on map.");
                return IS_ALIVE;
            }
        }
        // The player does not have any valid units or settlements on the map.

        int mandatory = spec.getInteger(GameOptions.MANDATORY_COLONY_YEAR);
        if (getGame().getTurn().getYear() >= mandatory) {
            // After the season cutover year there must be a presence
            // in the New World.
            logger.info(getName() + " dead, no presence >= " + mandatory);
            return IS_DEAD;
        }

        // No problems, unit available on carrier but off map, or goods
        // available to be sold.
        if (hasEmbarked) {
            logger.info(getName() + " alive, has embarked unit.");
            return IS_ALIVE;
        } else if (hasGoods) {
            logger.info(getName() + " alive, has cargo.");
            return IS_ALIVE;
        }

        // It is necessary to still have a carrier.
        final Europe europe = getEurope();
        final ToIntFunction<UnitType> unitPricer = ut -> {
            int p = europe.getUnitPrice(ut);
            return (p == UNDEFINED) ? Integer.MAX_VALUE : p;
        };
        int goldNeeded = 0;
        if (!hasCarrier) {
            int price = (europe == null) ? Integer.MAX_VALUE
                : min(spec.getUnitTypesWithAbility(Ability.NAVAL_UNIT),
                      unitPricer);
            if (price == Integer.MAX_VALUE || !checkGold(price)) {
                logger.info(getName() + " dead, can not buy carrier.");
                return IS_DEAD;
            }
            goldNeeded += price;
        }

        // A colonist is required.
        if (hasColonist) {
            logger.info(getName() + " alive, has waiting colonist.");
            return IS_ALIVE;
        } else if (europe == null) {
            logger.info(getName() + " dead, can not recruit.");
            return IS_DEAD;
        }
        UnitType unitType = null;
        int price = Math.min(europe.getRecruitPrice(),
                             min(spec.getUnitTypesWithAbility(Ability.FOUND_COLONY),
                                 unitPricer));
        goldNeeded += price;
        if (checkGold(goldNeeded)) {
            logger.info(getName() + " alive, can buy colonist.");
            return IS_ALIVE;
        }

        // Col1 auto-recruits a unit in Europe if you run out before
        // the cutover year.
        logger.info(getName() + " survives by autorecruit.");
        return AUTORECRUIT;
    }

    /**
     * Check if a REF player has been defeated and should surrender.
     *
     * @return True if this REF player has been defeated.
     */
    public boolean checkForREFDefeat() {
        if (!isREF()) {
            throw new RuntimeException("Not a REF player: " + this.getId());
        }

        // No one to fight?  Either the rebels are dead, or the REF
        // was already defeated and the rebels are independent.
        // Either way, it does not need to surrender.
        if (getRebels().isEmpty()) return false;

        // Not defeated if holding settlements.
        if (hasSettlements()) return false;

        // Not defeated if there is a non-zero navy and enough land units.
        final int landREFUnitsRequired = 7; // FIXME: magic number
        boolean naval = false;
        int land = 0;
        for (Unit u : getUnitList()) {
            if (u.isNaval()) naval = true; else {
                if (u.hasAbility(Ability.REF_UNIT)) land++;
            }
        }
        if (naval && land >= landREFUnitsRequired) return false;

        // Surrender if a rebel has a stronger land army.
        final double power = calculateStrength(false);
        return none(getRebels(), r -> power < r.calculateStrength(false));
    }

    /**
     * Kill off a player and clear out its remains.
     *
     * +vis: Albeit killing the player makes visibility changes moot.
     * +til: Fixes the appearance changes too.
     *
     * @param cs A {@code ChangeSet} to update.
     */
    public void csKill(ChangeSet cs) {
        setDead(true);
        cs.addPartial(See.all(), this, "dead");
        cs.addDead(this);

        // Clean up missions and remove tension/alarm/stance.
        for (Player other : getGame().getLivePlayerList(this)) {
            if (isEuropean() && other.isIndian()) {
                for (IndianSettlement is : other.getIndianSettlementList()) {
                    ServerIndianSettlement sis = (ServerIndianSettlement)is;
                    if (is.hasMissionary(this)) sis.csKillMissionary(null, cs);
                    is.getTile().cacheUnseen();//+til
                    sis.removeAlarm(this);//-til
                }
                other.removeTension(this);
            }
            other.setStance(this, null);
        }

        // Remove settlements.  Update formerly owned tiles.
        List<Settlement> settlements = getSettlementList();
        while (!settlements.isEmpty()) {
            csDisposeSettlement(settlements.remove(0), cs);
        }

        // Clean up remaining tile ownerships
        for (Tile t : transform(getGame().getMap().getAllTiles(),
                                matchKeyEquals(this, Tile::getOwner))) {
            t.cacheUnseen();//+til
            t.changeOwnership(null, null);//-til
            cs.add(See.perhaps().always(this), t);
        }

        // Remove units
        List<Unit> units = getUnitList();
        while (!units.isEmpty()) {
            Unit u = units.remove(0);
            if (u.hasTile()) cs.add(See.perhaps(), u.getTile());
            cs.addRemove(See.perhaps().always(this),
                         u.getLocation(), u);//-vis(this)
            u.dispose();
        }

        // Remove European stuff
        if (market != null) {
            market.dispose();
            market = null;
        }
        if (monarch != null) {
            monarch.dispose();
            monarch = null;
        }
        if (europe != null) {
            europe.dispose();
            europe = null;
        }
        currentFather = null;
        if (foundingFathers != null) foundingFathers.clear();
        if (offeredFathers != null) offeredFathers.clear();
        // FIXME: stance and tension?
        if (tradeRoutes != null) tradeRoutes.clear();
        // Retaining model messages for now
        // Retaining history for now
        if (lastSales != null) lastSales = null;
        featureContainer.clear();

        invalidateCanSeeTiles();//+vis(this)
    }

    /**
     * Withdraw a player from the new world.
     *
     * @param cs A {@code ChangeSet} to update.
     */
    public void csWithdraw(ChangeSet cs) {
        String key = (isEuropean() && getPlayerType() == PlayerType.COLONIAL)
            ? "model.player.dead.european"
            : "model.player.dead.native";
        cs.addGlobalMessage(getGame(), null,
            new ModelMessage(MessageType.FOREIGN_DIPLOMACY, key, this)
                .addStringTemplate("%nation%", getNationLabel()));
        Game game = getGame();
        cs.addGlobalHistory(game,
            new HistoryEvent(game.getTurn(),
                             HistoryEvent.HistoryEventType.NATION_DESTROYED, null)
                .addStringTemplate("%nation%", getNationLabel()));
        csKill(cs);
    }


    public int getRemainingEmigrants() {
        return remainingEmigrants;
    }

    public void setRemainingEmigrants(int emigrants) {
        remainingEmigrants = emigrants;
    }

    /**
     * Checks whether the current founding father has been recruited.
     *
     * @return The new founding father, or null if none available or ready.
     */
    public FoundingFather checkFoundingFather() {
        FoundingFather father = null;
        if (currentFather != null) {
            int extraLiberty = getRemainingFoundingFatherCost();
            if (extraLiberty <= 0) {
                boolean overflow = getSpecification()
                    .getBoolean(GameOptions.SAVE_PRODUCTION_OVERFLOW);
                setLiberty((overflow) ? -extraLiberty : 0);
                father = currentFather;
                currentFather = null;
            }
        }
        return father;
    }

    /**
     * Checks whether to start recruiting a founding father.
     *
     * @return True if a new father should be chosen.
     */
    public boolean canRecruitFoundingFather() {
        Specification spec = getGame().getSpecification();
        switch (getPlayerType()) {
        case COLONIAL:
            break;
        case REBEL: case INDEPENDENT:
            if (!spec.getBoolean(GameOptions.CONTINUE_FOUNDING_FATHER_RECRUITMENT)) return false;
            break;
        default:
            return false;
        }
        return canHaveFoundingFathers() && currentFather == null
            && hasSettlements()
            && getFatherCount() < spec.getFoundingFathers().size();
    }

    /**
     * Build a list of random FoundingFathers, one per type.
     * Do not include any the player has or are not available.
     *
     * @param random A pseudo-random number source.
     * @return A list of FoundingFathers.
     */
    public List<FoundingFather> getRandomFoundingFathers(Random random) {
        // Build weighted random choice for each father type
        final Game game = getGame();
        final Specification spec = game.getSpecification();
        final int age = game.getAge();
        EnumMap<FoundingFatherType, List<RandomChoice<FoundingFather>>> choices
            = new EnumMap<>(FoundingFatherType.class);
        for (FoundingFather father : transform(spec.getFoundingFathers(),
                ff -> !hasFather(ff) && ff.isAvailableTo(this))) {
            FoundingFatherType type = father.getType();
            List<RandomChoice<FoundingFather>> rc = choices.get(type);
            if (rc == null) rc = new ArrayList<>();
            int weight = father.getWeight(age);
            rc.add(new RandomChoice<>(father, weight));
            choices.put(father.getType(), rc);
        }

        // Select one from each father type
        final Function<FoundingFatherType, FoundingFather> mapper = ft -> {
            List<RandomChoice<FoundingFather>> rc = choices.get(ft);
            return (rc == null) ? null
                : RandomChoice.getWeightedRandom(logger,
                    "Choose founding father", rc, random);
        };
        List<FoundingFather> fathers = transform(FoundingFatherType.values(),
            alwaysTrue(), mapper, toListNoNulls());
        LogBuilder lb = new LogBuilder(64);
        lb.add("Random fathers for ", getDebugName(), ":");
        for (FoundingFather f : fathers) lb.add(" ", f.getSuffix());
        lb.log(logger, Level.INFO);
        return fathers;
    }

    /**
     * Add a HistoryEvent to this player.
     *
     * @param event The {@code HistoryEvent} to add.
     */
    @Override
    public void addHistory(HistoryEvent event) {
        history.add(event);
    }

    /**
     * Update the current score for this player.
     *
     * Known incompatibility with the Col1 manual:
     * ``In addition, you get one point per liberty bell produced
     *   after foreign intervention''
     * However you are already getting a point per liberty bell
     * produced, so this implies you get no further liberty after
     * declaring independence!?, but it can then start again if the
     * foreign intervention happens (penalizing players who quickly
     * thrash the REF:-S).  Whatever this really means, it is
     * incompatible with our extensions to allow playing on (and
     * defeating other Europeans), so for now at least just leave the
     * simple liberty==score rule in place.
     *
     * @return True if the player score changed.
     */
    public boolean updateScore() {
        int oldScore = this.score;
        this.score = sum(getUnits(), Unit::getScoreValue)
            + sum(getColonies(), Colony::getLiberty)
            + SCORE_FOUNDING_FATHER * count(getFathers());
        int gold = getGold();
        if (gold != GOLD_NOT_ACCOUNTED) {
            this.score += (int)Math.floor(SCORE_GOLD * gold);
        }
        
        int bonus = 0;
        for (HistoryEvent h : transform(getHistory(),
                matchKeyEquals(getId(), HistoryEvent::getPlayerId))) {
            switch (h.getEventType()) {
            case INDEPENDENCE:
                switch (h.getScore()) {
                case 0: bonus = SCORE_INDEPENDENCE_BONUS_FIRST; break;
                case 1: bonus = SCORE_INDEPENDENCE_BONUS_SECOND; break;
                case 2: bonus = SCORE_INDEPENDENCE_BONUS_THIRD; break;
                default: bonus = 0; break;
                }
                break;
            default:
                this.score += h.getScore();
                break;
            }
        }
        this.score += (this.score * bonus) / 100;

        return this.score != oldScore;
    }

    /**
     * Checks if this {@code Player} has explored the given
     * {@code Tile}.
     *
     * @param tile The {@code Tile}.
     * @return <i>true</i> if the {@code Tile} has been explored and
     *         <i>false</i> otherwise.
     */
    @Override
    public boolean hasExplored(Tile tile) {
        return tile.isExploredBy(this);
    }

    /**
     * Sets the given tile to be explored by this player and updates
     * the player's information about the tile.
     *
     * +til: Exploring the tile also updates the pet.
     *
     * @param tile The {@code Tile} to explore.
     * @return True if the tile is newly explored by this action.
     */
    public boolean exploreTile(Tile tile) {
        boolean ret = !hasExplored(tile);
        if (ret) tile.setExplored(this, true);
        return ret;
    }

    /**
     * Sets the tiles within the given {@code Unit}'s line of
     * sight to be explored by this player.
     *
     * @param tiles A list of {@code Tile}s.
     * @return A list of newly explored {@code Tile}s.
     * @see #hasExplored
     */
    public Set<Tile> exploreTiles(Collection<? extends Tile> tiles) {
        return transform(tiles, t -> exploreTile(t), (Tile t) -> t,
                         Collectors.toSet());
    }

    /**
     * Sets the tiles visible to a given settlement to be explored by
     * this player and updates the player's information about the
     * tiles.  Note that the player does not necessarily own the settlement
     * (e.g. missionary at native settlement).
     *
     * @param settlement The {@code Settlement} that is exploring.
     * @return A list of newly explored {@code Tile}s.
     */
    public Set<Tile> exploreForSettlement(Settlement settlement) {
        Set<Tile> tiles = new HashSet<>(settlement.getOwnedTiles());
        tiles.addAll(settlement.getVisibleTiles());
        tiles.remove(settlement.getTile());
        return exploreTiles(tiles);
    }

    /**
     * Sets the tiles within the given {@code Unit}'s line of
     * sight to be explored by this player.
     *
     * @param unit The {@code Unit}.
     * @return A list of newly explored {@code Tile}s.
     * @see #hasExplored
     */
    public Set<Tile> exploreForUnit(Unit unit) {
        return (getGame() == null || getGame().getMap() == null || unit == null
            || !(unit.getLocation() instanceof Tile)) 
            ? Collections.<Tile>emptySet()
            : exploreTiles(unit.getVisibleTiles());
    }

    /**
     * Makes the entire map visible or invisible.
     *
     * @param reveal If true, reveal the map, if false, hide it.
     * @return A list of tiles whose visibility changed.
     */
    public Set<Tile> exploreMap(boolean reveal) {
        Set<Tile> result = new HashSet<>();
        for (Tile t : transform(getGame().getMap().getAllTiles(),
                                t -> hasExplored(t) != reveal)) {
            t.setExplored(this, reveal);//-vis(this)
            result.add(t);
        }
        invalidateCanSeeTiles();//+vis(this)
        if (!reveal) {
            for (Settlement s : getSettlementList()) exploreForSettlement(s);
            for (Unit u : getUnitList()) exploreForUnit(u);
        }
        return result;
    }

    /**
     * Checks if this player can see a unit.
     *
     * @param unit The {@code Unit} to check.
     * @return True if the {@code Unit} is visible to the player.
     */
    public boolean canSeeUnit(Unit unit) {
        Tile tile;
        return (this.owns(unit)) ? true
            : ((tile = unit.getTile()) == null) ? false
            : (!this.canSee(tile)) ? false
            : (tile.hasSettlement()) ? false
            : (unit.isOnCarrier()) ? false
            : true;
    }
        
    /**
     * Try to reassign the ownership of a collection of tiles,
     * preferring this player.
     *
     * Do it in two passes so the first successful claim does not give
     * a large advantage.
     *
     * @param tiles The collection of {@code Tile}s to reassign.
     * @param avoid An optional {@code Settlement} to consider last
     *     when making claims.
     */
    public void reassignTiles(Collection<Tile> tiles, Settlement avoid) {
        HashMap<Settlement, Integer> votes = new HashMap<>();
        HashMap<Tile, Settlement> claims = new HashMap<>();
        Settlement claimant;
        for (Tile t : tiles) t.changeOwnership(null, null);//-til
        for (Tile tile : transform(tiles, t -> !t.isOccupied())) {
            votes.clear();
            for (Tile t : tile.getSurroundingTiles(1)) {
                claimant = t.getOwningSettlement();
                if (claimant != null
                    // BR#3375773 found a case where tiles were
                    // still owned by a settlement that had been
                    // previously destroyed.  These should be gone, but...
                    && !claimant.isDisposed()
                    && claimant.getOwner() != null
                    && claimant.getOwner().canOwnTile(tile)
                    && (claimant.getOwner().isIndian()
                        || claimant.getTile().getDistanceTo(tile)
                        <= claimant.getRadius())) {
                    // Weight claimant settlements:
                    //   settlements owned by the same player
                    //     > settlements owned by same type of player
                    //     > other settlements
                    int value = (claimant.getOwner() == this) ? 3
                        : (claimant.getOwner().isEuropean()
                            == this.isEuropean()) ? 2
                        : 1;
                    if (votes.get(claimant) != null) {
                        value += votes.get(claimant);
                    }
                    votes.put(claimant, value);
                }
            }
            boolean lastResort = false;
            int bestValue = 0;
            claimant = null;
            for (Entry<Settlement, Integer> entry : votes.entrySet()) {
                if (avoid == entry.getKey()) {
                    lastResort = true;
                    continue;
                }
                int value = entry.getValue();
                if (bestValue < value) {
                    bestValue = value;
                    claimant = entry.getKey();
                }
            }
            if (claimant == null && lastResort) claimant = avoid;
            claims.put(tile, claimant);
        }
        for (Entry<Tile, Settlement> e : claims.entrySet()) {
            Tile t = e.getKey();
            if ((claimant = e.getValue()) == null) {
                t.changeOwnership(null, null);//-til
            } else {
                ServerPlayer newOwner = (ServerPlayer)claimant.getOwner();
                t.changeOwnership(newOwner, claimant);//-til
            }
        }
    }

    /**
     * Create units from a list of abstract units.  Only used by
     * Europeans at present.
     *
     * -vis: Visibility issues depending on location.
     * -til: Tile appearance issue if created in a colony (not ATM)
     *
     * @param abstractUnits The list of {@code AbstractUnit}s to
     *     create.
     * @param location The {@code Location} where the units will
     *     be created.
     * @return A list of units created.
     */
    public List<Unit> createUnits(List<AbstractUnit> abstractUnits,
                                  Location location) {
        if (location == null) return Collections.<Unit>emptyList();
        List<Unit> units = new ArrayList<>();

        final Game game = getGame();
        final Specification spec = game.getSpecification();
        for (AbstractUnit au : abstractUnits) {
            UnitType type = au.getType(spec);
            Role role = au.getRole(spec);
            // @compat 0.10.x
            // The REF always had an exemption from the availability
            // rules.  We are transitioning to it being subject to the
            // normal rules, which requires REF nations to have the
            // INDEPENDENT_NATION ability (or they do not get
            // man-o-war).  We are also handling the role transition.
            //
            // Drop the isREF() branch when the compatibility code
            // goes away.
            if (isREF()) {
                if (!role.isAvailableTo(this, type) && null != role.getId()) {
                    switch (role.getId()) {
                    case "model.role.soldier":
                        role = spec.getRole("model.role.infantry");
                        break;
                    case "model.role.dragoon":
                        role = spec.getRole("model.role.cavalry");
                        break;
                    default:
                        break;
                    }
                }
            } else {
                if (!type.isAvailableTo(this)) {
                    logger.warning("Ignoring abstract unit " + au
                        + " unavailable to: " + getId());
                    continue;
                }
                if (!role.isAvailableTo(this, type)) {
                    logger.warning("Ignoring abstract unit " + au
                        + " with role " + role
                        + " unavailable to: " + getId());
                    continue;
                }
            }
            // end @compat 0.10.x
            for (int i = 0; i < au.getNumber(); i++) {
                ServerUnit su = new ServerUnit(game, location, this, type,
                                               role);//-vis(this)
                units.add(su);
            }
        }
        return units;
    }

    /**
     * Embark the land units.  For all land units, find a naval unit
     * to carry it.  Fill greedily, so as if there is excess naval
     * capacity then the naval units at the end of the list will tend
     * to be empty or very lightly filled, allowing them to defend the
     * whole fleet at full strength.  Returns a list of units that
     * could not be placed on ships.
     *
     * -vis: Has visibility implications depending on the initial
     * location of the loaded units.  Usually ATM this is Europe which
     * is safe, but beware.
     * -til: Safe while in Europe though.
     *
     * @param landUnits A list of land units to put on ships.
     * @param navalUnits A list of ships to put land units on.
     * @param random A pseudo-random number source.
     * @return a list of units left over
     */
    public List<Unit> loadShips(List<Unit> landUnits,
                                List<Unit> navalUnits,
                                Random random) {
        List<Unit> leftOver = new ArrayList<>();
        randomShuffle(logger, "Naval load", navalUnits, random);
        randomShuffle(logger, "Land load", landUnits, random);
        LogBuilder lb = new LogBuilder(256);
        lb.mark();
        for (Unit unit : landUnits) {
            Unit carrier = find(navalUnits, u -> u.canAdd(unit));
            if (carrier != null) {
                unit.setLocation(carrier);//-vis(owner)
                lb.add(unit, " -> ", carrier, ", ");
            } else {
                leftOver.add(unit);
            }
        }
        if (lb.grew("Load ships: ")) {
            lb.shrink(", ");
            lb.log(logger, Level.FINEST);
        }        
        return leftOver;
    }

    /**
     * Simpler frontend to loadShips.
     *
     * @param units The {@code Unit}s to load.
     * @return The left over units.
     */
    public List<Unit> loadShips(List<Unit> units) {
        List<Unit> landUnits = new ArrayList<Unit>();
        List<Unit> navalUnits = new ArrayList<Unit>();
        for (Unit u : units) {
            if (u.isNaval()) navalUnits.add(u); else landUnits.add(u);
        }
        return loadShips(landUnits, navalUnits, null);
    }

    /**
     * Calculates the price of a group of mercenaries for this player.
     *
     * @param mercenaries A list of mercenaries to price.
     * @return The price.
     */
    public int priceMercenaries(List<AbstractUnit> mercenaries) {
        int mercPrice = sum(mercenaries, au -> getPrice(au));
        if (!checkGold(mercPrice)) mercPrice = getGold();
        return mercPrice;
    }

    /**
     * Flush any market price changes.
     *
     * @param cs A {@code ChangeSet} to update.
     * @return True if the market has changed.
     */
    public boolean csFlushMarket(ChangeSet cs) {
        Market market = getMarket();
        if (market == null) return false;
        final Specification spec = getSpecification();
        boolean ret = false;
        StringBuilder sb = new StringBuilder(32);
        sb.append("Flush market for ").append(getId()).append(':');
        for (GoodsType goodsType : transform(spec.getGoodsTypeList(),
                                             gt -> csFlushMarket(gt, cs))) {
            sb.append(' ').append(goodsType.getId());
            ret = true;
        }
        if (ret) logger.finest(sb.toString());
        return ret;
    }

    /**
     * Flush any market price changes for a specified goods type.
     *
     * @param type The {@code GoodsType} to check.
     * @param cs A {@code ChangeSet} to update.
     * @return True if the market price had changed.
     */
    public boolean csFlushMarket(GoodsType type, ChangeSet cs) {
        final Market market = getMarket();
        boolean ret = market.hasPriceChanged(type);
        if (ret) {
            // This type of goods has changed price, so we will update
            // the market and send a message as well.
            cs.addMessage(this,
                          market.makePriceChangeMessage(type));
            market.flushPriceChange(type);
            cs.add(See.only(this), market.getMarketData(type));
        }
        return ret;
    }

    /**
     * Buy goods in Europe.
     *
     * @param container The {@code GoodsContainer} to carry the goods.
     * @param type The {@code GoodsType} to buy.
     * @param amount The amount of goods to buy.
     * @return The amount actually removed from the market, or
     *     negative on failure.
     */
    public int buy(GoodsContainer container, GoodsType type, int amount) {
        final Market market = getMarket();
        final int price = market.getBidPrice(type, amount);
        if (!checkGold(price)) return -1;

        modifyGold(-price);
        market.modifySales(type, -amount);
        if (container != null) container.addGoods(type, amount);
        market.modifyIncomeBeforeTaxes(type, -price);
        market.modifyIncomeAfterTaxes(type, -price);
        int marketAmount = (int)applyModifiers((float)amount,
            getGame().getTurn(), Modifier.TRADE_BONUS, type);
        market.addGoodsToMarket(type, -marketAmount);
        return marketAmount;
    }

    /**
     * Sell goods in Europe.
     *
     * @param container An optional {@code GoodsContainer}
     *     carrying the goods.
     * @param type The {@code GoodsType} to sell.
     * @param amount The amount of goods to sell.
     * @return The amount actually added to the market, or negative on failure.
     */
    public int sell(GoodsContainer container, GoodsType type, int amount) {
        final Market market = getMarket();
        final int tax = getTax();
        final int incomeBeforeTaxes = market.getSalePrice(type, amount);
        final int incomeAfterTaxes = ((100 - tax) * incomeBeforeTaxes) / 100;
        
        modifyGold(incomeAfterTaxes);
        market.modifySales(type, amount);
        if (container != null) container.addGoods(type, -amount);
        market.modifyIncomeBeforeTaxes(type, incomeBeforeTaxes);
        market.modifyIncomeAfterTaxes(type, incomeAfterTaxes);
        int marketAmount = (int)applyModifiers((float)amount,
            getGame().getTurn(), Modifier.TRADE_BONUS, type);
        market.addGoodsToMarket(type, marketAmount);
        return marketAmount;
    }

    /**
     * Adds a player to the list of players for whom the stance has changed.
     *
     * @param other The {@code ServerPlayer} to add.
     */
    public void addStanceChange(ServerPlayer other) {
        if (!stanceDirty.contains(other)) stanceDirty.add(other);
    }

    /**
     * Modifies stance.
     *
     * @param stance The new {@code Stance}.
     * @param otherPlayer The {@code Player} wrt which the stance changes.
     * @param symmetric If true, change the otherPlayer stance as well.
     * @param cs A {@code ChangeSet} to update.
     * @return True if there was a change in stance at all.
     */
    public boolean csChangeStance(Stance stance, ServerPlayer otherPlayer,
                                  boolean symmetric, ChangeSet cs) {
        ServerPlayer other = otherPlayer;
        boolean change = false;
        Stance old = getStance(otherPlayer);

        if (old != stance) {
            int modifier = old.getTensionModifier(stance);
            setStance(otherPlayer, stance);
            if (modifier != 0) {
                csModifyTension(otherPlayer, modifier, cs);//+til
            }
            cs.addHistory(this, new HistoryEvent(getGame().getTurn(),
                    HistoryEvent.getEventTypeFromStance(stance), otherPlayer)
                .addStringTemplate("%nation%", otherPlayer.getNationLabel()));
            logger.info("Stance modification " + getName()
                + " " + old + " -> " + stance + " wrt " + otherPlayer.getName());
            this.addStanceChange(other);
            if (old != Stance.UNCONTACTED) {
                cs.addMessage(other,
                    new ModelMessage(MessageType.FOREIGN_DIPLOMACY,
                                     stance.getStanceChangeKey(), this)
                        .addStringTemplate("%nation%", getNationLabel()));
            }
            cs.addStance(See.only(this), this, stance, otherPlayer);
            cs.addStance(See.only(other), this, stance, otherPlayer);
            change = true;
        }
        if (symmetric && (old = otherPlayer.getStance(this)) != stance) {
            int modifier = old.getTensionModifier(stance);
            otherPlayer.setStance(this, stance);
            if (modifier != 0) {
                other.csModifyTension(this, modifier, cs);//+til
            }
            cs.addHistory(otherPlayer, new HistoryEvent(getGame().getTurn(),
                    HistoryEvent.getEventTypeFromStance(stance), this)
                .addStringTemplate("%nation%", this.getNationLabel()));
            logger.info("Stance modification " + otherPlayer.getName()
                + " " + old + " -> " + stance
                + " wrt " + getName() + " (symmetric)");
            other.addStanceChange(this);
            if (old != Stance.UNCONTACTED) {
                cs.addMessage(this,
                    new ModelMessage(MessageType.FOREIGN_DIPLOMACY,
                                     stance.getStanceChangeKey(), otherPlayer)
                        .addStringTemplate("%nation%",
                            otherPlayer.getNationLabel()));
            }
            cs.addStance(See.only(this), otherPlayer, stance, this);
            cs.addStance(See.only(other), otherPlayer, stance, this);
            change = true;
        }

        return change;
    }

    /**
     * Modifies the hostility against the given player.
     *
     * +til: Handles tile modifications.
     *
     * @param player The {@code Player}.
     * @param add The amount to add to the current tension level.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csModifyTension(Player player, int add, ChangeSet cs) {
        csModifyTension(player, add, null, cs);
    }

    /**
     * Modifies the hostility against the given player.
     *
     * +til: Handles tile modifications.
     *
     * @param player The {@code Player}.
     * @param add The amount to add to the current tension level.
     * @param origin A {@code Settlement} where the alarming event
     *     occurred.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csModifyTension(Player player, int add, Settlement origin,
                                ChangeSet cs) {
        Tension.Level oldLevel = getTension(player).getLevel();
        getTension(player).modify(add);
        if (oldLevel != getTension(player).getLevel()) {
            cs.add(See.only((ServerPlayer)player), this);
        }

        // Propagate tension change as settlement alarm to all
        // settlements except the one that originated it (if any).
        if (isIndian()) {
            for (IndianSettlement is : transform(getIndianSettlements(),
                    i -> i != origin && i.hasContacted(player))) {
                ((ServerIndianSettlement)is).csModifyAlarm(player, add,
                                                           false, cs);//+til
            }
        }
    }

    public void csPayUpkeep(Random random, ChangeSet cs) {
        final Specification spec = getSpecification();
        final Disaster bankruptcy = spec.getDisaster(Disaster.BANKRUPTCY);

        boolean changed = false;
        int upkeep = sum(getSettlements(), Settlement::getUpkeep);
        if (checkGold(upkeep)) {
            modifyGold(-upkeep);
            if (getBankrupt()) {
                setBankrupt(false);
                changed = true;
                // the only effects of a disaster that can be reversed
                // are the modifiers
                forEach(flatten(bankruptcy.getEffects(),
                                e -> e.getObject().getModifiers()),
                    m -> cs.addModifier(this, this, m, false));
                cs.addMessage(this,
                    new ModelMessage(MessageType.GOVERNMENT_EFFICIENCY,
                                     "model.player.disaster.bankruptcy.stop",
                                     this));
            }
        } else {
            modifyGold(-getGold());
            if (!getBankrupt()) {
                setBankrupt(true);
                changed = true;
                csApplyDisaster(random, null, bankruptcy, cs);
                cs.addMessage(this,
                    new ModelMessage(MessageType.GOVERNMENT_EFFICIENCY,
                                     "model.player.disaster.bankruptcy.start",
                                     this));
            }
        }
        if (upkeep > 0) cs.addPartial(See.only(this), this, "gold");
        if (changed) cs.addPartial(See.only(this), this, "bankrupt");
    }

    public void csNaturalDisasters(Random random, ChangeSet cs,
                                   int probability) {
        if (randomInt(logger, "Natural disaster", random, 100) < probability) {
            List<Colony> colonies = getColonyList();
            int size = colonies.size();
            if (size <= 0) return;
            // Randomly select a colony to start with, then generate
            // an appropriate disaster if possible, else continue with
            // the next colony, wrapping around if necessary.
            int start = randomInt(logger, "select colony", random, size);
            for (int i = 0; i < size; i++) {
                Colony colony = colonies.get((start + i) % size);
                Disaster disaster = RandomChoice.getWeightedRandom(logger,
                    "select disaster", colony.getDisasterChoices(), random);
                List<ModelMessage> messages = csApplyDisaster(random,
                    colony, disaster, cs);
                if (!messages.isEmpty()) {
                    cs.addMessage(this,
                        new ModelMessage(MessageType.DEFAULT,
                                         "model.player.disaster.strikes",
                                         colony)
                            .addName("%colony%", colony.getName())
                            .addName("%disaster%", disaster));
                    for (ModelMessage message : messages) {
                        cs.addMessage(this, message);
                    }
                    return;
                }
            }
        }
    }

    /**
     * Apply the effects of the given {@code Disaster} to the
     * given {@code Colony}, or the {@code Player} if the
     * {@code Colony} is {@code null}, and return a list of
     * appropriate {@code ModelMessage}s. Note that a disaster
     * might have no effect on a particular colony. In that case, the
     * returned list is empty.
     *
     * @param random A {@code Random} number source.
     * @param colony A {@code Colony}, or {@code null}.
     * @param disaster A {@code Disaster} value.
     * @param cs A {@code ChangeSet} to update.
     * @return A list of {@code ModelMessage}s, possibly empty.
     */
    public List<ModelMessage> csApplyDisaster(Random random, Colony colony,
                                              Disaster disaster, ChangeSet cs) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("Applying ").append(disaster.getNumberOfEffects())
            .append(" effect/s of disaster ")
            .append(Messages.getName(disaster));
        if (colony != null) sb.append(" to ").append(colony.getName());
        sb.append(':');
        List<Effect> effects = new ArrayList<>();
        switch (disaster.getNumberOfEffects()) {
        case ONE:
            effects.add(RandomChoice.getWeightedRandom(logger,
                    "Get effect of disaster", disaster.getEffects(), random));
            sb.append(' ').append(Messages.getName(effects.get(0)));
            break;
        case SEVERAL:
            for (RandomChoice<Effect> effect : disaster.getEffects()) {
                if (randomInt(logger, "Get effects of disaster", random, 100)
                    < effect.getProbability()) {
                    effects.add(effect.getObject());
                    sb.append(' ').append(Messages.getName(effect.getObject()));
                }
            }
            break;
        case ALL:
            for (RandomChoice<Effect> effect : disaster.getEffects()) {
                effects.add(effect.getObject());
                sb.append(' ').append(Messages.getName(effect.getObject()));
            }
        }
        if (effects.isEmpty()) sb.append(" All avoided");
        logger.fine(sb.toString());

        boolean colonyDirty = false;
        List<ModelMessage> messages = new ArrayList<>();
        ModelMessage mm;
outer:  for (Effect effect : effects) {
            mm = null;
            if (colony == null) {
                forEach(effect.getModifiers(), modifier -> {
                        if (modifier.getDuration() > 0) {
                            Modifier timedModifier = Modifier
                                .makeTimedModifier(modifier.getId(), modifier, getGame().getTurn());
                            modifier.setModifierIndex(Modifier.DISASTER_PRODUCTION_INDEX);
                            cs.addModifier(this, this, timedModifier, true);
                        } else {
                            cs.addModifier(this, this, modifier, true);
                        }
                    });
            } else {
                if (null != effect.getId()) {
                    switch (effect.getId()) {
                    case Effect.LOSS_OF_MONEY:
                        int plunder = Math.max(1, colony.getPlunder(null, random) / 5);
                        modifyGold(-plunder);
                        cs.addPartial(See.only(this), this, "gold");
                        mm = new ModelMessage(MessageType.DEFAULT,
                                              effect.getId(), this)
                            .addAmount("%amount%", plunder);
                        break;
                    case Effect.LOSS_OF_BUILDING:
                        Building building = getBuildingForEffect(colony, effect, random);
                        if (building != null) {
                            // Add message before damaging building
                            mm = new ModelMessage(MessageType.DEFAULT,
                                                  effect.getId(), colony)
                                .addNamed("%building%", building.getType());
                            csDamageBuilding(building, cs);
                            colonyDirty = true;
                        }
                        break;
                    case Effect.LOSS_OF_GOODS:
                        Goods goods = getRandomMember(logger, "select goods",
                            colony.getLootableGoodsList(),
                            random);
                        if (goods != null) {
                            goods.setAmount(Math.min(goods.getAmount() / 2, 50));
                            colony.removeGoods(goods);
                            mm = new ModelMessage(MessageType.DEFAULT,
                                                  effect.getId(), colony)
                                .addStringTemplate("%goods%", goods.getLabel(true));
                            colonyDirty = true;
                        }
                        break;
                    case Effect.LOSS_OF_UNIT:
                        {
                            Unit unit = getUnitForEffect(colony, effect, random);
                            if (unit != null) {
                                if (colony.getUnitCount() == 1) {
                                    messages.clear();
                                    mm = new ModelMessage(MessageType.DEFAULT,
                                        "model.player.disaster.effect.colonyDestroyed",
                                        this)
                                        .addName("%colony%", colony.getName());
                                    messages.add(mm);
                                    csDisposeSettlement(colony, cs);
                                    colonyDirty = false;
                                    break outer; // No point proceeding
                                }
                                mm = new ModelMessage(MessageType.DEFAULT,
                                                      effect.getId(), colony)
                                    .addStringTemplate("%unit%",
                                        unit.getLabel());
                                cs.addRemove(See.only(this), null, unit);
                                unit.dispose();//-vis: Safe, entirely within colony
                                colonyDirty = true;
                            }
                            break;
                        }
                    case Effect.DAMAGED_UNIT:
                        {
                            Unit unit = getUnitForEffect(colony, effect, random);
                            if (unit != null && unit.isNaval()) {
                                Location repairLocation = unit.getRepairLocation();
                                if (repairLocation == null) {
                                    mm = new ModelMessage(MessageType.DEFAULT,
                                                          effect.getId(),
                                                          colony)
                                        .addStringTemplate("%unit%",
                                            unit.getLabel());
                                    csSinkShip(unit, null, cs);
                                } else {
                                    mm = new ModelMessage(MessageType.DEFAULT,
                                                          effect.getId(),
                                                          colony)
                                        .addStringTemplate("%unit%",
                                            unit.getLabel());
                                    csDamageShip(unit, repairLocation, cs);
                                }
                                colonyDirty = true;
                            }
                            break;
                        }
                    default:
                        mm = new ModelMessage(MessageType.DEFAULT,
                                              effect.getId(), colony);
                        forEach(effect.getModifiers(), m -> {
                                if (m.getDuration() > 0) {
                                    Modifier timedModifier = Modifier
                                        .makeTimedModifier(m.getId(), m, getGame().getTurn());
                                    timedModifier.setModifierIndex(Modifier.DISASTER_PRODUCTION_INDEX);
                                    cs.addModifier(this, colony, timedModifier, true);
                                } else {
                                    cs.addModifier(this, colony, m, true);
                                }
                            });
                        colonyDirty |= first(effect.getModifiers()) != null;
                        break;
                    }
                }
            }
            if (mm != null) messages.add(mm);
        }
        if (colonyDirty) cs.add(See.perhaps(), colony);
        return messages;
    }

    public Building getBuildingForEffect(Colony colony, Effect effect, Random random) {
        List<Building> buildings = colony.getBurnableBuildings();
        return (buildings.isEmpty()) ? null
            : getRandomMember(logger, "Select building for effect",
                              buildings, random);
    }

    public Unit getUnitForEffect(Colony colony, Effect effect, Random random) {
        List<Unit> units = transform(colony.getAllUnitsList(),
                                     u -> effect.appliesTo(u.getType()));
        return (units.isEmpty()) ? null
            : getRandomMember(logger, "Select unit for effect", units, random);
    }

    /**
     * Propagate an European market trade to the other European markets.
     *
     * @param type The type of goods that was traded.
     * @param amount The amount of goods that was traded.
     * @param random A pseudo-random number source.
     */
    public void propagateToEuropeanMarkets(GoodsType type, int amount,
                                           Random random) {
        if (!type.isStorable()) return;

        // Propagate 5-30% of the original change.
        final int lowerBound = 5; // FIXME: to spec
        final int upperBound = 30;// FIXME: to spec
        amount *= randomInt(logger, "Propagate goods", random,
                            upperBound - lowerBound + 1) + lowerBound;
        amount /= 100;
        if (amount == 0) return;

        // Do not need to update the clients here, these changes happen
        // while it is not their turn.
        for (Player p : getGame().getLiveEuropeanPlayerList(this)) {
            Market market = p.getMarket();
            if (market != null) market.addGoodsToMarket(type, amount);
        }
    }

    /**
     * Add or remove a standard yearly amount of storable goods, and a
     * random extra amount of a random type.  Then push out all the
     * accumulated trades.
     *
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csYearlyGoodsAdjust(Random random, ChangeSet cs) {
        final Game game = getGame();
        final List<GoodsType> goodsTypes = game.getSpecification()
            .getStorableGoodsTypeList();
        final Market market = getMarket();

        // Pick a random type of storable goods to add/remove an extra
        // amount of.
        GoodsType extraType;
        while (!(extraType = getRandomMember(logger, "Choose goods type",
                                             goodsTypes, random)).isStorable());

        // Remove standard amount, and the extra amount.
        for (GoodsType type : transform(goodsTypes,
                                        gt -> market.hasBeenTraded(gt))) {
            boolean add = market.getAmountInMarket(type)
                < type.getInitialAmount();
            int amount = game.getTurn().getNumber() / 10;
            if (type == extraType) amount = 2 * amount + 1;
            if (amount <= 0) continue;
            amount = randomInt(logger, "Market adjust " + type, random, amount);
            if (!add) amount = -amount;
            market.addGoodsToMarket(type, amount);
            logger.finest(getName() + " adjust of " + amount
                + " " + type
                + ", total: " + market.getAmountInMarket(type)
                + ", initial: " + type.getInitialAmount());
            addExtraTrade(new AbstractGoods(type, amount));
        }

        flushExtraTrades(random);
        csFlushMarket(cs);
    }

    /**
     * Starts a new turn for a player.
     *
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csStartTurn(Random random, ChangeSet cs) {
        Game game = getGame();
        if (isEuropean()) {
            csBombardEnemyShips(random, cs);

            csYearlyGoodsAdjust(random, cs);

            FoundingFather father = checkFoundingFather();
            if (father != null) {
                csAddFoundingFather(father, random, cs);
                clearOfferedFathers();
            }

            List<FoundingFather> ffs = getOfferedFathers();
            if (canRecruitFoundingFather() && ffs.isEmpty()) {
                ffs = getRandomFoundingFathers(random);
                setOfferedFathers(ffs);
            }
            if (!ffs.isEmpty()) {
                cs.add(See.only(this), ChangePriority.CHANGE_EARLY,
                    new ChooseFoundingFatherMessage(ffs, null));
            }

            if (updateScore()) {
                cs.addPartial(See.only(this), this, "score");
            }

        } else if (isIndian()) {
            // We do not have to worry about Player level stance
            // changes driving Stance, as that is delegated to the AI.
            //
            // However we want to notify of individual settlements
            // that change tension level, but there are complex
            // interactions between settlement and player tensions.
            // The simple way to do it is just to save all old tension
            // levels and check if they have changed after applying
            // all the changes.
            List<IndianSettlement> allSettlements = getIndianSettlementList();
            java.util.Map<IndianSettlement,
                java.util.Map<Player, Tension.Level>> oldLevels = new HashMap<>();
            for (IndianSettlement is : allSettlements) {
                java.util.Map<Player, Tension.Level> oldLevel = new HashMap<>();
                oldLevels.put(is, oldLevel);
                for (Player enemy : game.getLiveEuropeanPlayerList(this)) {
                    Tension alarm = is.getAlarm(enemy);
                    oldLevel.put(enemy,
                        (alarm == null) ? null : alarm.getLevel());
                }
            }

            // Do the settlement alarms first.
            for (IndianSettlement is : allSettlements) {
                java.util.Map<Player, Integer> extra = new HashMap<>();
                for (Player enemy : game.getLiveEuropeanPlayerList(this)) {
                    extra.put(enemy, 0);
                }

                // Look at the uses of tiles surrounding the settlement.
                int alarmRadius = is.getRadius() + ALARM_RADIUS;
                for (Tile tile : is.getTile().getSurroundingTiles(alarmRadius)) {
                    Colony colony = tile.getColony();
                    if (tile.getFirstUnit() != null) { // Military units
                        Player enemy =  tile.getFirstUnit().getOwner();
                        if (enemy.isEuropean()) {
                            Integer alarm = extra.get(enemy);
                            if (alarm == null) continue;
                            alarm += (int)sumDouble(tile.getUnits(),
                                u -> u.isOffensiveUnit() && !u.isNaval(),
                                u -> u.getType().getOffence());
                            extra.put(enemy, alarm);
                        }
                    } else if (colony != null) { // Colonies
                        Player enemy = colony.getOwner();
                        extra.put(enemy, extra.get(enemy)
                                  + ALARM_TILE_IN_USE
                                  + colony.getUnitCount());
                    } else if (tile.getOwningSettlement() != null) { // Control
                        Player enemy = tile.getOwningSettlement().getOwner();
                        if (enemy != null && enemy.isEuropean()) {
                            extra.put(enemy, extra.get(enemy)
                                      + ALARM_TILE_IN_USE);
                        }
                    }
                }
                // Missionary helps reducing alarm a bit
                if (is.hasMissionary()) {
                    Unit missionary = is.getMissionary();
                    int missionAlarm = getGame().getSpecification()
                        .getInteger(GameOptions.MISSION_INFLUENCE);
                    if (missionary.hasAbility(Ability.EXPERT_MISSIONARY)) {
                        missionAlarm *= 2;
                    }
                    Player enemy = missionary.getOwner();
                    extra.put(enemy,
                              extra.get(enemy) + missionAlarm);
                }
                // Apply modifiers, and commit the total change.
                forEachMapEntry(extra, e -> e.getValue() != 0, e -> {
                        final Player player = e.getKey();
                        int change = (int)player.applyModifiers((float)e.getValue(),
                            game.getTurn(), Modifier.NATIVE_ALARM_MODIFIER);
                        ((ServerIndianSettlement)is)
                            .csModifyAlarm(player, change, true, cs);//+til
                    });
            }

            // Calm down a bit at the whole-tribe level.
            for (Player enemy : transform(game.getLiveEuropeanPlayers(this),
                                          p -> getTension(p).getValue() > 0)) {
                int change = -getTension(enemy).getValue()/100 - 4;
                csModifyTension(enemy, change, cs);//+til
            }

            // Now collect the settlements that changed.
            // Update those that changed, and add messages for selected
            // worsening relation transitions.
            for (IndianSettlement is : allSettlements) {
                forEachMapEntry(oldLevels.get(is), e ->
                    ((ServerIndianSettlement)is)
                        .csCheckTension(e.getKey(), e.getValue(), cs));
            }

            // All updated, start the turn for the settlements.
            for (IndianSettlement is : allSettlements) {
                ((ServerIndianSettlement)is).csStartTurn(random, cs);
            }
        }
    }

    /**
     * All player colonies bombard all available targets.
     *
     * @param random A random number source.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csBombardEnemyShips(Random random, ChangeSet cs) {
        // A unit is a bombard target only if it is:
        //     - not one of ours
        //     - a naval unit at sea
        //     - either we are at war with them or they are pirates
        final Predicate<Unit> bombardUnit = u -> 
            (u.getOwner() != this
                && u.isNaval() && !u.getTile().isLand()
                && (atWarWith(u.getOwner()) || u.hasAbility(Ability.PIRACY)));
        // For all colonies that are able to bombard, search neighbouring
        // tiles for targets, and fire!
        for (Colony c : transform(getColonies(), Colony::canBombardEnemyShip)) {
            Tile tile = c.getTile();
            for (Unit u : transform(flatten(tile.getSurroundingTiles(1, 1),
                                            Tile::getUnits),
                                    bombardUnit)) {
                csCombat(c, u, null, random, cs);
            }
        }
    }

    /**
     * Adds a founding father to a players continental congress.
     *
     * @param father The {@code FoundingFather} to add.
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csAddFoundingFather(FoundingFather father, Random random,
                                    ChangeSet cs) {
        final Game game = getGame();
        final Specification spec = game.getSpecification();
        final ServerEurope europe = (ServerEurope)getEurope();
        final Turn turn = game.getTurn();
        boolean europeDirty = false, visibilityChange = false;

        addFather(father);
        addHistory(new HistoryEvent(turn,
                HistoryEvent.HistoryEventType.FOUNDING_FATHER, this)
                    .addNamed("%father%", father));
        // FIXME: We do not want to have to update the whole player
        // just to get the FF into the client, but for now if there
        // are modifiers that is the only way to do it.
        cs.add(See.only(this), this);
        cs.addMessage(this,
            new ModelMessage(ModelMessage.MessageType.SONS_OF_LIBERTY,
                             "model.player.foundingFatherJoinedCongress",
                             this)
                      .addNamed("%foundingFather%", father)
                      .add("%description%", father.getDescriptionKey()));

        List<AbstractUnit> units = father.getUnitList();
        if (units != null && !units.isEmpty() && europe != null) {
            createUnits(father.getUnitList(), europe);//-vis: safe, Europe
            europeDirty = true;
        }

        java.util.Map<UnitType, UnitType> upgrades = father.getUpgrades();
        if (upgrades != null) {
            for (Unit u : getUnitList()) {
                UnitType newType = upgrades.get(u.getType());
                if (newType != null) {
                    u.changeType(newType);//-vis(this)
                    visibilityChange = true;
                    cs.add(See.perhaps(), u);
                }
            }
        }

        // Some modifiers are special
        for (Modifier m : iterable(father.getModifiers())) {
            if (Modifier.LINE_OF_SIGHT_BONUS.equals(m.getId())) {
                // Check for tiles that are now visible.  They need to be
                // explored, and always updated so that units are visible.
                // *Requires that canSee[] has **not** been updated yet!*
                Stream<Colony> colonies = (hasAbility(Ability.SEE_ALL_COLONIES))
                    ? getGame().getAllColonies(null)
                    : getColonies();
                Set<Tile> tiles
                    = transform(concat(flatten(colonies,
                                               c -> c.getVisibleTiles().stream()),
                                       flatten(getUnits(),
                                               u -> u.getVisibleTiles().stream())),
                                t -> !canSee(t), Function.identity(),
                                Collectors.toSet());
                exploreTiles(tiles); // Explore the new tiles
                cs.add(See.only(this), tiles);
                visibilityChange = true;
            } else if (Modifier.SOL.equals(m.getId())) {
                for (Colony c : getColonyList()) {
                    c.addLiberty(0); // Kick the SoL and production bonus
                    c.invalidateCache();
                }
            }
        }

        // Some events are special
        for (Event event : father.getEvents()) {
            String eventId = event.getId();
            switch (eventId) {
            case "model.event.resetBannedMissions":
                for (Player p : game.getLiveNativePlayerList()) {
                    if (p.missionsBanned(this)) {
                        p.removeMissionBan(this);
                        cs.add(See.only(this), p);
                    }
                }
                break;

            case "model.event.resetNativeAlarm":
                for (Player p : transform(game.getLiveNativePlayers(),
                                          p -> p.hasContacted(this))) {
                    p.setTension(this, new Tension(Tension.TENSION_MIN));
                    for (IndianSettlement is : transform(p.getIndianSettlements(),
                                                         is -> is.hasContacted(this))) {
                        is.getTile().cacheUnseen();//+til
                        is.setAlarm(this, new Tension(Tension.TENSION_MIN));//-til
                        cs.add(See.only(this), is);
                    }
                    csChangeStance(Stance.PEACE, (ServerPlayer)p, true, cs);
                }
                break;

            case "model.event.boycottsLifted":
                Market market = getMarket();
                for (GoodsType goodsType : spec.getGoodsTypeList()) {
                    if (market.getArrears(goodsType) > 0) {
                        market.setArrears(goodsType, 0);
                        cs.add(See.only(this), market.getMarketData(goodsType));
                    }
                }
                break;

            case "model.event.freeBuilding":
                BuildingType type = spec.getBuildingType(event.getValue());
                for (Colony c : getColonyList()) {
                    ((ServerColony)c).csFreeBuilding(type, cs);
                }
                break;

            case "model.event.seeAllColonies":
                visibilityChange = true;//-vis(this), can now see other colonies
                for (Colony colony : game.getAllColoniesList(null)) {
                    final Tile t = colony.getTile();
                    Set<Tile> tiles = new HashSet<>();
                    if (exploreTile(t)) {
                        if (!hasAbility(Ability.SEE_ALL_COLONIES)) {
                            // FreeCol ruleset adds this ability
                            // allowing full visibility of colony,
                            // whereas Col1 showed colonies as size 1.
                            Tile c = t.copy(game, Tile.class);
                            c.getColony().setDisplayUnitCount(1);
                            t.setCachedTile(this, c);
                        }
                        tiles.add(t);
                    }
                    // Revealed tiles in 11x11 block in Col1
                    final int fullRadius = (int)father
                        .applyModifiers((float)colony.getLineOfSight(),
                                        turn, Modifier.EXPOSED_TILES_RADIUS);
                    tiles.addAll(exploreTiles(t.getSurroundingTiles(1,
                                fullRadius)));
                    cs.add(See.only(this), tiles);
                }

            case "model.event.newRecruits":
                if (europe != null) {
                    europeDirty = europe.replaceRecruits(random);
                }
                break;

            case "model.event.movementChange":
                for (Unit u : transform(getUnits(), u -> u.getMovesLeft() > 0)) {
                    u.setMovesLeft(u.getInitialMovesLeft());
                    cs.addPartial(See.only(this), u, "movesLeft");
                }
                break;

            default:
                break;
            }
        }

        if (europeDirty) cs.add(See.only(this), europe);
        if (visibilityChange) invalidateCanSeeTiles(); //+vis(this)
    }

    /**
     * Get a list of free building types this player has access to
     * through its choice of founding fathers.  Used to upgrade newly
     * captured colonies.
     *
     * @return A list of free {@code BuildingType}s.
     */
    public List<BuildingType> getFreeBuildingTypes() {
        final Specification spec = getGame().getSpecification();
        return transform(flatten(getFathers(), ff -> ff.getEvents().stream()),
                         matchKeyEquals("model.event.freeBuilding", Event::getId),
                         ev -> spec.getBuildingType(ev.getValue()));
    }

    /**
     * Claim land.
     *
     * @param tile The {@code Tile} to claim.
     * @param settlement The {@code Settlement} to claim for.
     * @param price The price to pay for the land, which must agree
     *     with the owner valuation, unless negative which denotes stealing.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csClaimLand(Tile tile, Settlement settlement, int price,
                            ChangeSet cs) {
        ServerPlayer owner = (ServerPlayer)tile.getOwner();
        Settlement ownerSettlement = tile.getOwningSettlement();
        tile.cacheUnseen();//+til
        tile.changeOwnership(this, settlement);//-vis(?),-til

        // Update the tile and any now-angrier owners, and the player
        // gold if a price was paid.
        cs.add(See.perhaps(), tile);
        if (price > 0) {
            modifyGold(-price);
            owner.modifyGold(price);
            cs.addPartial(See.only(this), this, "gold");
        } else if (price < 0 && owner.isIndian()) {
            ServerIndianSettlement sis = (ServerIndianSettlement)ownerSettlement;
            if (sis == null) {
                owner.csModifyTension(this, Tension.TENSION_ADD_LAND_TAKEN,
                                      cs);
            } else {
                sis.csModifyAlarm(this, Tension.TENSION_ADD_LAND_TAKEN,
                                  true, cs);
            }
        }
        logger.finest(this.getName() + " claimed " + tile
            + " from " + ((owner == null) ? "no-one" : owner.getName())
            + ", price: " + ((price == 0) ? "free" : (price < 0) ? "stolen"
                : price));
    }


    /**
     * A unit migrates from Europe.
     *
     * @param slot The slot within {@code Europe} to select the unit from.
     * @param type The type of migration occurring.
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csEmigrate(int slot, MigrationType type, Random random,
                           ChangeSet cs) {
        // Create the recruit, move it to the docks.
        ServerEurope europe = (ServerEurope)getEurope();
        UnitType recruitType = europe.extractRecruitable(slot, random);
        final Game game = getGame();
        final Specification spec = game.getSpecification();
        Role role = (spec.getBoolean(GameOptions.EQUIP_EUROPEAN_RECRUITS))
            ? recruitType.getDefaultRole()
            : spec.getDefaultRole();
        Unit unit = new ServerUnit(game, europe, this,
                                   recruitType, role);//-vis: safe/Europe

        // Handle migration type specific changes.
        switch (type) {
        case FOUNTAIN:
            setRemainingEmigrants(getRemainingEmigrants() - 1);
            break;
        case RECRUIT:
            modifyGold(-europe.getRecruitPrice());
            cs.addPartial(See.only(this), this, "gold");
            europe.increaseRecruitmentDifficulty();
            // Fall through
        case NORMAL:
            reduceImmigration();
            updateImmigrationRequired();
            cs.addPartial(See.only(this), this,
                          "immigration", "immigrationRequired");
            if (!MigrationType.specificMigrantSlot(slot)) {
                cs.addMessage(this, getEmigrationMessage(unit));
            }
            break;
        case SURVIVAL:
            // Add an informative message if this was a survival recruitment,
            cs.addMessage(this,
                new ModelMessage(ModelMessage.MessageType.UNIT_ADDED,
                                 "model.player.autoRecruit",
                                 this, unit)
                    .addNamed("%europe%", europe)
                    .addStringTemplate("%unit%", unit.getLabel()));
            break;
        default:
            throw new IllegalArgumentException("Bogus migration type");
        }
        cs.add(See.only(this), europe);
    }

    /**
     * Combat.
     *
     * @param attacker The {@code FreeColGameObject} that is attacking.
     * @param defender The {@code FreeColGameObject} that is defending.
     * @param crs A list of {@code CombatResult}s defining the result.
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csCombat(FreeColGameObject attacker,
                         FreeColGameObject defender,
                         List<CombatResult> crs,
                         Random random,
                         ChangeSet cs) {
        CombatModel combatModel = getGame().getCombatModel();
        boolean isAttack = combatModel.combatIsAttack(attacker, defender);
        boolean isBombard = combatModel.combatIsBombard(attacker, defender);
        Unit attackerUnit = null;
        Settlement attackerSettlement = null;
        Tile attackerTile = null;
        Unit defenderUnit = null;
        //ServerPlayer attackerPlayer = null;
        ServerPlayer defenderPlayer = null;
        Tile defenderTile = null;
        if (isAttack) {
            attackerUnit = (Unit)attacker;
            //attackerPlayer = (ServerPlayer)attackerUnit.getOwner();
            attackerTile = attackerUnit.getTile();
            defenderUnit = (Unit)defender;
            defenderPlayer = (ServerPlayer)defenderUnit.getOwner();
            defenderTile = defenderUnit.getTile();
            boolean bombard = attackerUnit.hasAbility(Ability.BOMBARD);
            cs.addAttribute(See.only(this), "sound",
                (attackerUnit.isNaval()) ? "sound.attack.naval"
                : (bombard) ? "sound.attack.artillery"
                : (attackerUnit.isMounted()) ? "sound.attack.mounted"
                : "sound.attack.foot");
            if (attackerUnit.getOwner().isIndian()
                && defenderPlayer.isEuropean()
                && defenderUnit.getLocation().getColony() != null
                && !defenderPlayer.atWarWith(attackerUnit.getOwner())) {
                StringTemplate attackerNation
                    = attackerUnit.getApparentOwnerName();
                Colony colony = defenderUnit.getLocation().getColony();
                cs.addMessage(defenderPlayer,
                    new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                     "combat.raid.ours", colony)
                        .addName("%colony%", colony.getName())
                        .addStringTemplate("%nation%", attackerNation));
            }
        } else if (isBombard) {
            attackerSettlement = (Settlement)attacker;
            //attackerPlayer = (ServerPlayer)attackerSettlement.getOwner();
            attackerTile = attackerSettlement.getTile();
            defenderUnit = (Unit)defender;
            defenderPlayer = (ServerPlayer)defenderUnit.getOwner();
            defenderTile = defenderUnit.getTile();
            cs.addAttribute(See.only(this), "sound", "sound.attack.bombard");
        } else {
            throw new IllegalStateException("Bogus combat");
        }
        assert defenderTile != null;

        // If the combat results were not specified (usually the case),
        // query the combat model.
        if (crs == null) {
            crs = combatModel.generateAttackResult(random, attacker, defender);
        }
        if (crs.isEmpty()) {
            throw new IllegalStateException("empty attack result");
        }
        // Extract main result, insisting it is one of the fundamental cases,
        // and add the animation.
        // Set vis so that loser always sees things.
        // FIXME: Bombard animations
        See vis; // Visibility that insists on the loser seeing the result.
        CombatResult result = crs.remove(0);
        switch (result) {
        case NO_RESULT:
            vis = See.perhaps();
            break; // Do not animate if there is no result.
        case WIN:
            vis = See.perhaps().always(defenderPlayer);
            if (isAttack) {
                if (attackerTile == null
                    || attackerTile == defenderTile
                    || !attackerTile.isAdjacent(defenderTile)) {
                    logger.warning("Bogus attack from " + attackerTile
                        + " to " + defenderTile
                        + "\n" + FreeColDebugger.stackTraceToString());
                } else {
                    cs.addAttack(vis, attackerUnit, defenderUnit, true);
                }
            }
            break;
        case LOSE:
            vis = See.perhaps().always(this);
            if (isAttack) {
                if (attackerTile == null
                    || attackerTile == defenderTile
                    || !attackerTile.isAdjacent(defenderTile)) {
                    logger.warning("Bogus attack from " + attackerTile
                        + " to " + defenderTile
                        + "\n" + FreeColDebugger.stackTraceToString());
                } else {
                    cs.addAttack(vis, attackerUnit, defenderUnit, false);
                }
            }
            break;
        default:
            throw new IllegalStateException("generateAttackResult returned: "
                                            + result);
        }
        // Now process the details.
        boolean attackerTileDirty = false;
        boolean defenderTileDirty = false;
        boolean moveAttacker = false;
        boolean burnedNativeCapital = false;
        Settlement settlement = defenderTile.getSettlement();
        Colony colony = defenderTile.getColony();
        IndianSettlement natives = (settlement instanceof IndianSettlement)
            ? (IndianSettlement) settlement
            : null;
        int attackerTension = 0;
        int defenderTension = 0;
        for (CombatResult cr : crs) {
            boolean ok;
            switch (cr) {
            case AUTOEQUIP_UNIT:
                ok = isAttack && settlement != null;
                if (ok) {
                    csAutoequipUnit(defenderUnit, settlement, cs);
                }
                break;
            case BURN_MISSIONS:
                ok = isAttack && result == CombatResult.WIN
                    && natives != null
                    && isEuropean() && defenderPlayer.isIndian();
                if (ok) {
                    defenderTileDirty |= natives.hasMissionary(this);
                    csBurnMissions(attackerUnit, natives, cs);
                }
                break;
            case CAPTURE_AUTOEQUIP:
                ok = isAttack && result == CombatResult.WIN
                    && settlement != null;
                if (ok) {
                    csCaptureAutoEquip(attackerUnit, defenderUnit, cs);
                    attackerTileDirty = defenderTileDirty = true;
                }
                break;
            case CAPTURE_COLONY:
                ok = isAttack && result == CombatResult.WIN
                    && colony != null
                    && isEuropean() && defenderPlayer.isEuropean();
                if (ok) {
                    csCaptureColony(attackerUnit, (ServerColony)colony,
                                    random, cs);
                    attackerTileDirty = defenderTileDirty = false;
                    moveAttacker = true;
                    defenderTension += Tension.TENSION_ADD_MAJOR;
                }
                break;
            case CAPTURE_CONVERT:
                ok = isAttack && result == CombatResult.WIN
                    && natives != null
                    && isEuropean() && defenderPlayer.isIndian();
                if (ok) {
                    csCaptureConvert(attackerUnit, natives, random, cs);
                    attackerTileDirty = true;
                }
                break;
            case CAPTURE_EQUIP:
                ok = isAttack && result != CombatResult.NO_RESULT;
                if (ok) {
                    if (result == CombatResult.WIN) {
                        csCaptureEquip(attackerUnit, defenderUnit, cs);
                    } else {
                        csCaptureEquip(defenderUnit, attackerUnit, cs);
                    }
                    attackerTileDirty = defenderTileDirty = true;
                }
                break;
            case CAPTURE_UNIT:
                ok = isAttack && result != CombatResult.NO_RESULT;
                if (ok) {
                    if (result == CombatResult.WIN) {
                        csCaptureUnit(attackerUnit, defenderUnit, cs);
                    } else {
                        csCaptureUnit(defenderUnit, attackerUnit, cs);
                    }
                    attackerTileDirty = true;
                    defenderTileDirty = false; // Added in csCaptureUnit
                }
                break;
            case DAMAGE_COLONY_SHIPS:
                ok = isAttack && result == CombatResult.WIN
                    && colony != null;
                if (ok) {
                    csDamageColonyShips(attackerUnit, colony, cs);
                    defenderTileDirty = true;
                }
                break;
            case DAMAGE_SHIP_ATTACK:
                ok = isAttack && result != CombatResult.NO_RESULT
                    && ((result == CombatResult.WIN) ? defenderUnit
                        : attackerUnit).isNaval();
                if (ok) {
                    if (result == CombatResult.WIN) {
                        csDamageShipAttack(attackerUnit, defenderUnit, cs);
                        defenderTileDirty = true;
                    } else {
                        csDamageShipAttack(defenderUnit, attackerUnit, cs);
                        attackerTileDirty = true;
                    }
                }
                break;
            case DAMAGE_SHIP_BOMBARD:
                ok = isBombard && result == CombatResult.WIN
                    && defenderUnit.isNaval();
                if (ok) {
                    csDamageShipBombard(attackerSettlement, defenderUnit, cs);
                    defenderTileDirty = true;
                }
                break;
            case DEMOTE_UNIT:
                ok = isAttack && result != CombatResult.NO_RESULT;
                if (ok) {
                    if (result == CombatResult.WIN) {
                        csDemoteUnit(attackerUnit, defenderUnit, cs);
                        defenderTileDirty = true;
                    } else {
                        csDemoteUnit(defenderUnit, attackerUnit, cs);
                        attackerTileDirty = true;
                    }
                }
                break;
            case DESTROY_COLONY:
                ok = isAttack && result == CombatResult.WIN
                    && colony != null
                    && isIndian() && defenderPlayer.isEuropean();
                if (ok) {
                    csDestroyColony(attackerUnit, colony, random, cs);
                    attackerTileDirty = defenderTileDirty = true;
                    moveAttacker = true;
                    attackerTension -= Tension.TENSION_ADD_NORMAL;
                    defenderTension += Tension.TENSION_ADD_MAJOR;
                }
                break;
            case DESTROY_SETTLEMENT:
                ok = isAttack && result == CombatResult.WIN
                    && natives != null
                    && defenderPlayer.isIndian();
                if (ok) {
                    burnedNativeCapital = settlement.isCapital();
                    csDestroySettlement(attackerUnit, natives, random, cs);
                    attackerTileDirty = defenderTileDirty = true;
                    moveAttacker = true;
                    attackerTension -= Tension.TENSION_ADD_NORMAL;
                    if (!burnedNativeCapital) {
                        defenderTension += Tension.TENSION_ADD_MAJOR;
                    }
                }
                break;
            case EVADE_ATTACK:
                ok = isAttack && result == CombatResult.NO_RESULT
                    && defenderUnit.isNaval();
                if (ok) {
                    csEvadeAttack(attackerUnit, defenderUnit, cs);
                }
                break;
            case EVADE_BOMBARD:
                ok = isBombard && result == CombatResult.NO_RESULT
                    && defenderUnit.isNaval();
                if (ok) {
                    csEvadeBombard(attackerSettlement, defenderUnit, cs);
                }
                break;
            case LOOT_SHIP:
                ok = isAttack && result != CombatResult.NO_RESULT
                    && attackerUnit.isNaval() && defenderUnit.isNaval();
                if (ok) {
                    if (result == CombatResult.WIN) {
                        csLootShip(attackerUnit, defenderUnit, cs);
                    } else {
                        csLootShip(defenderUnit, attackerUnit, cs);
                    }
                }
                break;
            case LOSE_AUTOEQUIP:
                ok = isAttack && result == CombatResult.WIN
                    && settlement != null;
                if (ok) {
                    csLoseAutoEquip(attackerUnit, defenderUnit, cs);
                    defenderTileDirty = true;
                }
                break;
            case LOSE_EQUIP:
                ok = isAttack && result != CombatResult.NO_RESULT;
                if (ok) {
                    if (result == CombatResult.WIN) {
                        csLoseEquip(attackerUnit, defenderUnit, cs);
                        defenderTileDirty = true;
                    } else {
                        csLoseEquip(defenderUnit, attackerUnit, cs);
                        attackerTileDirty = true;
                    }
                }
                break;
            case PILLAGE_COLONY:
                ok = isAttack && result == CombatResult.WIN
                    && colony != null
                    && isIndian() && defenderPlayer.isEuropean();
                if (ok) {
                    csPillageColony(attackerUnit, colony, random, cs);
                    defenderTileDirty = true;
                    attackerTension -= Tension.TENSION_ADD_NORMAL;
                }
                break;
            case PROMOTE_UNIT:
                ok = isAttack && result != CombatResult.NO_RESULT;
                if (ok) {
                    if (result == CombatResult.WIN) {
                        csPromoteUnit(attackerUnit, cs);
                        attackerTileDirty = true;
                    } else {
                        csPromoteUnit(defenderUnit, cs);
                        defenderTileDirty = true;
                    }
                }
                break;
            case SINK_COLONY_SHIPS:
                ok = isAttack && result == CombatResult.WIN
                    && colony != null;
                if (ok) {
                    csSinkColonyShips(attackerUnit, colony, cs);
                    defenderTileDirty = true;
                }
                break;
            case SINK_SHIP_ATTACK:
                ok = isAttack && result != CombatResult.NO_RESULT
                    && ((result == CombatResult.WIN) ? defenderUnit
                        : attackerUnit).isNaval();
                if (ok) {
                    if (result == CombatResult.WIN) {
                        csSinkShipAttack(attackerUnit, defenderUnit, cs);
                        defenderTileDirty = true;
                    } else {
                        csSinkShipAttack(defenderUnit, attackerUnit, cs);
                        attackerTileDirty = true;
                    }
                }
                break;
            case SINK_SHIP_BOMBARD:
                ok = isBombard && result == CombatResult.WIN
                    && defenderUnit.isNaval();
                if (ok) {
                    csSinkShipBombard(attackerSettlement, defenderUnit, cs);
                    defenderTileDirty = true;
                }
                break;
            case SLAUGHTER_UNIT:
                ok = isAttack && result != CombatResult.NO_RESULT;
                if (ok) {
                    if (result == CombatResult.WIN) {
                        csSlaughterUnit(attackerUnit, defenderUnit, cs);
                        defenderTileDirty = true;
                        attackerTension -= Tension.TENSION_ADD_NORMAL;
                        defenderTension += getSlaughterTension(defenderUnit);
                    } else {
                        csSlaughterUnit(defenderUnit, attackerUnit, cs);
                        attackerTileDirty = true;
                        attackerTension += getSlaughterTension(attackerUnit);
                        defenderTension -= Tension.TENSION_ADD_NORMAL;
                    }
                }
                break;
            default:
                ok = false;
                break;
            }
            if (!ok) {
                throw new IllegalStateException("Attack (result=" + result
                                                + ") has bogus subresult: "
                                                + cr);
            }
        }

        // Handle stance and tension.
        // - Privateers do not provoke stance changes but can set the
        //     attackedByPrivateers flag
        // - Attacks among Europeans imply war
        // - Burning of a native capital results in surrender
        // - Other attacks involving natives do not imply war, but
        //     changes in Tension can drive Stance, however this is
        //     decided by the native AI in their turn so just adjust tension.
        if (attacker.hasAbility(Ability.PIRACY)) {
            if (!defenderPlayer.getAttackedByPrivateers()) {
                defenderPlayer.setAttackedByPrivateers(true);
                cs.addPartial(See.only(defenderPlayer), defenderPlayer,
                              "attackedByPrivateers");
            }
        } else if (defender.hasAbility(Ability.PIRACY)) {
            ; // do nothing
        } else if (burnedNativeCapital) {
            defenderPlayer.getTension(this).setValue(Tension.SURRENDERED);
            // FIXME: just the tension
            cs.add(See.perhaps().always(this), defenderPlayer);
            csChangeStance(Stance.PEACE, defenderPlayer, true, cs);
            for (IndianSettlement is : transform(defenderPlayer.getIndianSettlements(),
                                                 is -> is.hasContacted(this))) {
                is.getAlarm(this).setValue(Tension.SURRENDERED);
                // Only update attacker with settlements that have
                // been seen, as contact can occur with its members.
                if (hasExplored(is.getTile())) {
                    cs.add(See.perhaps().always(this), is);
                } else {
                    cs.add(See.only(defenderPlayer), is);
                }
            }
        } else if (isEuropean() && defenderPlayer.isEuropean()) {
            csChangeStance(Stance.WAR, defenderPlayer, true, cs);
        } else { // At least one player is non-European
            if (isEuropean()) {
                csChangeStance(Stance.WAR, defenderPlayer, true, cs);
            } else if (isIndian()) {
                if (result == CombatResult.WIN) {
                    attackerTension -= Tension.TENSION_ADD_MINOR;
                } else if (result == CombatResult.LOSE) {
                    attackerTension += Tension.TENSION_ADD_MINOR;
                }
            }
            if (defenderPlayer.isEuropean()) {
                defenderPlayer.csChangeStance(Stance.WAR, this, true, cs);
            } else if (defenderPlayer.isIndian()) {
                if (result == CombatResult.WIN) {
                    defenderTension += Tension.TENSION_ADD_MINOR;
                } else if (result == CombatResult.LOSE) {
                    defenderTension -= Tension.TENSION_ADD_MINOR;
                }
            }
            if (attackerTension != 0) {
                this.csModifyTension(defenderPlayer,
                                     attackerTension, cs);//+til
            }
            if (defenderTension != 0) {
                defenderPlayer.csModifyTension(this,
                                               defenderTension, cs);//+til
            }
        }

        // Move the attacker if required.
        if (moveAttacker) {
            attackerUnit.setMovesLeft(attackerUnit.getInitialMovesLeft());
            ((ServerUnit) attackerUnit).csMove(defenderTile, random, cs);
            attackerUnit.setMovesLeft(0);
            // Move adds in updates for the tiles, but...
            attackerTileDirty = defenderTileDirty = false;
            // ...with visibility of perhaps().
            // Thus the defender might see the change,
            // but because its settlement is gone it also might not.
            // So add in another defender-specific update.
            // The worst that can happen is a duplicate update.
            cs.add(See.only(defenderPlayer), defenderTile);
        } else if (isAttack) {
            // The Revenger unit can attack multiple times, so spend
            // at least the eventual cost of moving to the tile.
            // Other units consume the entire move.
            if (attacker.hasAbility(Ability.MULTIPLE_ATTACKS)) {
                int movecost = attackerUnit.getMoveCost(defenderTile);
                attackerUnit.setMovesLeft(attackerUnit.getMovesLeft()
                                          - movecost);
            } else {
                attackerUnit.setMovesLeft(0);
            }
            if (!attackerTileDirty) {
                cs.addPartial(See.only(this), attacker, "movesLeft");
            }
        }

        // Make sure we always update the attacker and defender tile
        // if it is not already done yet.
        if (attackerTileDirty) {
            if (attackerSettlement != null) cs.remove(attackerSettlement);
            cs.add(vis, attackerTile);
        }
        if (defenderTileDirty) {
            if (settlement != null) cs.remove(settlement);
            cs.add(vis, defenderTile);
        }
    }

    /**
     * Gets the amount to raise tension by when a unit is slaughtered.
     *
     * @param loser The {@code Unit} that dies.
     * @return An amount to raise tension by.
     */
    private int getSlaughterTension(Unit loser) {
        // Tension rises faster when units die.
        Settlement settlement = loser.getSettlement();
        if (settlement != null) {
            if (settlement instanceof IndianSettlement) {
                return (settlement.isCapital())
                    ? Tension.TENSION_ADD_CAPITAL_ATTACKED
                    : Tension.TENSION_ADD_SETTLEMENT_ATTACKED;
            } else {
                return Tension.TENSION_ADD_NORMAL;
            }
        } else { // attack in the open
            return (loser.getHomeIndianSettlement() != null)
                ? Tension.TENSION_ADD_UNIT_DESTROYED
                : Tension.TENSION_ADD_MINOR;
        }
    }

    /**
     * Notifies of automatic arming.
     *
     * @param unit The {@code Unit} that is auto-equipping.
     * @param settlement The {@code Settlement} being defended.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csAutoequipUnit(Unit unit, Settlement settlement,
                                 ChangeSet cs) {
        ServerPlayer player = (ServerPlayer) unit.getOwner();
        cs.addMessage(player,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.automaticDefence", unit)
                .addStringTemplate("%unit%", unit.getLabel())
                .addName("%colony%", settlement.getName()));
    }

    /**
     * Burns a players missions.
     *
     * @param attacker The {@code Unit} that attacked.
     * @param is The {@code IndianSettlement} that was attacked.
     * @param cs The {@code ChangeSet} to update.
     */
    private void csBurnMissions(Unit attacker, IndianSettlement is,
                                ChangeSet cs) {
        ServerPlayer attackerPlayer = (ServerPlayer) attacker.getOwner();
        StringTemplate attackerNation = attackerPlayer.getNationLabel();
        ServerPlayer nativePlayer = (ServerPlayer)is.getOwner();
        StringTemplate nativeNation = nativePlayer.getNationLabel();

        // Message only for the European player
        cs.addMessage(attackerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.burnMissions", attacker, is)
                .addStringTemplate("%nation%", attackerNation)
                .addStringTemplate("%enemyNation%", nativeNation));

        // Burn down the missions
        boolean here = is.hasMissionary(attackerPlayer);
        for (IndianSettlement s : nativePlayer.getIndianSettlementsWithMissionaryList(attackerPlayer)) {
            ((ServerIndianSettlement)s).csKillMissionary(null, cs);
        }
        // Backtrack on updating this tile, avoiding duplication in csCombat
        if (here) cs.remove(is.getTile());
    }

    /**
     * Defender auto equips but loses and attacker captures the equipment.
     *
     * @param attacker The {@code Unit} that attacked.
     * @param defender The {@code Unit} that defended and loses equipment.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csCaptureAutoEquip(Unit attacker, Unit defender,
                                    ChangeSet cs) {
        Role role = defender.getAutomaticRole();
        csLoseAutoEquip(attacker, defender, cs);
        csCaptureEquipment(attacker, defender, role, cs);
    }

    /**
     * Captures a colony.
     *
     * @param attacker The attacking {@code Unit}.
     * @param colony The {@code ServerColony} to capture.
     * @param random A pseudo-random number source.
     * @param cs The {@code ChangeSet} to update.
     */
    private void csCaptureColony(Unit attacker, ServerColony colony,
                                 Random random, ChangeSet cs) {
        Game game = attacker.getGame();
        ServerPlayer attackerPlayer = (ServerPlayer) attacker.getOwner();
        StringTemplate attackerNation = attacker.getApparentOwnerName();
        ServerPlayer colonyPlayer = (ServerPlayer) colony.getOwner();
        StringTemplate colonyNation = colonyPlayer.getNationLabel();
        Tile tile = colony.getTile();
        int plunder = colony.getPlunder(attacker, random);

        // Handle history and messages before colony handover
        cs.addHistory(attackerPlayer,
            new HistoryEvent(game.getTurn(),
                HistoryEvent.HistoryEventType.CONQUER_COLONY, attackerPlayer)
                .addStringTemplate("%nation%", colonyNation)
                .addName("%colony%", colony.getName()));
        cs.addHistory(colonyPlayer,
            new HistoryEvent(game.getTurn(),
                HistoryEvent.HistoryEventType.COLONY_CONQUERED, attackerPlayer)
                      .addStringTemplate("%nation%", attackerNation)
                      .addName("%colony%", colony.getName()));
        cs.addMessage(attackerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.colonyCaptured.enemy", colony)
                .addName("%colony%", colony.getName())
                .addStringTemplate("%unit%", attacker.getLabel())
                .addStringTemplate("%enemyNation%", colonyNation)
                .addAmount("%amount%", plunder));
        cs.addMessage(colonyPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                            "combat.colonyCaptured.ours", tile)
                .addName("%colony%", colony.getName())
                .addStringTemplate("%enemyUnit%", attacker.getLabel())
                .addStringTemplate("%enemyNation%", attackerNation)
                .addAmount("%amount%", plunder));
        colonyPlayer.csLoseLocation(colony, cs);

        // Allocate some plunder
        if (plunder > 0) {
            attackerPlayer.modifyGold(plunder);
            colonyPlayer.modifyGold(-plunder);
            cs.addPartial(See.only(attackerPlayer), attackerPlayer, "gold");
            cs.addPartial(See.only(colonyPlayer), colonyPlayer, "gold");
        }

        // Remove goods party modifiers as they apply to a different monarch.
        for (Modifier m : transform(colony.getModifiers(),
                matchKey(Specification.COLONY_GOODS_PARTY_SOURCE,
                         Modifier::getSource))) colony.removeModifier(m);

        // Hand over the colony.  Inform former owner of loss of owned
        // tiles, and process possible increase in line of sight.
        // No need to display the colony tile or the attacker tile to
        // the attacking player as the unit is yet to move
        colony.csChangeOwner(attackerPlayer, true, cs);//-til,-vis(attackerPlayer,colonyPlayer)
        cs.addAttribute(See.only(attackerPlayer), "sound",
                        "sound.event.captureColony");

        // Ready to reset visibility
        attackerPlayer.invalidateCanSeeTiles();//+vis(attackerPlayer)
        colonyPlayer.invalidateCanSeeTiles();//+vis(colonyPlayer)
    }

    /**
     * Extracts a convert from a native settlement.
     *
     * @param attacker The {@code Unit} that is attacking.
     * @param is The {@code IndianSettlement} under attack.
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csCaptureConvert(Unit attacker, IndianSettlement is,
                                  Random random, ChangeSet cs) {
        final Specification spec = getGame().getSpecification();
        ServerPlayer attackerPlayer = (ServerPlayer)attacker.getOwner();
        ServerPlayer nativePlayer = (ServerPlayer)is.getOwner();
        StringTemplate convertNation = nativePlayer.getNationLabel();
        List<Unit> units = is.getAllUnitsList();
        ServerUnit convert = (ServerUnit)getRandomMember(logger,
            "Choose convert", units, random);
        if (nativePlayer.csChangeOwner(convert, attackerPlayer,
                                       UnitChangeType.CONVERSION,
                                       attacker.getTile(),
                                       cs)) { //-vis(attackerPlayer)
            convert.changeRole(spec.getDefaultRole(), 0);
            for (Goods g : convert.getGoods()) convert.removeGoods(g);
            convert.setMovesLeft(0);
            convert.setState(Unit.UnitState.ACTIVE);
            cs.add(See.only(nativePlayer), is.getTile());
            cs.addMessage(attackerPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 "combat.newConvertFromAttack", convert)
                    .addStringTemplate("%unit%", attacker.getLabel())
                    .addStringTemplate("%enemyNation%", convertNation)
                    .addStringTemplate("%enemyUnit%", convert.getLabel()));
            attackerPlayer.invalidateCanSeeTiles();//+vis(attackerPlayer)
        }
    }

    /**
     * Captures equipment.
     *
     * @param winner The {@code Unit} that captures equipment.
     * @param loser The {@code Unit} that defended and loses equipment.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csCaptureEquip(Unit winner, Unit loser, ChangeSet cs) {
        Role role = loser.getRole();
        csLoseEquip(winner, loser, cs);
        csCaptureEquipment(winner, loser, role, cs);
    }

    /**
     * Capture equipment.
     *
     * @param winner The {@code Unit} that is capturing equipment.
     * @param loser The {@code Unit} that is losing equipment.
     * @param role The {@code Role} wrest from the loser.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csCaptureEquipment(Unit winner, Unit loser,
                                    Role role, ChangeSet cs) {
        ServerPlayer winnerPlayer = (ServerPlayer) winner.getOwner();
        ServerPlayer loserPlayer = (ServerPlayer) loser.getOwner();
        Role newRole = winner.canCaptureEquipment(role);
        if (newRole != null) {
            List<AbstractGoods> newGoods
                = winner.getGoodsDifference(newRole, 1);
            GoodsType goodsType = newGoods.get(0).getType(); // FIXME: generalize
            winner.changeRole(newRole, 1);

            // Currently can not capture equipment back so this only
            // makes sense for native players, and the message is
            // native specific.
            if (winnerPlayer.isIndian()) {
                StringTemplate winnerNation = winner.getApparentOwnerName();
                cs.addMessage(loserPlayer,
                              new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                               "combat.equipmentCaptured",
                                               winnerPlayer)
                                  .addStringTemplate("%nation%", winnerNation)
                                  .addNamed("%equipment%", goodsType));

                // CHEAT: Immediately transferring the captured goods
                // back to a potentially remote settlement is pretty
                // dubious.  Apparently Col1 did it.  Better would be
                // to give the capturing unit a go-home-with-plunder mission.
                IndianSettlement is = winner.getHomeIndianSettlement();
                if (is != null) {
                    for (AbstractGoods ag : newGoods) {
                        is.addGoods(ag);
                        winnerPlayer.logCheat("teleported " + ag
                            + " back to " + is.getName());
                    }
                    cs.add(See.only(winnerPlayer), is);
                }
            }
        }
    }

    /**
     * Capture a unit.
     *
     * @param winner A {@code Unit} that is capturing.
     * @param loser A {@code Unit} to capture.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csCaptureUnit(Unit winner, Unit loser, ChangeSet cs) {
        ServerPlayer loserPlayer = (ServerPlayer) loser.getOwner();
        StringTemplate loserNation = loserPlayer.getNationLabel();
        StringTemplate loserLocation = loser.getLocation()
            .getLocationLabelFor(loserPlayer);
        StringTemplate loserLabel = loser.getLabel();
        ServerPlayer winnerPlayer = (ServerPlayer) winner.getOwner();
        StringTemplate winnerNation = winner.getApparentOwnerName();
        StringTemplate winnerLocation = winner.getLocation()
            .getLocationLabelFor(winnerPlayer);

        // Capture the unit.  There are visibility implications for
        // both players because the captured unit might be the only
        // one on its tile, and the winner might have captured a unit
        // with greater line of sight.  Remember where the loser was,
        // as it might be destroyed on capture.
        final Tile oldTile = loser.getTile();
        String key;
        String change = (winnerPlayer.isUndead()) ? UnitChangeType.UNDEAD
            : UnitChangeType.CAPTURE;
        if (loserPlayer.csChangeOwner(loser, winnerPlayer, change,
                                      winner.getTile(), cs)) {//-vis(both)
            loser.setMovesLeft(0);
            loser.setState(Unit.UnitState.ACTIVE);
            cs.add(See.perhaps().always(loserPlayer), oldTile);
            // Winner message post-capture when it owns the loser
            key = "combat.unitCaptured.enemy." + loser.getType().getSuffix();
            cs.addMessage(winnerPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 key, loser)
                    .addDefaultId("combat.unitCaptured.enemy")
                    .addStringTemplate("%location%", winnerLocation)
                    .addStringTemplate("%unit%", winner.getLabel())
                    .addStringTemplate("%enemyNation%", loserNation)
                    .addStringTemplate("%enemyUnit%", loserLabel));
        }
        key = "combat.unitCaptured.ours." + loser.getType().getSuffix();
        cs.addMessage(loserPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             key, oldTile)
                .addDefaultId("combat.unitCaptured.ours")
                .addStringTemplate("%location%", loserLocation)
                .addStringTemplate("%unit%", loserLabel)
                .addStringTemplate("%enemyNation%", winnerNation)
                .addStringTemplate("%enemyUnit%", winner.getLabel()));
        winnerPlayer.invalidateCanSeeTiles();//+vis(winnerPlayer)
        loserPlayer.invalidateCanSeeTiles();//+vis(loserPlayer)
    }

    /**
     * Damages all ships in a colony in preparation for capture.
     *
     * @param attacker The {@code Unit} that is damaging.
     * @param colony The {@code Colony} to damage ships in.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csDamageColonyShips(Unit attacker, Colony colony,
                                     ChangeSet cs) {
        boolean captureRepairing = getSpecification()
            .getBoolean(GameOptions.CAPTURE_UNITS_UNDER_REPAIR);
        List<Unit> units = transform(colony.getTile().getUnits(),
            u -> u.isNaval() && !(captureRepairing && u.isDamaged()));
        if (!units.isEmpty()) {
            final ServerPlayer shipPlayer = (ServerPlayer)colony.getOwner();
            final Unit ship = units.get(0);
            final Location repairLocation = ship.getRepairLocation();
            StringTemplate t = StringTemplate.label(", ");
            for (Unit u : units) {
                csDamageShip(u, repairLocation, cs);
                t.addStringTemplate(u.getLabel());
            }
            cs.addMessage(shipPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 "combat.shipsDamaged", shipPlayer)
                    .addStringTemplate("%ships%", t)
                    .addAmount("%number%", units.size())
                    .addStringTemplate("%repairLocation%",
                        repairLocation.getLocationLabelFor(shipPlayer)));
        }
    }

    /**
     * Damage a ship through normal attack.
     *
     * @param attacker The attacker {@code Unit}.
     * @param ship The {@code Unit} which is a ship to damage.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csDamageShipAttack(Unit attacker, Unit ship, ChangeSet cs) {
        ServerPlayer attackerPlayer = (ServerPlayer) attacker.getOwner();
        StringTemplate attackerNation = attacker.getApparentOwnerName();
        ServerPlayer shipPlayer = (ServerPlayer) ship.getOwner();
        Location shipLocation = ship.getLocation();
        Location repair = ship.getRepairLocation();
        StringTemplate repairLoc = repair.getLocationLabelFor(shipPlayer);
        StringTemplate shipNation = ship.getApparentOwnerName();

        cs.addMessage(attackerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipDamaged.enemy", attacker)
                .addStringTemplate("%location%",
                    shipLocation.getLocationLabelFor(attackerPlayer))
                .addStringTemplate("%unit%", attacker.getLabel())
                .addStringTemplate("%enemyNation%", shipNation)
                .addStringTemplate("%enemyUnit%", ship.getLabel()));
        cs.addMessage(shipPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipDamaged.ours", ship)
                .addStringTemplate("%location%",
                    shipLocation.getLocationLabelFor(shipPlayer))
                .addStringTemplate("%unit%", ship.getLabel())
                .addStringTemplate("%enemyUnit%", attacker.getLabel())
                .addStringTemplate("%enemyNation%", attackerNation)
                .addStringTemplate("%repairLocation%", repairLoc));

        csDamageShip(ship, repair, cs);
    }

    /**
     * Damage a ship through bombard.
     *
     * @param settlement The attacker {@code Settlement}.
     * @param ship The {@code Unit} which is a ship to damage.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csDamageShipBombard(Settlement settlement, Unit ship,
                                     ChangeSet cs) {
        ServerPlayer attackerPlayer = (ServerPlayer)settlement.getOwner();
        ServerPlayer shipPlayer = (ServerPlayer)ship.getOwner();
        Building building = ((Colony)settlement).getStockade();
        Location repair = ship.getRepairLocation();
        StringTemplate repairLoc = repair.getLocationLabelFor(shipPlayer);
        StringTemplate shipNation = ship.getApparentOwnerName();

        cs.addMessage(attackerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipDamagedByBombardment.enemy",
                             settlement)
                .addStringTemplate("%location%",
                    settlement.getLocationLabelFor(attackerPlayer))
                .addNamed("%building%", building)
                .addStringTemplate("%enemyNation%", shipNation)
                .addStringTemplate("%enemyUnit%", ship.getLabel()));
        cs.addMessage(shipPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipDamagedByBombardment.ours", ship)
                .addStringTemplate("%location%",
                    settlement.getLocationLabelFor(shipPlayer))
                .addStringTemplate("%unit%", ship.getLabel())
                .addNamed("%building%", building)
                .addStringTemplate("%enemyNation%",
                    attackerPlayer.getNationLabel())
                .addStringTemplate("%repairLocation%", repairLoc));

        csDamageShip(ship, repair, cs);
    }

    /**
     * Damage a ship.
     *
     * @param ship The naval {@code Unit} to damage.
     * @param repair The {@code Location} to send it to.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csDamageShip(Unit ship, Location repair, ChangeSet cs) {
        ServerPlayer player = (ServerPlayer) ship.getOwner();

        // Lose the goods and units aboard
        for (Goods g : ship.getGoodsContainer().getCompactGoods()) {
            ship.remove(g);
        }
        for (Unit u : ship.getUnitList()) {
            ship.remove(u);
            cs.addRemove(See.only(player), null, u);//-vis: safe, within unit
            u.dispose();
        }

        // Damage the ship and send it off for repair
        Location shipLoc = (repair instanceof Colony) ? repair.getTile()
            : repair;
        ship.setHitPoints(1);
        ship.setDestination(null);
        ship.setLocation(shipLoc);//-vis(player)
        ship.setState(Unit.UnitState.ACTIVE);
        ship.setMovesLeft(0);
        cs.add(See.only(player), (FreeColGameObject)shipLoc);
        player.invalidateCanSeeTiles();//+vis(player)
    }

    /**
     * Demotes a unit.
     *
     * @param winner The {@code Unit} that won.
     * @param loser The {@code Unit} that lost and should be demoted.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csDemoteUnit(Unit winner, Unit loser, ChangeSet cs) {
        final Specification spec = getSpecification();
        final ServerPlayer loserPlayer = (ServerPlayer)loser.getOwner();
        final StringTemplate loserNation = loser.getApparentOwnerName();
        final StringTemplate loserLocation = loser.getLocation()
            .getLocationLabelFor(loserPlayer);
        final StringTemplate loserLabel = loser.getLabel();
        final ServerPlayer winnerPlayer = (ServerPlayer)winner.getOwner();
        final StringTemplate winnerNation = winner.getApparentOwnerName();
        final StringTemplate winnerLocation = winner.getLocation()
            .getLocationLabelFor(winnerPlayer);
        final String suffix = loser.getType().getSuffix(); // pre-demotion value
        String key;
        
        UnitChange uc = loser.getUnitChange(UnitChangeType.DEMOTION);
        if (uc == null || uc.to == loser.getType()) {
            logger.warning("Demotion failed, type="
                + ((uc == null) ? "null" : "same type: " + uc.to));
            return;
        }
        loser.changeType(uc.to);//-vis(loserPlayer)
        loserPlayer.invalidateCanSeeTiles();//+vis(loserPlayer)

        key = "combat.unitDemoted.enemy." + suffix;
        cs.addMessage(winnerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             key, winner)
                .addDefaultId("combat.unitDemoted.enemy")
                .addStringTemplate("%location%", winnerLocation)
                .addStringTemplate("%unit%", winner.getLabel())
                .addStringTemplate("%enemyNation%", loserNation)
                .addStringTemplate("%oldName%", loserLabel)
                .addStringTemplate("%enemyUnit%", loser.getLabel()));
        key = "combat.unitDemoted.ours." + suffix;
        cs.addMessage(loserPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             key, loser)
                .addDefaultId("combat.unitDemoted.ours")
                .addStringTemplate("%location%", loserLocation)
                .addStringTemplate("%oldName%", loserLabel)
                .addStringTemplate("%unit%", loser.getLabel())
                .addStringTemplate("%enemyNation%", winnerNation)
                .addStringTemplate("%enemyUnit%", winner.getLabel()));
    }

    /**
     * Destroy a colony.
     *
     * @param attacker The {@code Unit} that attacked.
     * @param colony The {@code Colony} that was attacked.
     * @param random A pseudo-random number source.
     * @param cs The {@code ChangeSet} to update.
     */
    private void csDestroyColony(Unit attacker, Colony colony, Random random,
                                 ChangeSet cs) {
        Game game = attacker.getGame();
        ServerPlayer attackerPlayer = (ServerPlayer) attacker.getOwner();
        StringTemplate attackerNation = attacker.getApparentOwnerName();
        ServerPlayer colonyPlayer = (ServerPlayer) colony.getOwner();
        StringTemplate colonyNation = colonyPlayer.getNationLabel();
        int plunder = colony.getPlunder(attacker, random);

        // Handle history and messages before colony destruction.
        cs.addHistory(colonyPlayer,
            new HistoryEvent(game.getTurn(),
                HistoryEvent.HistoryEventType.COLONY_DESTROYED, attackerPlayer)
                .addStringTemplate("%nation%", attackerNation)
                .addName("%colony%", colony.getName()));
        cs.addMessage(colonyPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.colonyBurned.ours", colony.getTile())
                .addName("%colony%", colony.getName())
                .addStringTemplate("%enemyNation%", attackerNation)
                .addStringTemplate("%enemyUnit%", attacker.getLabel())
                .addAmount("%amount%", plunder));
        cs.addGlobalMessage(game, colonyPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.colonyBurned.other", colonyPlayer)
                .addName("%colony%", colony.getName())
                .addStringTemplate("%nation%", colonyNation)
                .addStringTemplate("%attackerNation%", attackerNation));
        colonyPlayer.csLoseLocation(colony, cs);

        // Allocate some plunder.
        if (plunder > 0) {
            attackerPlayer.modifyGold(plunder);
            colonyPlayer.modifyGold(-plunder);
            cs.addPartial(See.only(attackerPlayer), attackerPlayer, "gold");
            cs.addPartial(See.only(colonyPlayer), colonyPlayer, "gold");
        }

        // Dispose of the colony and its contents.
        csDisposeSettlement(colony, cs);
    }

    /**
     * Destroys an Indian settlement.
     *
     * @param attacker The attacking {@code Unit}.
     * @param is An {@code IndianSettlement} to destroy.
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csDestroySettlement(Unit attacker, IndianSettlement is,
                                     Random random, ChangeSet cs) {
        final Game game = getGame();
        final Specification spec = game.getSpecification();
        Tile tile = is.getTile();
        ServerPlayer attackerPlayer = (ServerPlayer)attacker.getOwner();
        ServerPlayer nativePlayer = (ServerPlayer)is.getOwner();
        StringTemplate attackerNation = attackerPlayer.getNationLabel();
        StringTemplate nativeNation = nativePlayer.getNationLabel();
        String settlementName = is.getName();
        boolean capital = is.isCapital();
        int plunder = is.getPlunder(attacker, random);

        // Remaining units lose their home.
        for (Unit u : is.getOwnedUnits()) {
            u.setHomeIndianSettlement(null);
            cs.add(See.only(nativePlayer), u);
        }
                
        // Destroy the settlement, update settlement tiles.
        csDisposeSettlement(is, cs);

        // Make the treasure train if there is treasure.
        if (plunder > 0) {
            List<UnitType> unitTypes
                = spec.getUnitTypesWithAbility(Ability.CARRY_TREASURE);
            UnitType type = getRandomMember(logger, "Choose train",
                                            unitTypes, random);
            Unit train = new ServerUnit(game, tile, attackerPlayer,
                                        type);//-vis: safe, attacker on tile
            train.setTreasureAmount(plunder);
        }

        // This is an atrocity.
        int score = spec.getInteger(GameOptions.DESTROY_SETTLEMENT_SCORE);
        HistoryEvent h = new HistoryEvent(game.getTurn(),
            HistoryEvent.HistoryEventType.DESTROY_SETTLEMENT, this)
                .addStringTemplate("%nation%", nativeNation)
                .addName("%settlement%", settlementName);
        h.setScore(score);
        cs.addHistory(attackerPlayer, h);

        // Finish with messages and history.
        cs.addMessage(attackerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.destroySettlement.enemy", attacker)
                .addName("%settlement%", settlementName)
                .addStringTemplate("%unit%", attacker.getLabel())
                .addStringTemplate("%nativeNation%", nativeNation)
                .addAmount("%amount%", plunder));
        if (capital) {
            cs.addMessage(attackerPlayer,
                new ModelMessage(ModelMessage.MessageType.FOREIGN_DIPLOMACY,
                                 "combat.destroySettlement.enemy.capital",
                                 attacker)
                    .addStringTemplate("%nation%", nativeNation));
        }
        if (nativePlayer.checkForDeath() == IS_DEAD) {
            h = new HistoryEvent(game.getTurn(),
                HistoryEvent.HistoryEventType.DESTROY_NATION, this)
                    .addStringTemplate("%nation%", attackerNation)
                    .addStringTemplate("%nativeNation%", nativeNation);
            h.setScore(SCORE_NATION_DESTROYED);
            cs.addGlobalHistory(game, h);
        }
        cs.addAttribute(See.only(attackerPlayer), "sound",
            "sound.event.destroySettlement");
    }

    /**
     * Disposes of a settlement and reassign its tiles.
     *
     * +vis,til: Resolves the whole mess.
     *
     * @param settlement The {@code Settlement} under attack.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csDisposeSettlement(Settlement settlement, ChangeSet cs) {
        logger.finest("Disposing of " + settlement.getName());
        ServerPlayer owner = (ServerPlayer)settlement.getOwner();
        Set<Tile> owned = settlement.getOwnedTiles();
        for (Tile t : owned) t.cacheUnseen();//+til
        Tile centerTile = settlement.getTile();
        ServerPlayer missionaryOwner = null;
        int radius = 0;

        // Get rid of the any missionary first.
        if (settlement instanceof ServerIndianSettlement) {
            ServerIndianSettlement sis = (ServerIndianSettlement)settlement;
            if (sis.hasMissionary()) {
                missionaryOwner = (ServerPlayer)sis.getMissionary().getOwner();
                radius = sis.getMissionaryLineOfSight();
                sis.csKillMissionary(true, cs);
            }
        }
            
        // Get it off the map and off the owners list.
        settlement.exciseSettlement();//-vis(owner),-til
        owner.removeSettlement(settlement);
        if (owner.hasSettlement(settlement)) {
            throw new IllegalStateException("Still has settlement: "
                + settlement);
        }

        // Reassign the tiles owned by the settlement, if possible
        owner.reassignTiles(owned, null);

        See vis = See.perhaps().always(owner);
        if (missionaryOwner != null) vis.except(missionaryOwner);
        cs.add(vis, owned);
        cs.addRemove(vis, centerTile, settlement);//-vis(owner)
        settlement.dispose();
        owner.invalidateCanSeeTiles();//+vis(owner)

        // Former missionary owner knows that the settlement fell.
        if (missionaryOwner != null) {
            List<Tile> surrounding = transform(centerTile.getSurroundingTiles(1, radius),
                                               t -> !owned.contains(t));
            cs.add(See.only(missionaryOwner), owned);
            cs.add(See.only(missionaryOwner), surrounding);
            cs.addRemove(See.only(missionaryOwner), centerTile, settlement);
            missionaryOwner.invalidateCanSeeTiles();//+vis(missionaryOwner)
            for (Tile t : surrounding) t.cacheUnseen(missionaryOwner);
        }

        // Recache, should only show now cleared tiles to former owner.
        for (Tile t : owned) t.cacheUnseen();
        // Center tile is special for native settlements.  Because
        // native settlement tiles are *always* cached, the cache
        // needs to be completely cleared for players that can see the
        // settlement is gone.
        if (settlement instanceof IndianSettlement) centerTile.seeTile();
        if (missionaryOwner != null) centerTile.seeTile(missionaryOwner);
    }

    /**
     * Evade a normal attack.
     *
     * @param attacker The attacker {@code Unit}.
     * @param defender A naval {@code Unit} that evades the attacker.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csEvadeAttack(Unit attacker, Unit defender, ChangeSet cs) {
        ServerPlayer attackerPlayer = (ServerPlayer) attacker.getOwner();
        StringTemplate attackerNation = attacker.getApparentOwnerName();
        Location attackerLocation = attacker.getLocation();
        ServerPlayer defenderPlayer = (ServerPlayer) defender.getOwner();
        StringTemplate defenderNation = defender.getApparentOwnerName();
        Location defenderLocation = defender.getLocation();

        cs.addMessage(attackerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipEvaded.enemy", attacker)
                .addStringTemplate("%location%",
                    attackerLocation.getLocationLabelFor(attackerPlayer))
                .addStringTemplate("%unit%", attacker.getLabel())
                .addStringTemplate("%enemyNation%", defenderNation)
                .addStringTemplate("%enemyUnit%", defender.getLabel()));
        cs.addMessage(defenderPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipEvaded.ours", defender)
                .addStringTemplate("%location%",
                    defenderLocation.getLocationLabelFor(defenderPlayer))
                .addStringTemplate("%unit%", defender.getLabel())
                .addStringTemplate("%enemyNation%", attackerNation)
                .addStringTemplate("%enemyUnit%", attacker.getLabel()));
    }

    /**
     * Evade a bombardment.
     *
     * @param settlement The attacker {@code Settlement}.
     * @param defender A naval {@code Unit} that evades the attacker.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csEvadeBombard(Settlement settlement, Unit defender,
                                ChangeSet cs) {
        ServerPlayer attackerPlayer = (ServerPlayer) settlement.getOwner();
        ServerPlayer defenderPlayer = (ServerPlayer) defender.getOwner();
        StringTemplate defenderNation = defender.getApparentOwnerName();
        Building building = ((Colony)settlement).getStockade();

        cs.addMessage(attackerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipEvadedBombardment.enemy", settlement)
                .addStringTemplate("%location%",
                    settlement.getLocationLabelFor(attackerPlayer))
                .addNamed("%building%", building)
                .addStringTemplate("%enemyNation%", defenderNation)
                .addStringTemplate("%enemyUnit%", defender.getLabel()));
        cs.addMessage(defenderPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipEvadedBombardment.ours", defender)
                .addStringTemplate("%location%",
                    settlement.getLocationLabelFor(defenderPlayer))
                .addStringTemplate("%unit%", defender.getLabel())
                .addNamed("%building%", building)
                .addStringTemplate("%enemyNation%",
                    attackerPlayer.getNationLabel()));
    }

    /**
     * Loot a ship.
     *
     * @param winner The winning naval {@code Unit}.
     * @param loser The losing naval {@code Unit}
     * @param cs A {@code ChangeSet} to update.
     */
    private void csLootShip(Unit winner, Unit loser, ChangeSet cs) {
        ServerPlayer winnerPlayer = (ServerPlayer) winner.getOwner();
        List<Goods> capture = loser.getGoodsList();
        if (!capture.isEmpty() && winner.hasSpaceLeft()) {
            for (Goods g : capture) g.setLocation(null);
            new LootSession(winner, loser, capture);
            cs.add(See.only(winnerPlayer), ChangeSet.ChangePriority.CHANGE_LATE,
                new LootCargoMessage(winner, loser.getId(), capture));
        }
        loser.getGoodsContainer().removeAll();
        loser.setState(Unit.UnitState.ACTIVE);
    }

    /**
     * Unit auto equips but loses equipment.
     *
     * @param attacker The {@code Unit} that attacked.
     * @param defender The {@code Unit} that defended and loses equipment.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csLoseAutoEquip(Unit attacker, Unit defender, ChangeSet cs) {
        ServerPlayer defenderPlayer = (ServerPlayer) defender.getOwner();
        StringTemplate defenderNation = defenderPlayer.getNationLabel();
        Settlement settlement = defender.getSettlement();
        Role role = defender.getAutomaticRole();
        StringTemplate defenderLabel = Messages.getUnitLabel(null,
            defender.getType().getId(), 1, defenderPlayer.getNation().getId(),
            role.getId(), null);
        ServerPlayer attackerPlayer = (ServerPlayer) attacker.getOwner();
        StringTemplate attackerNation = attacker.getApparentOwnerName();

        // Autoequipment is not actually with the unit, it is stored
        // in the settlement of the unit.  Remove it from there.
        for (AbstractGoods ag : role.getRequiredGoodsList()) {
            settlement.removeGoods(ag);
        }

        // No special message, attacker can not distinguish auto-armed
        // from actual-armed.
        cs.addMessage(attackerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.unitDemotedToUnarmed.enemy", attacker)
                .addStringTemplate("%location%",
                     settlement.getLocationLabelFor(attackerPlayer))
                .addStringTemplate("%unit%", attacker.getLabel())
                .addStringTemplate("%oldName%", defenderLabel)
                .addStringTemplate("%enemyNation%", defenderNation)
                .addStringTemplate("%enemyUnit%", defender.getLabel()));
        cs.addMessage(defenderPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.unitLoseAutoEquip", defender)
                .addStringTemplate("%location%",
                    settlement.getLocationLabelFor(defenderPlayer))
                .addStringTemplate("%unit%", defender.getLabel())
                .addStringTemplate("%enemyNation%", attackerNation)
                .addStringTemplate("%enemyUnit%", attacker.getLabel()));
    }

    /**
     * Unit drops some equipment.
     *
     * @param winner The {@code Unit} that won.
     * @param loser The {@code Unit} that lost and loses equipment.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csLoseEquip(Unit winner, Unit loser, ChangeSet cs) {
        final Specification spec = getSpecification();
        ServerPlayer loserPlayer = (ServerPlayer) loser.getOwner();
        StringTemplate loserNation = loserPlayer.getNationLabel();
        StringTemplate loserLocation = loser.getLocation()
            .getLocationLabelFor(loserPlayer);
        StringTemplate loserLabel = loser.getLabel();
        ServerPlayer winnerPlayer = (ServerPlayer) winner.getOwner();
        StringTemplate winnerNation = winner.getApparentOwnerName();
        StringTemplate winnerLocation = winner.getLocation()
            .getLocationLabelFor(winnerPlayer);
        Role role = loser.getRole();
        String key;

        Role downgrade = role.getDowngrade();
        if (downgrade != null) {
            loser.changeRole(downgrade, 1);
        } else {
            loser.changeRole(spec.getDefaultRole(), 0);
        }

        // Account for possible loss of mobility due to horses going away.
        loser.setMovesLeft(Math.min(loser.getMovesLeft(),
                                    loser.getInitialMovesLeft()));

        if (!loser.isArmed()) {
            cs.addMessage(winnerPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 "combat.unitDemotedToUnarmed.enemy", winner)
                    .addStringTemplate("%location%", winnerLocation)
                    .addStringTemplate("%unit%", winner.getLabel())
                    .addStringTemplate("%oldName%", loserLabel)
                    .addStringTemplate("%enemyNation%", loserNation)
                    .addStringTemplate("%enemyUnit%", loser.getLabel()));
            cs.addMessage(loserPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 "combat.unitDemotedToUnarmed.ours", loser)
                    .addStringTemplate("%location%", loserLocation)
                    .addStringTemplate("%oldName%", loserLabel)
                    .addStringTemplate("%unit%", loser.getLabel())
                    .addStringTemplate("%enemyNation%", winnerNation)
                    .addStringTemplate("%enemyUnit%", winner.getLabel()));
            loser.setState(Unit.UnitState.ACTIVE);
        } else {
            key = "combat.unitDemoted.enemy." + loser.getType().getSuffix();
            cs.addMessage(winnerPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 key, winner)
                    .addDefaultId("combat.unitDemoted.enemy")
                    .addStringTemplate("%location%", winnerLocation)
                    .addStringTemplate("%unit%", winner.getLabel())
                    .addStringTemplate("%oldName%", loserLabel)
                    .addStringTemplate("%enemyNation%", loserNation)
                    .addStringTemplate("%enemyUnit%", loser.getLabel()));
            key = "combat.unitDemoted.ours." + loser.getType().getSuffix();
            cs.addMessage(loserPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 key, loser)
                    .addDefaultId("combat.unitDemoted.ours")
                    .addStringTemplate("%location%", loserLocation)
                    .addStringTemplate("%oldName%", loserLabel)
                    .addStringTemplate("%unit%", loser.getLabel())
                    .addStringTemplate("%enemyNation%", winnerNation)
                    .addStringTemplate("%enemyUnit%", winner.getLabel()));
        }
    }

    /**
     * Hook for when a player loses access to a location for whatever
     * reason.  Useful for disabling trade routes.
     *
     * @param loc The {@code Location} that was lost.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csLoseLocation(Location loc, ChangeSet cs) {
        for (TradeRoute tr : transform(getTradeRoutes(),
                                       r -> r.removeMatchingStops(loc))) {
            for (Unit u : tr.getAssignedUnits()) {
                u.setTradeRoute(null);
                cs.add(See.only(this), u);
            }
            cs.addMessage(this,
                new ModelMessage(ModelMessage.MessageType.GOODS_MOVEMENT,
                    "combat.tradeRouteSuspended", this)
                    .addName("%route%", tr.getName())
                    .addStringTemplate("%stop%", loc.getLocationLabel()));
            cs.add(See.only(this), tr);
        }
    }

    /**
     * Damage a building or a ship or steal some goods or gold.
     *
     * @param attacker The attacking {@code Unit}.
     * @param colony The {@code Colony} to pillage.
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csPillageColony(Unit attacker, Colony colony,
                                 Random random, ChangeSet cs) {
        ServerPlayer attackerPlayer = (ServerPlayer) attacker.getOwner();
        StringTemplate attackerNation = attacker.getApparentOwnerName();
        ServerPlayer colonyPlayer = (ServerPlayer) colony.getOwner();
        StringTemplate colonyNation = colonyPlayer.getNationLabel();

        // Collect the damagable buildings, ships, movable goods.
        List<Building> buildingList = colony.getBurnableBuildings();
        List<Unit> shipList = colony.getTile().getNavalUnits();
        List<Goods> goodsList = colony.getLootableGoodsList();

        // Pick one, with one extra choice for stealing gold.
        int pillage = randomInt(logger, "Pillage choice", random,
            buildingList.size() + shipList.size() + goodsList.size()
            + ((colony.canBePlundered()) ? 1 : 0));
        if (pillage < buildingList.size()) {
            Building building = buildingList.get(pillage);
            csDamageBuilding(building, cs);
            cs.addMessage(colonyPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 "combat.raid.building", colony)
                    .addName("%colony%", colony.getName())
                    .addNamed("%building%", building)
                    .addStringTemplate("%enemyNation%", attackerNation)
                    .addStringTemplate("%enemyUnit%", attacker.getLabel()));
        } else if (pillage < buildingList.size() + shipList.size()) {
            Unit ship = shipList.get(pillage - buildingList.size());
            if (ship.getRepairLocation() == null) {
                csSinkShipAttack(attacker, ship, cs);
            } else {
                csDamageShipAttack(attacker, ship, cs);
            }
        } else if (pillage < buildingList.size() + shipList.size()
                   + goodsList.size()) {
            Goods goods = goodsList.get(pillage - buildingList.size()
                - shipList.size());
            goods.setAmount(Math.min(goods.getAmount() / 2, 50));
            colony.removeGoods(goods);
            if (attacker.canAdd(goods)) attacker.add(goods);
            cs.addMessage(colonyPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 "combat.raid.goods", colony, goods)
                    .addName("%colony%", colony.getName())
                    .addAmount("%amount%", goods.getAmount())
                    .addNamed("%goods%", goods.getType())
                    .addStringTemplate("%enemyNation%", attackerNation)
                    .addStringTemplate("%enemyUnit%", attacker.getLabel()));

        } else {
            int plunder = Math.max(1, colony.getPlunder(attacker, random) / 5);
            colonyPlayer.modifyGold(-plunder);
            attackerPlayer.modifyGold(plunder);
            cs.addPartial(See.only(colonyPlayer), colonyPlayer, "gold");
            cs.addMessage(colonyPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 "combat.raid.plunder", colony)
                    .addAmount("%amount%", plunder)
                    .addName("%colony%", colony.getName())
                    .addStringTemplate("%enemyNation%", attackerNation)
                    .addStringTemplate("%enemyUnit%", attacker.getLabel()));
        }
        cs.addGlobalMessage(getGame(), colonyPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.raid.other", colonyPlayer)
                .addName("%colony%", colony.getName())
                .addStringTemplate("%colonyNation%", colonyNation)
                .addStringTemplate("%nation%", attackerNation));
    }

    /**
     * Damage a building in a colony by downgrading it if possible and
     * destroying it otherwise.
     *
     * This is called as a result of pillaging, which always updates
     * the colony tile.
     *
     * @param building The {@code Building} to damage.
     * @param cs a {@code ChangeSet} value
     */
    private void csDamageBuilding(Building building, ChangeSet cs) {
        ServerColony colony = (ServerColony)building.getColony();
        Tile copied = colony.getTile().getTileToCache();
        boolean changed = false;
        BuildingType type = building.getType();
        if (type.getUpgradesFrom() == null) {
            changed = colony.ejectUnits(building, building.getUnitList());//-til
            colony.destroyBuilding(building);//-til
            changed |= building.getType().isDefenceType();
            cs.addRemove(See.only((ServerPlayer)colony.getOwner()), colony, 
                         building);//-vis: safe, buildings are ok
            building.dispose();
            // Have any abilities been removed that gate other production,
            // e.g. removing docks should shut down fishing.
            for (WorkLocation wl : transform(colony.getAllWorkLocations(),
                                             w -> !w.isEmpty() && !w.canBeWorked())) {
                changed |= colony.ejectUnits(wl, wl.getUnitList());//-til
                logger.info("Units ejected from workLocation "
                    + wl.getId() + " on loss of "
                    + building.getType().getSuffix());
            }
        } else if (building.canBeDamaged()) {
            changed = colony.ejectUnits(building, building.downgrade());//-til
            changed |= building.getType().isDefenceType();
        } else {
            return;
        }
        if (changed) colony.getTile().cacheUnseen(copied);//+til
        if (isAI()) {
            colony.firePropertyChange(Colony.REARRANGE_COLONY, true, false);
        }
    }


    /**
     * Promotes a unit.
     *
     * @param winner The {@code Unit} that won and should be promoted.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csPromoteUnit(Unit winner, ChangeSet cs) {
        ServerPlayer winnerPlayer = (ServerPlayer) winner.getOwner();
        StringTemplate winnerLabel = winner.getLabel();

        UnitChange uc = winner.getUnitChange(UnitChangeType.PROMOTION);
        if (uc == null || uc.to == winner.getType()) {
            logger.warning("Promotion failed, type="
                + ((uc == null) ? "null" : "same type: " + uc.to));
            return;
        }
        winner.changeType(uc.to);//-vis(winnerPlayer)
        winnerPlayer.invalidateCanSeeTiles();//+vis(winnerPlayer)

        cs.addMessage(winnerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.unitPromoted", winner)
                .addStringTemplate("%oldName%", winnerLabel)
                .addStringTemplate("%unit%", winner.getLabel()));
    }

    /**
     * Sinks all ships in a colony.
     *
     * @param attacker The attacker {@code Unit}.
     * @param colony The {@code Colony} to sink ships in.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csSinkColonyShips(Unit attacker, Colony colony, ChangeSet cs) {
        boolean captureRepairing = getSpecification()
            .getBoolean(GameOptions.CAPTURE_UNITS_UNDER_REPAIR);
        List<Unit> units = transform(colony.getTile().getUnits(),
            u -> u.isNaval() && !(captureRepairing && u.isDamaged()));
        if (!units.isEmpty()) {
            final ServerPlayer shipPlayer = (ServerPlayer)colony.getOwner();
            final ServerPlayer attackerPlayer = (ServerPlayer)attacker.getOwner();
            StringTemplate t = StringTemplate.label(", ");
            for (Unit u : units) {
                csSinkShip(u, attackerPlayer, cs);
                t.addStringTemplate(u.getLabel());
            }
            cs.addMessage(shipPlayer,
                new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                                 "combat.shipsSunk", shipPlayer)
                    .addStringTemplate("%ships%", t)
                    .addAmount("%number%", units.size()));
        }
    }

    /**
     * Sinks this ship as result of a normal attack.
     *
     * @param attacker The attacker {@code Unit}.
     * @param ship The naval {@code Unit} to sink.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csSinkShipAttack(Unit attacker, Unit ship, ChangeSet cs) {
        ServerPlayer shipPlayer = (ServerPlayer) ship.getOwner();
        StringTemplate shipNation = ship.getApparentOwnerName();
        Location shipLocation = ship.getLocation();
        Unit attackerUnit = attacker;
        ServerPlayer attackerPlayer = (ServerPlayer) attackerUnit.getOwner();
        StringTemplate attackerNation = attackerUnit.getApparentOwnerName();

        cs.addMessage(attackerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipSunk.enemy", attackerUnit)
                .addStringTemplate("%location%",
                    shipLocation.getLocationLabelFor(attackerPlayer))
                .addStringTemplate("%unit%", attackerUnit.getLabel())
                .addStringTemplate("%enemyUnit%", ship.getLabel())
                .addStringTemplate("%enemyNation%", shipNation));
        cs.addMessage(shipPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipSunk.ours", ship.getTile())
                .addStringTemplate("%location%",
                    shipLocation.getLocationLabelFor(shipPlayer))
                .addStringTemplate("%unit%", ship.getLabel())
                .addStringTemplate("%enemyUnit%", attackerUnit.getLabel())
                .addStringTemplate("%enemyNation%", attackerNation));

        csSinkShip(ship, attackerPlayer, cs);
    }

    /**
     * Sinks this ship as result of a bombard.
     *
     * @param settlement The bombarding {@code Settlement}.
     * @param ship The naval {@code Unit} to sink.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csSinkShipBombard(Settlement settlement, Unit ship,
                                   ChangeSet cs) {
        ServerPlayer attackerPlayer = (ServerPlayer) settlement.getOwner();
        ServerPlayer shipPlayer = (ServerPlayer) ship.getOwner();
        StringTemplate shipNation = ship.getApparentOwnerName();
        Building building = ((Colony)settlement).getStockade();
        
        cs.addMessage(attackerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipSunkByBombardment.enemy", settlement)
                .addStringTemplate("%location%",
                    settlement.getLocationLabelFor(attackerPlayer))
                .addNamed("%building%", building)
                .addStringTemplate("%enemyUnit%", ship.getLabel())
                .addStringTemplate("%enemyNation%", shipNation));
        cs.addMessage(shipPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             "combat.shipSunkByBombardment", ship.getTile())
                .addStringTemplate("%location%",
                    settlement.getLocationLabelFor(shipPlayer))
                .addStringTemplate("%unit%", ship.getLabel())
                .addNamed("%building%", building)
                .addStringTemplate("%enemyNation%",
                    attackerPlayer.getNationLabel()));

        csSinkShip(ship, attackerPlayer, cs);
    }

    /**
     * Sink the ship.
     *
     * @param ship The naval {@code Unit} to sink.
     * @param attackerPlayer The {@code ServerPlayer} that
     * attacked, or null
     * @param cs A {@code ChangeSet} to update.
     */
    private void csSinkShip(Unit ship, ServerPlayer attackerPlayer,
                            ChangeSet cs) {
        ServerPlayer shipPlayer = (ServerPlayer) ship.getOwner();
        cs.addRemove(See.perhaps().always(shipPlayer), ship.getLocation(),
                     ship);//-vis(shipPlayer)
        ship.dispose();
        shipPlayer.invalidateCanSeeTiles();//+vis(shipPlayer)
        if (attackerPlayer != null) {
            cs.addAttribute(See.only(attackerPlayer), "sound",
                            "sound.event.shipSunk");
        }
    }

    /**
     * Slaughter a unit.
     *
     * @param winner The {@code Unit} that is slaughtering.
     * @param loser The {@code Unit} to slaughter.
     * @param cs A {@code ChangeSet} to update.
     */
    private void csSlaughterUnit(Unit winner, Unit loser, ChangeSet cs) {
        ServerPlayer winnerPlayer = (ServerPlayer) winner.getOwner();
        StringTemplate winnerNation = winner.getApparentOwnerName();
        Location winnerLoc = (winner.isInColony()) ? winner.getColony()
            : winner.getLocation();
        StringTemplate winnerLocation
            = winnerLoc.getLocationLabelFor(winnerPlayer);
        ServerPlayer loserPlayer = (ServerPlayer) loser.getOwner();
        StringTemplate loserNation = loser.getApparentOwnerName();
        Location loserLoc = (loser.isInColony()) ? loser.getColony()
            : loser.getLocation();
        StringTemplate loserLocation
            = loserLoc.getLocationLabelFor(loserPlayer);
        String key;

        key = "combat.unitSlaughtered.enemy." + loser.getType().getSuffix();
        cs.addMessage(winnerPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             key, winner)
                .addDefaultId("combat.unitSlaughtered.enemy")
                .addStringTemplate("%location%", winnerLocation)
                .addStringTemplate("%unit%", winner.getLabel())
                .addStringTemplate("%enemyNation%", loserNation)
                .addStringTemplate("%enemyUnit%", loser.getLabel()));
        key = "combat.unitSlaughtered.ours." + loser.getType().getSuffix();
        cs.addMessage(loserPlayer,
            new ModelMessage(ModelMessage.MessageType.COMBAT_RESULT,
                             key, loser.getTile())
                .addDefaultId("combat.unitSlaughtered.ours")
                .addStringTemplate("%location%", loserLocation)
                .addStringTemplate("%unit%", loser.getLabel())
                .addStringTemplate("%enemyNation%", winnerNation)
                .addStringTemplate("%enemyUnit%", winner.getLabel()));
        if (loserPlayer.isIndian() && loserPlayer.checkForDeath() == IS_DEAD) {
            StringTemplate nativeNation = loserPlayer.getNationLabel();
            cs.addGlobalHistory(getGame(),
                new HistoryEvent(getGame().getTurn(),
                    HistoryEvent.HistoryEventType.DESTROY_NATION, winnerPlayer)
                .addStringTemplate("%nation%", winnerPlayer.getNationLabel())
                .addStringTemplate("%nativeNation%", nativeNation));
        }

        // Destroy unit.  Note See.only visibility used to handle the
        // case that the unit is the last at a settlement.  If
        // See.perhaps was used there, the settlement will be gone
        // when perhaps() is processed, which would erroneously make
        // the unit visible.
        cs.addRemove((loserLoc.getSettlement() != null)
            ? See.only(loserPlayer)
            : See.perhaps().always(loserPlayer),
            loserLoc, loser);//-vis(loserPlayer)
        loser.dispose();
        loserPlayer.invalidateCanSeeTiles();//+vis(loserPlayer)
    }

    /**
     * Updates the player view for each new tile on a supplied list,
     * and update a ChangeSet as well.
     *
     * @param newTiles A list of {@code Tile}s to update.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csSeeNewTiles(Collection<? extends Tile> newTiles,
                              ChangeSet cs) {
        exploreTiles(newTiles);
        cs.add(See.only(this), newTiles);
    }

    /**
     * Make a tea party modifier for the current turn.
     *
     * Public for the test suite.
     *
     * @return A tea party {@code Modifier}.
     */
    public Modifier makeTeaPartyModifier() {
        final Specification spec = getGame().getSpecification();
        final Turn turn = getGame().getTurn();
        Modifier modifier = first(spec.getModifiers(Modifier.COLONY_GOODS_PARTY));
        if (modifier != null) {
            modifier = Modifier.makeTimedModifier("model.goods.bells",
                                                  modifier, turn);
            modifier.setModifierIndex(Modifier.PARTY_PRODUCTION_INDEX);
        }
        return modifier;
    }

    /**
     * Raises the players tax rate, or handles a goods party.
     *
     * @param tax The new tax rate.
     * @param goods The {@code Goods} to use in a goods party.
     * @param accepted Whether the tax raise was accepted.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csRaiseTax(int tax, Goods goods, boolean accepted,
                           ChangeSet cs) {
        GoodsType goodsType = goods.getType();
        Colony colony = (Colony) goods.getLocation();
        int amount = Math.min(goods.getAmount(), GoodsContainer.CARGO_SIZE);
        String monarchKey = getMonarchKey();

        if (accepted) {
            csSetTax(tax, cs);
            logger.info("Accepted tax raise to: " + tax);
        } else if (colony.getGoodsCount(goodsType) < amount) {
            // Player has removed the goods from the colony,
            // so raise the tax anyway.
            final int extraTax = 3; // FIXME, magic number
            csSetTax(tax + extraTax, cs);
            cs.add(See.only(this), ChangePriority.CHANGE_NORMAL,
                new MonarchActionMessage(Monarch.MonarchAction.FORCE_TAX,
                    StringTemplate.template(Monarch.MonarchAction.FORCE_TAX.getTextKey())
                    .addAmount("%amount%", tax + extraTax),
                    monarchKey));
            logger.info("Forced tax raise to: " + (tax + extraTax));
        } else { // Tea party
            Specification spec = getGame().getSpecification();
            colony.getGoodsContainer().saveState();
            colony.removeGoods(goodsType, amount);

            int arrears = market.getPaidForSale(goodsType)
                * spec.getInteger(GameOptions.ARREARS_FACTOR);
            Market market = getMarket();
            market.setArrears(goodsType, arrears);

            Modifier tpm = makeTeaPartyModifier();
            cs.addModifier(this, colony, tpm, true);
            cs.add(See.only(this), colony.getGoodsContainer());
            cs.add(See.only(this), market.getMarketData(goodsType));
            
            String messageId = "model.player.colonyGoodsParty."
                + goodsType.getSuffix();
            if (!Messages.containsKey(messageId)) {
                messageId = (colony.isLandLocked())
                    ? "model.player.colonyGoodsParty.landLocked"
                    : "model.player.colonyGoodsParty.harbour";
            }
            cs.addMessage(this,
                new ModelMessage(ModelMessage.MessageType.FOREIGN_DIPLOMACY,
                                 messageId, this)
                    .addName("%colony%", colony.getName())
                    .addAmount("%amount%", amount)
                    .addNamed("%goods%", goodsType));
            cs.addAttribute(See.only(this), "flush", Boolean.TRUE.toString());
            logger.info("Goods party at " + colony.getName()
                + " with: " + goods + " arrears: " + arrears);
            if (isAI()) { // Reset the goods wishes
                colony.firePropertyChange(Colony.REARRANGE_COLONY,
                                          goodsType, null);
            }
        }
    }

    /**
     * Handle the end of a session where the player has ignored a tax
     * increase demand.
     *
     * @param tax The new tax rate.
     * @param goods The {@code Goods} to use in a goods party.
     * @param cs A {@code ChangeSet} to update.
     */
    public void ignoreTax(int tax, Goods goods, ChangeSet cs) {
        csRaiseTax(tax, goods, true, cs);
        cs.addMessage(this,
            new ModelMessage(ModelMessage.MessageType.FOREIGN_DIPLOMACY,
                             "model.player.ignoredTax", this)
                .addAmount("%amount%", tax));
    }

    /**
     * Handle the end of a session where the player has ignored an
     * offer of mercenaries.
     *
     * @param cs A {@code ChangeSet} to update.
     */
    public void ignoreMercenaries(ChangeSet cs) {
        cs.addMessage(this,
            new ModelMessage(ModelMessage.MessageType.FOREIGN_DIPLOMACY,
                             "model.player.ignoredMercenaries", this));
    }

    /**
     * Add a mercenary offer to the player.
     *
     * @param mercenaries A list of mercenary units.
     * @param action The monarch action that caused the offer.
     * @param random A pseudo-random number source.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csMercenaries(List<AbstractUnit> mercenaries,
                              Monarch.MonarchAction action,
                              Random random, ChangeSet cs) {
        if (mercenaries.isEmpty()) return;
        final int n = NameCache.getMercenaryLeaderIndex(random);
        final int price = priceMercenaries(mercenaries);
        cs.add(See.only(this), ChangePriority.CHANGE_EARLY,
            new MonarchActionMessage(action, StringTemplate
                .template(action.getTextKey())
                .addName("%leader%", NameCache.getMercenaryLeaderName(n))
                .addAmount("%gold%", price)
                .addStringTemplate("%mercenaries%",
                    AbstractUnit.getListLabel(", ", mercenaries)),
                "image.flavor.model.mercenaries." + n));
        new MonarchSession(this, action, mercenaries, price);
    }
        
    /**
     * Set the player tax rate.
     * If this requires a change to the bells bonuses, we have to update
     * the whole player (bah) because we can not yet independently update
     * the feature container.
     *
     * @param tax The new tax rate.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csSetTax(int tax, ChangeSet cs) {
        setTax(tax);
        if (recalculateBellsBonus()) {
            cs.add(See.only(this), this);
        } else {
            cs.addPartial(See.only(this), this, "tax");
        }
    }

    /**
     * Adds mercenaries that the player has accepted.
     *
     * @param mercs A list of mercenaries.
     * @param price The price to be charged for them.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csAddMercenaries(List<AbstractUnit> mercs, int price,
                                 ChangeSet cs) {
        if (checkGold(price)) {
            final Specification spec = getSpecification();
            List<AbstractUnit> naval
                = transform(mercs, au -> au.getType(spec).isNaval());
            Tile dst;
            if (naval.isEmpty()) { // Deliver to first settlement
                dst = first(getColonies()).getTile();
                createUnits(mercs, dst);//-vis: safe, in colony
                cs.add(See.only(this), dst);
            } else { // Let them sail in
                dst = getEntryLocation().getTile();
                loadShips(createUnits(mercs, dst));//-vis
                invalidateCanSeeTiles();//+vis(this)
                cs.add(See.perhaps(), dst);
            }
            cs.addMessage(this,
                new ModelMessage(ModelMessage.MessageType.FOREIGN_DIPLOMACY,
                                 "model.player.mercenariesArrived", this)
                    .addStringTemplate("%location%",
                        dst.up().getLocationLabelFor(this)));
            modifyGold(-price);
            cs.addPartial(See.only(this), this, "gold");
        } else {
            getMonarch().setDispleasure(true);
            cs.add(See.only(this), ChangePriority.CHANGE_NORMAL,
                new MonarchActionMessage(Monarch.MonarchAction.DISPLEASURE,
                    StringTemplate.template(Monarch.MonarchAction.DISPLEASURE.getTextKey()),
                    getMonarchKey()));
        }
    }

    /**
     * Make contact between two nations if necessary.
     *
     * @param other The other {@code ServerPlayer}.
     * @param cs A {@code ChangeSet} to update.
     * @return True if this was a first contact.
     */
    public boolean csContact(ServerPlayer other, ChangeSet cs) {
        if (hasContacted(other)) return false;

        // Must be a first contact!
        final Game game = getGame();
        Turn turn = game.getTurn();
        if (isIndian()) {
            if (other.isIndian()) {
                return false; // Ignore native-to-native contacts.
            } else {
                cs.addHistory(other, new HistoryEvent(turn,
                        HistoryEvent.HistoryEventType.MEET_NATION, other)
                    .addStringTemplate("%nation%", getNationLabel()));
            }
        } else { // (serverPlayer.isEuropean)
            cs.addHistory(this, new HistoryEvent(turn,
                    HistoryEvent.HistoryEventType.MEET_NATION, other)
                .addStringTemplate("%nation%", other.getNationLabel()));
        }

        logger.finest("First contact between " + this.getId()
            + " and " + other.getId());
        return true;
    }

    /**
     * Initiate first contact between this European and native player.
     *
     * @param other The native {@code ServerPlayer}.
     * @param tile The {@code Tile} contact is made at if this is
     *     a first landing in the new world and it is owned by the
     *     other player.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csNativeFirstContact(ServerPlayer other, Tile tile,
                                     ChangeSet cs) {
        cs.add(See.only(this), ChangePriority.CHANGE_EARLY,
            new FirstContactMessage(this, other, tile));
        csChangeStance(Stance.PEACE, other, true, cs);
        if (tile != null) {
            // Establish a diplomacy session so that if the player
            // accepts the tile offer, we can verify that the offer
            // was made.
            new DiplomacySession(tile.getFirstUnit(),
                                 tile.getOwningSettlement());
        }
    }

    /**
     * Initiate first contact between this European and another
     * European player.
     *
     * @param unit The {@code Unit} making contact.
     * @param settlement The {@code Settlement} being contacted.
     * @param otherUnit The {@code Unit} being contacted.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csEuropeanFirstContact(Unit unit, Settlement settlement,
                                       Unit otherUnit, ChangeSet cs) {
        final Game game = getGame();

        ServerPlayer other;
        if (settlement instanceof Colony) {
            other = (ServerPlayer)settlement.getOwner();
        } else if (otherUnit != null) {
            other = (ServerPlayer)otherUnit.getOwner();
        } else {
            throw new IllegalArgumentException("Non-null settlement or "
                    + "otherUnit required.");
        }
        
        DiplomaticTrade agreement = new DiplomaticTrade(game,
            DiplomaticTrade.TradeContext.CONTACT, this, other, null, 0);
        agreement.add(new StanceTradeItem(game, this, other, Stance.PEACE));
        DiplomacySession session = (settlement == null)
            ? new DiplomacySession(unit, otherUnit)
            : new DiplomacySession(unit, settlement);
        session.setAgreement(agreement);
        cs.add(See.only(this), ChangePriority.CHANGE_LATE, (settlement == null)
            ? new DiplomacyMessage(unit, otherUnit, agreement)
            : new DiplomacyMessage(unit, (Colony)settlement, agreement));
    }

    /**
     * Change the owner of a unit or dispose of it if the change is
     * impossible.  Move the unit to a new location if necessary.
     * Also handle disappearance of any carried units that will now be
     * invisible to the new owner.
     *
     * -vis(owner,newOwner)
     *
     * @param unit The {@code Unit} to change ownership of.
     * @param newOwner The new owning {@code ServerPlayer}.
     * @param change An optional accompanying change type.
     * @param loc A optional new {@code Location} for the unit.
     * @param cs A {@code ChangeSet} to update.
     * @return True if the new owner can have this unit.
     */
    public boolean csChangeOwner(Unit unit, ServerPlayer newOwner,
                                 String change, Location loc,
                                 ChangeSet cs) {
        if (newOwner == this) return true; // No transfer needed

        final Specification spec = getSpecification();
        final Tile oldTile = unit.getTile();
        if (change != null) {
            UnitType mainType = unit.getType();
            UnitChange uc;
            if ((uc = unit.getUnitChange(change, null, newOwner)) == null) {
                ; // mainType is unchanged
            } else if (uc.isAvailableTo(newOwner)) {
                mainType = uc.to;
            } else { // Can not have this unit.
                logger.warning("Change type/owner failed for " + unit
                    + " -> " + newOwner + "(" + change + "/" +  uc + ")");
                cs.addRemove(See.perhaps().always(this), oldTile, unit);
                unit.dispose();
                return false;
            }

            for (Unit u : unit.getUnitList()) {
                if ((uc = u.getUnitChange(change, null, newOwner)) == null) {
                    ; // no change for this passenger
                } else if (uc.isAvailableTo(newOwner)) {
                    if (uc.to != u.getType() && !u.changeType(uc.to)) {
                        logger.warning("Type change failure: " + u
                            + " -> " + uc.to);
                    }
                } else {
                    logger.warning("Change type/owner failed for cargo " + u
                        + " -> " + newOwner + "(" + change + "/" + uc + ")");
                    cs.addRemove(See.only(this), unit, u);
                    u.dispose();
                }
            }

            if (mainType != unit.getType() && !unit.changeType(mainType)) {
                logger.warning("Type change failure: " + unit
                    + " -> " + mainType);
                return false;
            }
        }
        unit.changeOwner(newOwner);
        if (loc != null) unit.setLocation(loc);
        if (unit.isCarrier()) {
            cs.addRemoves(See.only(this), unit, unit.getUnitList());
        }
        cs.add(See.only(newOwner), newOwner.exploreForUnit(unit));
        return true;
    }


    /**
     * Accept the native demand at a players colony.
     *
     * @param demandPlayer The {@code ServerPlayer} that is demanding.
     * @param unit The {@code Unit} that demanded.
     * @param colony The {@code Colony} that was demanded of.
     * @param type The {@code GoodsType} demanded, or null for gold.
     * @param amount The amount demanded.
     * @param result The result of the demand.
     * @param cs A {@code ChangeSet} to update.
     */
    public void csCompleteNativeDemand(ServerPlayer demandPlayer,
                                       Unit unit, Colony colony,
                                       GoodsType type, int amount,
                                       boolean result, ChangeSet cs) {
        // Always inform the demander of the result.
        cs.add(See.only(demandPlayer), ChangePriority.CHANGE_NORMAL,
                   new IndianDemandMessage(unit, colony, type, amount)
                       .setResult(result));

        if (result) {
            if (type == null) {
                this.modifyGold(-amount);
                demandPlayer.modifyGold(amount);
                cs.addPartial(See.only(this), this, "gold");
                cs.addPartial(See.only(demandPlayer), demandPlayer, "gold");
            } else {
                GoodsContainer colonyContainer = colony.getGoodsContainer(),
                    unitContainer = unit.getGoodsContainer();
                GoodsContainer.moveGoods(colonyContainer, type, amount,
                                         unitContainer);
                cs.add(See.only(this), colonyContainer);
                cs.add(See.only(demandPlayer), unitContainer);
            }
            
            // FIXME: One day the AI should decide what it does with tension
            int difficulty = getSpecification()
                .getInteger(GameOptions.NATIVE_DEMANDS);
            int tension = -(5 - difficulty) * 50;
            ServerIndianSettlement sis = (ServerIndianSettlement)
                unit.getHomeIndianSettlement();
            if (sis == null) {
                demandPlayer.csModifyTension(this, tension, cs);
            } else {
                sis.csModifyAlarm(this, tension, true, cs);
            }
        }
    }


    // Implement ServerModelObject

    /**
     * New turn for this player.
     *
     * @param random A {@code Random} number source.
     * @param lb A {@code LogBuilder} to log to.
     * @param cs A {@code ChangeSet} to update.
     */
    @Override
    public void csNewTurn(Random random, LogBuilder lb, ChangeSet cs) {
        lb.add("PLAYER ", getName(), ": ");
        final Game game = getGame();

        // Settlements
        List<Settlement> settlements = getSettlementList();
        int newSoL = 0, newImmigration = getImmigration();
        for (Settlement settlement : settlements) {
            ((ServerModelObject)settlement).csNewTurn(random, lb, cs);
            newSoL += settlement.getSoL();
        }
        newImmigration = getImmigration() - newImmigration;

        int numberOfColonies = settlements.size();
        if (numberOfColonies > 0) {
            newSoL = newSoL / numberOfColonies;
            if (oldSoL / 10 != newSoL / 10) {
                String key = (newSoL > oldSoL)
                    ? "model.player.soLIncrease"
                    : "model.player.soLDecrease";
                cs.addMessage(this,
                    new ModelMessage(MessageType.SONS_OF_LIBERTY, key, this)
                        .addAmount("%oldSoL%", oldSoL)
                        .addAmount("%newSoL%", newSoL));
            }
            oldSoL = newSoL; // Remember SoL for check changes at next turn.
        }

        // Europe.
        if (europe != null) {
            ((ServerModelObject) europe).csNewTurn(random, lb, cs);
            modifyImmigration(europe.getImmigration(newImmigration));
        }
        // Units.
        for (Unit unit : getUnitList()) {
            try {
                ((ServerModelObject) unit).csNewTurn(random, lb, cs);
            } catch (ClassCastException e) {
                logger.log(Level.SEVERE, "Not a ServerUnit: " + unit.getId(), e);
            }
        }

        if (isEuropean()) { // Update liberty and immigration
            // Auto-emigrate if selection not allowed.
            if (hasAbility(Ability.SELECT_RECRUIT)) {
                cs.addPartial(See.only(this), this, "immigration");
            } else {
                while (checkEmigrate()) {
                    csEmigrate(MigrationType.getUnspecificSlot(),
                               MigrationType.NORMAL, random, cs);
                }
            }
            cs.addPartial(See.only(this), this, "liberty");

            if (getSpecification().getBoolean(GameOptions.ENABLE_UPKEEP)) {
                csPayUpkeep(random, cs);
            }

            int probability = getSpecification().getInteger(GameOptions.NATURAL_DISASTERS);
            if (probability > 0) {
                csNaturalDisasters(random, cs, probability);
            }

            if (isRebel() && interventionBells
                >= getSpecification().getInteger(GameOptions.INTERVENTION_BELLS)) {
                interventionBells = Integer.MIN_VALUE;
                
                // Enter near a port.
                List<Colony> ports = getPorts();
                Colony port = getRandomMember(logger, "Intervention port",
                                              ports, random);
                Tile portTile = port.getTile();
                Tile entry = game.getMap().searchCircle(portTile,
                    GoalDeciders.getSimpleHighSeasGoalDecider(),
                    portTile.getHighSeasCount()+1).getSafeTile(this, random);
                
                // Create the force.
                // @compat 0.10.5
                // We used to nullify the monarch when declaring independence.
                // There are saved games out there where this happened
                // (see BR#2435).  Defend against NPE.
                Force ivf = null;
                if (getMonarch() != null
                // end @compat 0.10.5
                    && (ivf = getMonarch().getInterventionForce()) != null) {
                    List<Unit> land = createUnits(ivf.getLandUnitsList(),
                                                  entry);//-vis(this)
                    List<Unit> naval = createUnits(ivf.getNavalUnitsList(),
                                                   entry);//-vis(this)
                    List<Unit> leftOver = loadShips(land, naval, random);//-vis(this)
                    for (Unit unit : leftOver) {
                        // no use for left over units
                        logger.warning("Disposing of left over unit " + unit);
                        unit.setLocationNoUpdate(null);//-vis: safe, off map
                        unit.dispose();//-vis: safe, never sighted
                    }
                    Set<Tile> tiles = exploreForUnit(naval.get(0));
                    if (!tiles.contains(entry)) tiles.add(entry);
                    invalidateCanSeeTiles();//+vis(this)
                    cs.add(See.perhaps(), tiles);
                    cs.addMessage(this,
                        new ModelMessage(MessageType.DEFAULT,
                                         "model.player.interventionForceArrives",
                                         this));
                    logger.info("Intervention force ("
                        + naval.size() + " naval, "
                        + land.size() + " land, "
                        + leftOver.size() + " left over) arrives at " + entry
                        + "(for " + port.getName() + ")");
                }
            }
        }

        // Update stances
        while (!stanceDirty.isEmpty()) {
            ServerPlayer s = stanceDirty.remove(0);
            Stance sta = getStance(s);
            boolean war = sta == Stance.WAR;
            if (sta == Stance.UNCONTACTED) continue;
            for (Player p : game.getLiveEuropeanPlayerList(this)) {
                ServerPlayer sp = (ServerPlayer) p;
                if (p == s || !p.hasContacted(this)
                    || !p.hasContacted(s)) continue;
                if (p.hasAbility(Ability.BETTER_FOREIGN_AFFAIRS_REPORT)
                    || war) {
                    cs.addStance(See.only(sp), this, sta, s);
                    cs.addMessage(sp,
                        new ModelMessage(MessageType.FOREIGN_DIPLOMACY,
                                         sta.getOtherStanceChangeKey(), this)
                            .addStringTemplate("%attacker%", getNationLabel())
                            .addStringTemplate("%defender%", s.getNationLabel()));
                }
            }
        }
    }

    /**
     * Gets the tag name of the object.
     *
     * @return "serverPlayer"
     */
    @Override
    public String getServerXMLElementTagName() {
        return "serverPlayer";
    }


    // Override Object

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("[ServerPlayer ").append(getId())
            .append(' ').append(getName())
            .append(' ').append(this.connection)
            .append(']');
        return sb.toString();
    }
}
