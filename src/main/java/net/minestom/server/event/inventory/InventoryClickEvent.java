package net.minestom.server.event.inventory;

import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.ClickType;
import net.minestom.server.item.ItemStack;

/**
 * Called after {@link InventoryPreClickEvent}, this event cannot be cancelled and items related to the click
 * are already moved.
 */
public class InventoryClickEvent extends Event {

    private final Player player;
    private final Inventory inventory;
    private final int slot;
    private final ClickType clickType;
    private final ItemStack clickedItem;
    private final ItemStack cursorItem;

    public InventoryClickEvent(Player player, Inventory inventory, int slot, ClickType clickType, ItemStack clicked, ItemStack cursor) {
        this.player = player;
        this.inventory = inventory;
        this.slot = slot;
        this.clickType = clickType;
        this.clickedItem = clicked;
        this.cursorItem = cursor;
    }

    /**
     * Gets the player who clicked in the inventory.
     *
     * @return the player who clicked in the inventory
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * Can be null if the clicked inventory is the player one.
     *
     * @return the inventory where the click happened, null if this is the player's inventory
     */
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Gets the clicked slot number.
     *
     * @return the clicked slot number
     */
    public int getSlot() {
        return slot;
    }

    /**
     * Gets the click type.
     *
     * @return the click type
     */
    public ClickType getClickType() {
        return clickType;
    }

    /**
     * Gets the clicked item.
     *
     * @return the clicked item
     */
    public ItemStack getClickedItem() {
        return clickedItem;
    }

    /**
     * Gets the item in the player cursor.
     *
     * @return the cursor item
     */
    public ItemStack getCursorItem() {
        return cursorItem;
    }
}
