/**
 *  Copyright (C) 2002-2017   The FreeCol Team
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

package net.sf.freecol.client.gui.animation;

import net.sf.freecol.client.FreeColClient;
import net.sf.freecol.client.control.FreeColClientHolder;
import net.sf.freecol.client.gui.SwingGUI;
import net.sf.freecol.common.io.sza.SimpleZippedAnimation;
import net.sf.freecol.common.model.Direction;
import net.sf.freecol.common.model.Tile;
import net.sf.freecol.common.model.Unit;
import net.sf.freecol.common.resources.ResourceManager;


/**
 * Class for the animation of units attacks.
 */
final class UnitAttackAnimation extends FreeColClientHolder {

    private final Unit attacker;
    private final Unit defender;
    private final Tile attackerTile;
    private final Tile defenderTile;
    private final boolean success;

    // Local members to load the animation information into.
    private SimpleZippedAnimation sza = null;
    private boolean mirror = false;


    /**
     * Build a new attack animation.
     *
     * @param freeColClient The enclosing {@code FreeColClient}.
     * @param attacker The {@code Unit} that is attacking.
     * @param defender The {@code Unit} that is defending.
     * @param attackerTile The {@code Tile} the attack comes from.
     * @param defenderTile The {@code Tile} the attack goes to.
     * @param success Does the attack succeed?
     */
    public UnitAttackAnimation(FreeColClient freeColClient,
                               Unit attacker, Unit defender,
                               Tile attackerTile, Tile defenderTile,
                               boolean success) {
        super(freeColClient);

        this.attacker = attacker;
        this.defender = defender;
        this.attackerTile = attackerTile;
        this.defenderTile = defenderTile;
        this.success = success;
    }


    /**
     * Low level animation search.
     *
     * @param startStr The prefix for the animation resource.
     * @param scale The gui scale.
     * @param direction The {@code Direction} of the attack.
     * @return True if the animation is loaded.
     */
    private boolean loadAnimation(String startStr, float scale,
                                  Direction direction) {
        String specialId = startStr + direction.toString().toLowerCase();
        if (ResourceManager.hasSZAResource(specialId)) {
            this.sza = ResourceManager.getSimpleZippedAnimation(specialId, scale);
            this.mirror = false;
            return true;
        }

        Direction mirrored = direction.getEWMirroredDirection();
        if (mirrored != direction) {
            specialId = startStr + mirrored.toString().toLowerCase();
            if (ResourceManager.hasSZAResource(specialId)) {
                this.sza = ResourceManager.getSimpleZippedAnimation(specialId, scale);
                this.mirror = true;
                return true;
            }
        }
        return false;
    }

    /**
     * Find the animation for a unit attack.
     *
     * @param unit The {@code Unit} to animate.
     * @param direction The {@code Direction} of the attack.
     * @return True if the animation is loaded.
     */
    private boolean loadAnimation(Unit unit, Direction direction) {
        float scale = ((SwingGUI)getGUI()).getMapScale();
        String roleStr = (unit.hasDefaultRole()) ? ""
            : "." + unit.getRoleSuffix();
        String startStr = "animation.unit." + unit.getType().getId() + roleStr
                        + ".attack.";

        // Only check directions not covered by the EW mirroring.
        // Favour East and West animations.
        if (loadAnimation(startStr, scale, direction)) return true;
        switch (direction) {
        case N: case S:
            return loadAnimation(startStr, scale, direction.getNextDirection())
                || loadAnimation(startStr, scale, direction.rotate(2))
                || loadAnimation(startStr, scale, direction.rotate(3))
                || loadAnimation(startStr, scale, direction.rotate(-3));
        case NE: case SW:
            return loadAnimation(startStr, scale, direction.getNextDirection())
                || loadAnimation(startStr, scale, direction.getPreviousDirection())
                || loadAnimation(startStr, scale, direction.rotate(2))
                || loadAnimation(startStr, scale, direction.rotate(3));
        case NW: case SE:
            return loadAnimation(startStr, scale, direction.getPreviousDirection())
                || loadAnimation(startStr, scale, direction.getNextDirection())
                || loadAnimation(startStr, scale, direction.rotate(-2))
                || loadAnimation(startStr, scale, direction.rotate(-3));
        case E: case W:
            return loadAnimation(startStr, scale, direction.getNextDirection())
                || loadAnimation(startStr, scale, direction.getPreviousDirection())
                || loadAnimation(startStr, scale, direction.rotate(2))
                || loadAnimation(startStr, scale, direction.rotate(-2));
        default:
            break;
        }
        return false;           
    }

    /**
     * Do the animation.
     *
     * @return True if the required animations were found and executed,
     *     false on error.
     */
    public boolean animate() {
        Direction direction = this.attackerTile.getDirection(this.defenderTile);
        if (direction == null) return false; // Fail fast

        final FreeColClient fcc = getFreeColClient();
        final SwingGUI gui = (SwingGUI)getGUI();
        boolean ret = true;

        if (fcc.getAnimationSpeed(this.attacker.getOwner()) > 0) {
            if (loadAnimation(this.attacker, direction)) {
                new UnitImageAnimation(gui, this.attacker, this.attackerTile,
                                       this.sza, this.mirror).animate();
            } else {
                ret = false;
            }
        }

        if (!success && fcc.getAnimationSpeed(this.defender.getOwner()) > 0) {
            if (loadAnimation(this.defender, direction.getReverseDirection())) {
                new UnitImageAnimation(gui, this.defender, this.defenderTile,
                                       this.sza, this.mirror).animate();
            } else {
                ret = false;
            }
        }
        return ret;
    }
}
