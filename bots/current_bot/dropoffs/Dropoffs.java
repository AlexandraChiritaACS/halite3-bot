package bots.current_bot.dropoffs;

import bots.current_bot.mining.MiningFunctions;
import bots.current_bot.navigation.MapStatsKeeper;
import bots.current_bot.navigation.Navigation;
import bots.current_bot.utils.BotConstants;
import bots.current_bot.utils.CommonFunctions;
import bots.current_bot.utils.Logger;
import bots.current_bot.utils.MoveRegister;
import hlt.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Dropoffs {

    public static Optional<DropoffPlan> getDropoffPlan(
            Game game, Optional<DropoffPlan> currentPlan, Map<Integer, List<Double>> expectedHaliteTimes, int haliteForExceptionalDropoffs) {
        // Decides whether we should build a dropoff, and if so where and when we expect it to be.

        // No dropoff if too late in the game.
        if(game.turnNumber > Constants.MAX_TURNS * BotConstants.get().DROPOFF_TURNS()) return Optional.empty();
        // No dropoff if not enough ships.
        if(game.me.ships.size() < shipsNeededForNextDropoff(game)) return Optional.empty();

        Optional<DropoffPlan> newPlan = currentPlan;
        if(newPlan.isPresent() && newPlan.get().complete) {
            // Discard complete plans.
            newPlan = Optional.empty();
        }

        // Consider building a dropoff. We replan the site if the plan isn't underway yet. "Underway" means that
        // returning ships can go to the site, because we expect to have the halite in time to build it.
        if(!newPlan.isPresent() || !newPlan.get().underway) {
            // Our model is that we save halite*c ship turns (where c is a constant that doesn't actually end up appearing).
            // On the other hand, building a ship gives us b new ship turns. So we need halite > constant for a dropoff
            // to be better than a ship.
            // Also, for a dropoff to be worth more than 4000, we need halite*c*ship_turn_value > 4000, or halite > K / ship_turn_value.
            double bestHalite = Math.max(BotConstants.get().DROPOFF_HALITE(), BotConstants.get().DROPOFF_HALITE_SHIP_TURN() / MiningFunctions.shipTurnValue());
            Logger.info(String.format("Need %f halite for a dropoff - %f for absolute threshold, %f / %f = %f for ship-value threshold",
                    bestHalite,
                    BotConstants.get().DROPOFF_HALITE(),
                    BotConstants.get().DROPOFF_HALITE_SHIP_TURN(),
                    MiningFunctions.shipTurnValue(),
                    BotConstants.get().DROPOFF_HALITE_SHIP_TURN() / MiningFunctions.shipTurnValue()));
            Optional<Position> bestPosition = Optional.empty();
            for(int x=0; x<game.map.height; x++) {
                    for(int y=0; y<game.map.width; y++) {
                        MapCell c = game.map.cells[x][y];
                        if(c.hasStructure()) continue;
                        Position pos = c.position;
                        boolean enemyNearby = false;

                        // No dropoff is there's an enemy adjacent to the square. They'll probably mine it.
                        for(Position nbr : CommonFunctions.getNeighbourhood(game.map, pos, 1)) {
                            if(CommonFunctions.hasEnemyShip(game, nbr))enemyNearby = true;
                        }
                        if (enemyNearby) continue;

                        // No dropoff if we're too close to another dropoff.
                        int distance = MapStatsKeeper.nearestDropoffDistance(pos, game.me, game, Optional.<DropoffPlan>empty());
                        if (distance < BotConstants.get().MIN_DROPOFF_DISTANCE()) {
                            // Too close to another dropoff.
                            continue;
                        }

                        // No dropoff if there is no friendly ship nearby.
                        if(bots.current_bot.utils.CommonFunctions.ourShipsNearby(
                                game, pos, BotConstants.get().DROPOFF_SHIP_MAX_DISTANCE()).isEmpty()) continue;

                        // Count the nearby halite, exponentially weighted.
                        double nearbyHalite = CommonFunctions.haliteNearby(
                                game, c.position, BotConstants.get().DROPOFF_RADIUS(), BotConstants.get().DROPOFF_HALITE_DROPOFF());
                        // Adjust for how much we control this region.
                        nearbyHalite *= control(game, c.position, BotConstants.get().DROPOFF_RADIUS());
                        // Adjust for extra distance from nearby dropoffs.
                        nearbyHalite *= 1 + (distance - BotConstants.get().MIN_DROPOFF_DISTANCE()) * BotConstants.get().DROPOFF_EXTRA_DIST_BONUS();
                        // Adjust for how much this dropoff costs.
                        double costNorm = (double)(Constants.DROPOFF_COST - c.halite) / Constants.DROPOFF_COST;
                        costNorm = costNorm <= 0.1 ? 0.1 : costNorm;
                        nearbyHalite /= costNorm;
                        if (nearbyHalite > bestHalite) {
                            // Make this our new target. This next check doesn't seem to actually do anything, because
                            // of the values of these constants.
                            Integer ourShips = CommonFunctions.ourShipsNearby(game, c.position, BotConstants.get().DROPOFF_RADIUS()).size();
                            if(ourShips > BotConstants.get().DROPOFF_MIN_NEARBY_SHIPS()){
                                bestHalite = nearbyHalite;
                                bestPosition = Optional.of(pos);
                            }
                        }
                    }
            }
            if(bestPosition.isPresent()){
                Logger.info("New dropoff plan!");
                newPlan = Optional.of(new DropoffPlan(bestPosition.get()));
            }
        }

        // If we have a plan, better try to build!
        if(newPlan.isPresent()) {
            Position dest = newPlan.get().destination;
            // We need to redo some checks from the above, because if the plan is underway they haven't been done this
            // turn.

            // Don't build dropoffs on dropoffs.
            if(game.map.at(dest).hasStructure()) return Optional.empty();
            boolean enemyNearby = false;

            // Cancel the plan, even if it is underway, if there's an enemy too close.
            for(Position nbr : CommonFunctions.getNeighbourhood(game.map, dest, 1)) {
                if(CommonFunctions.hasEnemyShip(game, nbr))enemyNearby = true;
            }
            if(enemyNearby) return  Optional.empty();

            // We assume ships looking to build dropoffs have a certain amount of halite, and calculate how much halite
            // we need in the bank.
            Integer assumedShipHalite = (int)(BotConstants.get().ASSUMED_RETURNING_PROPORTION()*Constants.MAX_HALITE);
            Integer haliteNeeded = Constants.DROPOFF_COST - game.map.at(dest).halite - assumedShipHalite;
            newPlan.get().haliteNeeded = haliteNeeded;

            // We use the expected halite times of returners to calculate how many turns it will take us to be ready
            // to build the dropoff.
            double expectedHaliteAtTime = game.me.halite - haliteForExceptionalDropoffs;
            Integer maxTurnWithHalite = expectedHaliteTimes.isEmpty() ? 0 : Collections.max(expectedHaliteTimes.keySet());
            Optional<Integer> turnWhenReady = Optional.empty();
            for(int t=0; t<=maxTurnWithHalite; t++) {
                if(expectedHaliteAtTime > haliteNeeded){
                    turnWhenReady = Optional.of(t);
                    break;
                }
                if(expectedHaliteTimes.containsKey(t)) {
                    expectedHaliteAtTime += expectedHaliteTimes.get(t).stream().mapToDouble(a->a).sum();
                }
            }

            // If we declare this dropoff plan underway, we could get a returner in this many turns. Would be better to
            // check for returners only, probably.
            Integer whenNeeded = CommonFunctions.nearestFriendlyShip(game, dest);

            // Check if we'll have enough halite to build when the returner might arrive.
            if(!turnWhenReady.isPresent()) {
                Logger.info(String.format("Not enough halite for dropoff expected - save up %d", haliteNeeded));
                newPlan.get().underway = false;
            }
            else if(whenNeeded < turnWhenReady.get()) {
                Logger.info(String.format("Want to build dropoff at distance %d from ship, but will take %d turns to gather" +
                        "halite.", whenNeeded, turnWhenReady.get()));
                newPlan.get().underway = false;
            }
            else {
                Logger.info("Dropoff plan underway! Expect to have the halite when we need to build it.");
                newPlan.get().underway = true;
            }
        }

        return newPlan;
    }

    private static int shipsNeededForNextDropoff(Game game) {
        int d = game.me.dropoffs.size();
        if(d == 0) return BotConstants.get().FIRST_DROPOFF_SHIPS();
        else return BotConstants.get().FIRST_DROPOFF_SHIPS() + BotConstants.get().SECOND_DROPOFF_SHIPS() + (d-1)*BotConstants.get().SHIPS_PER_DROPOFF();
    }

    private static double control(Game game, Position position, int radius) {
        Integer ourShips = 0;
        Integer theirShips = 0;
        for(Position p : CommonFunctions.getNeighbourhood(game.map, position, radius)) {
            if(!game.map.at(p).hasShip()) continue;
            if(game.map.at(p).ship.owner.equals(game.myId)) ourShips++;
            else theirShips++;
            if(ourShips + theirShips == BotConstants.get().DROPOFF_TERRITORY_SHIPS()) break;
        }
        if(ourShips >= theirShips) return 1.0;
        return (double)(ourShips - theirShips + BotConstants.get().DROPOFF_TERRITORY_SHIPS()) / (BotConstants.get().DROPOFF_TERRITORY_SHIPS());
    }

    public static int getExceptionalDropoffs(Game game, MoveRegister moveRegister) {
        /**
         * Build dropoffs on any squares with ships and massive piles of halite. Roughly, we scale down all the usual
         * requirements according to how much halite is on a square.
         */
        if(game.turnNumber > Constants.MAX_TURNS * BotConstants.get().DROPOFF_TURNS()) return 0;

        int haliteNeeded = 0;

        for(Ship s : moveRegister.getRemainingShips()) {
            int halite = game.map.at(s.position).halite;
            if(halite < BotConstants.get().MIN_EXCEPTIONAL_HALITE()) continue;
            if(halite > Constants.DROPOFF_COST) {
                Logger.info("Building an exceptional dropoff for free!");
                moveRegister.registerDropoff(s);
                // Dropoff is free!
            }
            else {
                // p is linear between 1 at MIN_EXCEPTIONAL_HALITE and 0 at DROPOFF_COST
                Logger.info(String.format("Considering exceptional dropoff for ship %s", s));
                int haliteToBuild = Constants.DROPOFF_COST - s.halite - halite;
                haliteToBuild = haliteToBuild > 0 ? haliteToBuild : 0;
                if(haliteToBuild > game.me.halite - haliteNeeded) continue;

                double p = 1 - (halite -  BotConstants.get().MIN_EXCEPTIONAL_HALITE()) / (Constants.DROPOFF_COST -  BotConstants.get().MIN_EXCEPTIONAL_HALITE());
                MapCell c = game.map.at(s.position);
                if(c.hasStructure()) continue;
                Position pos = c.position;
                int distance = MapStatsKeeper.nearestDropoffDistance(pos, game.me, game, Optional.<DropoffPlan>empty());
                if (distance < BotConstants.get().MIN_DROPOFF_DISTANCE() * p) {
                    // Too close to another dropoff.
                    continue;
                }

                double nearbyHalite = CommonFunctions.haliteNearby(
                        game, c.position, BotConstants.get().DROPOFF_RADIUS(), BotConstants.get().DROPOFF_HALITE_DROPOFF());
                nearbyHalite *= control(game, c.position, BotConstants.get().DROPOFF_RADIUS());
                nearbyHalite *= 1 + (distance - BotConstants.get().MIN_DROPOFF_DISTANCE()) * BotConstants.get().DROPOFF_EXTRA_DIST_BONUS();
                if (nearbyHalite > BotConstants.get().DROPOFF_HALITE()) {
                    Integer ourShips = CommonFunctions.ourShipsNearby(game, c.position, BotConstants.get().DROPOFF_RADIUS()).size();
                    if(ourShips > BotConstants.get().DROPOFF_MIN_NEARBY_SHIPS()*p) {
                        Logger.info("Building an exceptional dropoff!");
                        moveRegister.registerDropoff(s);
                        haliteNeeded += haliteToBuild;
                    }
                }
            }
        }
        return haliteNeeded;
    }
}
