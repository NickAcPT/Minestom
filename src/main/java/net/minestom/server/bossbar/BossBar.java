package net.minestom.server.bossbar;

import net.minestom.server.Viewable;
import net.minestom.server.chat.ColoredText;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.BossBarPacket;
import net.minestom.server.utils.MathUtils;
import net.minestom.server.utils.validate.Check;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Represents a boss bar which is displayed on the top of the client screen (max amount of boss bar defined by {@link #MAX_BOSSBAR}).
 * <p>
 * To use it, create a new instance and add the {@link Player} you want using {@link #addViewer(Player)} and remove them using {@link #removeViewer(Player)}.
 * <p>
 * You can retrieve all the boss bars of a {@link Player} with {@link #getBossBars(Player)}.
 */
public class BossBar implements Viewable {

    private static final int MAX_BOSSBAR = 7;
    private static final Map<Player, Set<BossBar>> PLAYER_BOSSBAR_MAP = new HashMap<>();

    private final UUID uuid = UUID.randomUUID();
    private final Set<Player> viewers = new CopyOnWriteArraySet<>();

    private ColoredText title;
    private float progress;
    private BarColor color;
    private BarDivision division;
    private byte flags;

    /**
     * Creates a new {@link BossBar}.
     *
     * @param title    the boss bar title
     * @param color    the boss bar color
     * @param division the boss bar division
     */
    public BossBar(ColoredText title, BarColor color, BarDivision division) {
        this.title = title;
        this.color = color;
        this.division = division;
    }

    /**
     * Gets all the visible boss bars of a {@link Player}.
     *
     * @param player the player to check the boss bars
     * @return all the visible boss bars of the player, null if not any
     */
    public static Set<BossBar> getBossBars(Player player) {
        return PLAYER_BOSSBAR_MAP.getOrDefault(player, null);
    }

    @Override
    public synchronized boolean addViewer(Player player) {
        // Check already viewer
        if (isViewer(player)) {
            return false;
        }
        // Check max boss bar count
        final Set<BossBar> playerBossBars = getBossBars(player);
        if (playerBossBars != null && playerBossBars.size() >= MAX_BOSSBAR) {
            return false;
        }

        addToPlayer(player);
        return viewers.add(player);
    }

    @Override
    public synchronized boolean removeViewer(Player player) {
        // Check not viewer
        final boolean result = this.viewers.remove(player);
        if (result) {
            removeToPlayer(player);
        }
        // Remove from the map
        removePlayer(player);
        return result;
    }

    @Override
    public Set<Player> getViewers() {
        return Collections.unmodifiableSet(viewers);
    }

    /**
     * Gets the bossbar title.
     *
     * @return the current title of the bossbar
     */
    public ColoredText getTitle() {
        return title;
    }

    /**
     * Changes the bossbar title.
     *
     * @param title the new title of the bossbar
     */
    public void setTitle(ColoredText title) {
        this.title = title;
        updateTitle();
    }

    /**
     * Gets the bossbar progress.
     *
     * @return the current progress of the bossbar
     */
    public float getProgress() {
        return progress;
    }

    /**
     * Changes the bossbar progress.
     *
     * @param progress the new progress bar percentage
     * @throws IllegalArgumentException if {@code progress} is not between 0 and 1
     */
    public void setProgress(float progress) {
        Check.argCondition(!MathUtils.isBetween(progress, 0, 1),
                "BossBar progress percentage should be between 0 and 1");
        this.progress = progress;
        updateProgress();
    }

    /**
     * Gets the bossbar color.
     *
     * @return the current bossbar color
     */
    public BarColor getColor() {
        return color;
    }

    /**
     * Changes the bossbar color.
     *
     * @param color the new color of the bossbar
     */
    public void setColor(BarColor color) {
        this.color = color;
        updateStyle();
    }

    /**
     * Gets the bossbar division.
     *
     * @return the current bossbar division
     */
    public BarDivision getDivision() {
        return division;
    }

    /**
     * Changes the bossbar division.
     *
     * @param division the new bossbar division count
     */
    public void setDivision(BarDivision division) {
        this.division = division;
        updateStyle();
    }

    /**
     * Gets the bossbar flags.
     *
     * @return the flags
     */
    public byte getFlags() {
        return flags;
    }

    /**
     * Sets the bossbar flags.
     *
     * @param flags the bossbar flags
     * @see <a href="https://wiki.vg/Protocol#Boss_Bar">Boss bar packet</a>
     */
    public void setFlags(byte flags) {
        this.flags = flags;
    }

    /**
     * Deletes the boss bar and remove all of its viewers.
     */
    public void delete() {
        BossBarPacket bossBarPacket = new BossBarPacket();
        bossBarPacket.uuid = uuid;
        bossBarPacket.action = BossBarPacket.Action.REMOVE;
        sendPacketToViewers(bossBarPacket);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BossBar bossBar = (BossBar) o;
        return Objects.equals(uuid, bossBar.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }

    /**
     * Removes the player from the bossbar map.
     *
     * @param player the player to remove from the map
     */
    private void removePlayer(Player player) {
        if (!PLAYER_BOSSBAR_MAP.containsKey(player)) {
            return;
        }
        Set<BossBar> bossBars = PLAYER_BOSSBAR_MAP.get(player);
        bossBars.remove(this);
        if (bossBars.isEmpty()) {
            PLAYER_BOSSBAR_MAP.remove(player);
        }
    }

    /**
     * Sends a {@link BossBarPacket} to create the bossbar.
     * <p>
     * Also add the bossbar to the player viewing list.
     *
     * @param player the player to create the bossbar to
     */
    private void addToPlayer(Player player) {
        // Add to the map
        Set<BossBar> bossBars = PLAYER_BOSSBAR_MAP.computeIfAbsent(player, p -> new HashSet<>());
        bossBars.add(this);

        BossBarPacket bossBarPacket = new BossBarPacket();
        bossBarPacket.uuid = uuid;
        bossBarPacket.action = BossBarPacket.Action.ADD;
        bossBarPacket.title = title;
        bossBarPacket.health = progress;
        bossBarPacket.color = color;
        bossBarPacket.division = division;
        bossBarPacket.flags = flags;
        player.getPlayerConnection().sendPacket(bossBarPacket);
    }

    /**
     * Sends a {@link BossBarPacket} to remove the bossbar.
     *
     * @param player the player to remove the bossbar to
     */
    private void removeToPlayer(Player player) {
        BossBarPacket bossBarPacket = new BossBarPacket();
        bossBarPacket.uuid = uuid;
        bossBarPacket.action = BossBarPacket.Action.REMOVE;
        player.getPlayerConnection().sendPacket(bossBarPacket);
    }

    private void updateTitle() {
        BossBarPacket bossBarPacket = new BossBarPacket();
        bossBarPacket.uuid = uuid;
        bossBarPacket.action = BossBarPacket.Action.UPDATE_TITLE;
        bossBarPacket.title = title;
        sendPacketToViewers(bossBarPacket);
    }

    private void updateProgress() {
        BossBarPacket bossBarPacket = new BossBarPacket();
        bossBarPacket.uuid = uuid;
        bossBarPacket.action = BossBarPacket.Action.UPDATE_HEALTH;
        bossBarPacket.health = progress;
        sendPacketToViewers(bossBarPacket);
    }

    private void updateStyle() {
        BossBarPacket bossBarPacket = new BossBarPacket();
        bossBarPacket.uuid = uuid;
        bossBarPacket.action = BossBarPacket.Action.UPDATE_STYLE;
        bossBarPacket.color = color;
        sendPacketToViewers(bossBarPacket);
    }
}
