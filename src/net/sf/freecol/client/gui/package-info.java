/**
 * Contains the GUI classes.
 * <br><br>
 * A {@code JFrame} is created during the startup of the
 * program. This frame will be a {@link net.sf.freecol.client.gui.FreeColFrame}
 * which handles both windowed and full screen presentations.
 * A {@link net.sf.freecol.client.gui.Canvas} will then be added to the frame.
 * <br><br>
 * {@code Canvas} is the main container for the other GUI components in FreeCol.
 * This class is where the panels, dialogs and menus are added. In addition, {@code Canvas}
 * is the component in which the map graphics are displayed.
 * <br><br>
 * <b>Other important classes:</b>
 * <br>
 * <ul>
 * <li>The {@link net.sf.freecol.client.gui.GUI} contains the methods to draw
 * the map upon {@code Canvas}, in addition to other useful gui-methods.
 * <li>The {@link net.sf.freecol.client.gui.menu.FreeColMenuBar} is the menu bar that is
 * displayed on top corner of the {@code Canvas}.
 * </ul>
 */
package net.sf.freecol.client.gui;