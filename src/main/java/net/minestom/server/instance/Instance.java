package net.minestom.server.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.UpdateManager;
import net.minestom.server.data.Data;
import net.minestom.server.data.DataContainer;
import net.minestom.server.entity.*;
import net.minestom.server.entity.pathfinding.PFInstanceSpace;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventCallback;
import net.minestom.server.event.handler.EventHandler;
import net.minestom.server.event.instance.AddEntityToInstanceEvent;
import net.minestom.server.event.instance.RemoveEntityFromInstanceEvent;
import net.minestom.server.instance.batch.BlockBatch;
import net.minestom.server.instance.batch.ChunkBatch;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.instance.block.CustomBlock;
import net.minestom.server.network.PacketWriterUtils;
import net.minestom.server.network.packet.server.play.BlockActionPacket;
import net.minestom.server.network.packet.server.play.TimeUpdatePacket;
import net.minestom.server.storage.StorageLocation;
import net.minestom.server.thread.ThreadProvider;
import net.minestom.server.utils.BlockPosition;
import net.minestom.server.utils.Position;
import net.minestom.server.utils.chunk.ChunkCallback;
import net.minestom.server.utils.chunk.ChunkUtils;
import net.minestom.server.utils.time.CooldownUtils;
import net.minestom.server.utils.time.TimeUnit;
import net.minestom.server.utils.time.UpdateOption;
import net.minestom.server.utils.validate.Check;
import net.minestom.server.world.DimensionType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Instances are what are called "worlds" in Minecraft, you can add an entity in it using {@link Entity#setInstance(Instance)}.
 * <p>
 * An instance has entities and chunks, each instance contains its own entity list but the
 * chunk implementation has to be defined, see {@link InstanceContainer}.
 * <p>
 * WARNING: when making your own implementation registering the instance manually is required
 * with {@link InstanceManager#registerInstance(Instance)}, and
 * you need to be sure to signal the {@link UpdateManager} of the changes using
 * {@link UpdateManager#signalChunkLoad(Instance, int, int)} and {@link UpdateManager#signalChunkUnload(Instance, int, int)}.
 */
public abstract class Instance implements BlockModifier, EventHandler, DataContainer {

    protected static final BlockManager BLOCK_MANAGER = MinecraftServer.getBlockManager();
    protected static final UpdateManager UPDATE_MANAGER = MinecraftServer.getUpdateManager();

    private boolean registered;

    private final DimensionType dimensionType;

    private final WorldBorder worldBorder;

    // Tick since the creation of the instance
    private long worldAge;

    // The time of the instance
    private long time;
    private int timeRate = 1;
    private UpdateOption timeUpdate = new UpdateOption(1, TimeUnit.TICK);
    private long lastTimeUpdate;

    private final Map<Class<? extends Event>, Collection<EventCallback>> eventCallbacks = new ConcurrentHashMap<>();

    // Entities present in this instance
    protected final Set<Entity> entities = new CopyOnWriteArraySet<>();
    protected final Set<Player> players = new CopyOnWriteArraySet<>();
    protected final Set<EntityCreature> creatures = new CopyOnWriteArraySet<>();
    protected final Set<ObjectEntity> objectEntities = new CopyOnWriteArraySet<>();
    protected final Set<ExperienceOrb> experienceOrbs = new CopyOnWriteArraySet<>();
    // Entities per chunk
    protected final Map<Long, Set<Entity>> chunkEntities = new ConcurrentHashMap<>();

    // the uuid of this instance
    protected UUID uniqueId;

    // list of scheduled tasks to be executed during the next instance tick
    protected final ConcurrentLinkedQueue<Consumer<Instance>> nextTick = new ConcurrentLinkedQueue<>();

    // instance custom data
    private Data data;

    // the explosion supplier
    private ExplosionSupplier explosionSupplier;

    // Pathfinder
    private final PFInstanceSpace instanceSpace = new PFInstanceSpace(this);

    /**
     * Creates a new instance
     *
     * @param uniqueId      the {@link UUID} of the instance
     * @param dimensionType the {@link DimensionType} of the instance
     */
    public Instance(UUID uniqueId, DimensionType dimensionType) {
        this.uniqueId = uniqueId;
        this.dimensionType = dimensionType;

        this.worldBorder = new WorldBorder(this);
    }

    /**
     * Schedules a task to be run during the next instance tick
     * It ensures that the task will be executed in the same thread as the instance and its chunks/entities (depending of the {@link ThreadProvider})
     *
     * @param callback the task to execute during the next instance tick
     */
    public void scheduleNextTick(Consumer<Instance> callback) {
        this.nextTick.add(callback);
    }

    /**
     * Used to change the id of the block in a specific {@link BlockPosition}.
     * <p>
     * In case of a {@link CustomBlock} it does not remove it but only refresh its visual.
     *
     * @param blockPosition the block position
     * @param blockStateId  the new block state
     */
    public abstract void refreshBlockStateId(BlockPosition blockPosition, short blockStateId);

    /**
     * Does call {@link net.minestom.server.event.player.PlayerBlockBreakEvent}
     * and send particle packets
     *
     * @param player        the {@link Player} who break the block
     * @param blockPosition the {@link BlockPosition} of the broken block
     * @return true if the block has been broken, false if it has been cancelled
     */
    public abstract boolean breakBlock(Player player, BlockPosition blockPosition);

    /**
     * Forces the generation of a {@link Chunk}, even if no file and {@link ChunkGenerator} are defined.
     *
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @param callback consumer called after the chunk has been generated,
     *                 the returned chunk will never be null
     */
    public abstract void loadChunk(int chunkX, int chunkZ, ChunkCallback callback);

    /**
     * Loads the chunk if the chunk is already loaded or if
     * {@link #hasEnabledAutoChunkLoad()} returns true.
     *
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @param callback consumer called after the chunk has tried to be loaded,
     *                 contains a chunk if it is successful, null otherwise
     */
    public abstract void loadOptionalChunk(int chunkX, int chunkZ, ChunkCallback callback);

    /**
     * Schedules the removal of a {@link Chunk}, this method does not promise when it will be done.
     * <p>
     * WARNING: during unloading, all entities other than {@link Player} will be removed.
     * <p>
     * For {@link InstanceContainer} it is done during {@link InstanceContainer#tick(long)}
     *
     * @param chunk the chunk to unload
     */
    public abstract void unloadChunk(Chunk chunk);

    /**
     * Gets the loaded {@link Chunk} at a position.
     * <p>
     * WARNING: this should only return already-loaded chunk, use {@link #loadChunk(int, int)} or similar to load one instead.
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return the chunk at the specified position, null if not loaded
     */
    public abstract Chunk getChunk(int chunkX, int chunkZ);

    /**
     * Saves a {@link Chunk} to permanent storage.
     *
     * @param chunk    the {@link Chunk} to save
     * @param callback called when the {@link Chunk} is done saving
     */
    public abstract void saveChunkToStorage(Chunk chunk, Runnable callback);

    /**
     * Saves multiple chunks to permanent storage.
     *
     * @param callback called when the chunks are done saving
     */
    public abstract void saveChunksToStorage(Runnable callback);

    /**
     * Creates a new {@link BlockBatch} linked to this instance.
     *
     * @return a {@link BlockBatch} linked to the instance
     */
    public abstract BlockBatch createBlockBatch();

    /**
     * Creates a new {@link Chunk} batch linked to this instance and the specified chunk.
     *
     * @param chunk the chunk to modify
     * @return a ChunkBatch linked to {@code chunk}
     * @throws NullPointerException if {@code chunk} is null
     */
    public abstract ChunkBatch createChunkBatch(Chunk chunk);

    /**
     * Gets the instance {@link ChunkGenerator}.
     *
     * @return the {@link ChunkGenerator} of the instance
     */
    public abstract ChunkGenerator getChunkGenerator();

    /**
     * Changes the instance {@link ChunkGenerator}.
     *
     * @param chunkGenerator the new {@link ChunkGenerator} of the instance
     */
    public abstract void setChunkGenerator(ChunkGenerator chunkGenerator);

    /**
     * Gets all the instance's chunks.
     *
     * @return an unmodifiable containing all the loaded chunks of the instance
     */
    public abstract Collection<Chunk> getChunks();

    /**
     * Gets the instance {@link StorageLocation}.
     *
     * @return the {@link StorageLocation} of the instance
     */
    public abstract StorageLocation getStorageLocation();

    /**
     * Changes the instance {@link StorageLocation}.
     *
     * @param storageLocation the new {@link StorageLocation} of the instance
     */
    public abstract void setStorageLocation(StorageLocation storageLocation);

    /**
     * Used when a {@link Chunk} is not currently loaded in memory and need to be retrieved from somewhere else.
     * Could be read from disk, or generated from scratch.
     * <p>
     * WARNING: it has to retrieve a chunk, this is not optional.
     *
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk X
     * @param callback the callback executed once the {@link Chunk} has been retrieved
     */
    protected abstract void retrieveChunk(int chunkX, int chunkZ, ChunkCallback callback);

    /**
     * Called to generated a new {@link Chunk} from scratch.
     * <p>
     * This is where you can put your chunk generation code.
     *
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @param callback the callback executed with the newly created {@link Chunk}
     */
    protected abstract void createChunk(int chunkX, int chunkZ, ChunkCallback callback);

    /**
     * When set to true, chunks will load automatically when requested.
     * Otherwise using {@link #loadChunk(int, int)} will be required to even spawn a player
     *
     * @param enable enable the auto chunk load
     */
    public abstract void enableAutoChunkLoad(boolean enable);

    /**
     * Gets if the instance should auto load chunks.
     *
     * @return true if auto chunk load is enabled, false otherwise
     */
    public abstract boolean hasEnabledAutoChunkLoad();

    /**
     * Determines whether a position in the void. If true, entities should take damage and die.
     * <p>
     * Always returning false allow entities to survive in the void.
     *
     * @param position the position in the world
     * @return true iif position is inside the void
     */
    public abstract boolean isInVoid(Position position);

    /**
     * Gets if the instance has been registered in {@link InstanceManager}.
     *
     * @return true if the instance has been registered
     */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * Changes the registered field.
     * <p>
     * WARNING: should only be used by {@link InstanceManager}.
     *
     * @param registered true to mark the instance as registered
     */
    protected void setRegistered(boolean registered) {
        this.registered = registered;
    }

    /**
     * Gets the instance {@link DimensionType}.
     *
     * @return the dimension of the instance
     */
    public DimensionType getDimensionType() {
        return dimensionType;
    }

    /**
     * Gets the age of this instance in tick.
     *
     * @return the age of this instance in tick
     */
    public long getWorldAge() {
        return worldAge;
    }

    /**
     * Gets the current time in the instance (sun/moon).
     *
     * @return the time in the instance
     */
    public long getTime() {
        return time;
    }

    /**
     * Changes the current time in the instance, from 0 to 24000.
     * <p>
     * 0 = sunrise
     * 6000 = noon
     * 12000 = sunset
     * 18000 = midnight
     * <p>
     * This method is unaffected by {@link #getTimeRate()}
     * <p>
     * It does send the new time to all players in the instance, unaffected by {@link #getTimeUpdate()}
     *
     * @param time the new time of the instance
     */
    public void setTime(long time) {
        this.time = time;
        PacketWriterUtils.writeAndSend(getPlayers(), getTimePacket());
    }

    /**
     * Gets the rate of the time passing, it is 1 by default
     *
     * @return the time rate of the instance
     */
    public int getTimeRate() {
        return timeRate;
    }

    /**
     * Changes the time rate of the instance
     * <p>
     * 1 is the default value and can be set to 0 to be completely disabled (constant time)
     *
     * @param timeRate the new time rate of the instance
     * @throws IllegalStateException if {@code timeRate} is lower than 0
     */
    public void setTimeRate(int timeRate) {
        Check.stateCondition(timeRate < 0, "The time rate cannot be lower than 0");
        this.timeRate = timeRate;
    }

    /**
     * Gets the rate at which the client is updated with the current instance time
     *
     * @return the client update rate for time related packet
     */
    public UpdateOption getTimeUpdate() {
        return timeUpdate;
    }

    /**
     * Changes the rate at which the client is updated about the time
     * <p>
     * Setting it to null means that the client will never know about time change
     * (but will still change server-side)
     *
     * @param timeUpdate the new update rate concerning time
     */
    public void setTimeUpdate(UpdateOption timeUpdate) {
        this.timeUpdate = timeUpdate;
    }

    /**
     * Gets a {@link TimeUpdatePacket} with the current age and time of this instance
     *
     * @return the {@link TimeUpdatePacket} with this instance data
     */
    private TimeUpdatePacket getTimePacket() {
        TimeUpdatePacket timeUpdatePacket = new TimeUpdatePacket();
        timeUpdatePacket.worldAge = worldAge;
        timeUpdatePacket.timeOfDay = time;
        return timeUpdatePacket;
    }

    /**
     * Gets the instance {@link WorldBorder};
     *
     * @return the {@link WorldBorder} linked to the instance
     */
    public WorldBorder getWorldBorder() {
        return worldBorder;
    }

    /**
     * Gets the entities in the instance;
     *
     * @return an unmodifiable {@link Set} containing all the entities in the instance
     */
    public Set<Entity> getEntities() {
        return Collections.unmodifiableSet(entities);
    }

    /**
     * Gets the players in the instance;
     *
     * @return an unmodifiable {@link Set} containing all the players in the instance
     */
    public Set<Player> getPlayers() {
        return Collections.unmodifiableSet(players);
    }

    /**
     * Gets the creatures in the instance;
     *
     * @return an unmodifiable {@link Set} containing all the creatures in the instance
     */
    public Set<EntityCreature> getCreatures() {
        return Collections.unmodifiableSet(creatures);
    }

    /**
     * Gets the object entities in the instance;
     *
     * @return an unmodifiable {@link Set} containing all the object entities in the instance
     */
    public Set<ObjectEntity> getObjectEntities() {
        return Collections.unmodifiableSet(objectEntities);
    }

    /**
     * Gets the experience orbs in the instance.
     *
     * @return an unmodifiable {@link Set} containing all the experience orbs in the instance
     */
    public Set<ExperienceOrb> getExperienceOrbs() {
        return Collections.unmodifiableSet(experienceOrbs);
    }

    /**
     * Gets the entities located in the chunk.
     *
     * @param chunk the chunk to get the entities from
     * @return an unmodifiable {@link Set} containing all the entities in a chunk,
     * if {@code chunk} is unloaded, return an empty {@link HashSet}
     */
    public Set<Entity> getChunkEntities(Chunk chunk) {
        if (!ChunkUtils.isLoaded(chunk))
            return new HashSet<>();

        final long index = ChunkUtils.getChunkIndex(chunk.getChunkX(), chunk.getChunkZ());
        final Set<Entity> entities = getEntitiesInChunk(index);
        return Collections.unmodifiableSet(entities);
    }

    /**
     * Refreshes the visual block id at the position.
     * <p>
     * WARNING: the custom block id at the position will not change.
     *
     * @param x            the X position
     * @param y            the Y position
     * @param z            the Z position
     * @param blockStateId the new block state id
     */
    public void refreshBlockStateId(int x, int y, int z, short blockStateId) {
        refreshBlockStateId(new BlockPosition(x, y, z), blockStateId);
    }

    /**
     * Refreshes the visual block id at the position.
     * <p>
     * WARNING: the custom block id at the position will not change.
     *
     * @param x     the X position
     * @param y     the Y position
     * @param z     the Z position
     * @param block the new visual block
     */
    public void refreshBlockId(int x, int y, int z, Block block) {
        refreshBlockStateId(x, y, z, block.getBlockId());
    }

    /**
     * Refreshes the visual block id at the {@link BlockPosition}.
     * <p>
     * WARNING: the custom block id at the position will not change.
     *
     * @param blockPosition the block position
     * @param block         the new visual block
     */
    public void refreshBlockId(BlockPosition blockPosition, Block block) {
        refreshBlockStateId(blockPosition, block.getBlockId());
    }

    /**
     * Loads the {@link Chunk} at the given position without any callback.
     * <p>
     * WARNING: this is a non-blocking task.
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     */
    public void loadChunk(int chunkX, int chunkZ) {
        loadChunk(chunkX, chunkZ, null);
    }

    /**
     * Loads the chunk at the given {@link Position} with a callback.
     *
     * @param position the chunk position
     * @param callback the callback to run when the chunk is loaded
     */
    public void loadChunk(Position position, ChunkCallback callback) {
        final int chunkX = ChunkUtils.getChunkCoordinate((int) position.getX());
        final int chunkZ = ChunkUtils.getChunkCoordinate((int) position.getZ());
        loadChunk(chunkX, chunkZ, callback);
    }

    /**
     * Loads a {@link Chunk} (if {@link #hasEnabledAutoChunkLoad()} returns true)
     * at the given {@link Position} with a callback.
     *
     * @param position the chunk position
     * @param callback the callback executed when the chunk is loaded (or with a null chunk if not)
     */
    public void loadOptionalChunk(Position position, ChunkCallback callback) {
        final int chunkX = ChunkUtils.getChunkCoordinate((int) position.getX());
        final int chunkZ = ChunkUtils.getChunkCoordinate((int) position.getZ());
        loadOptionalChunk(chunkX, chunkZ, callback);
    }

    /**
     * Unloads the chunk at the given position.
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     */
    public void unloadChunk(int chunkX, int chunkZ) {
        final Chunk chunk = getChunk(chunkX, chunkZ);
        Check.notNull(chunk, "The chunk at " + chunkX + ":" + chunkZ + " is already unloaded");
        unloadChunk(chunk);
    }

    /**
     * Gives the block state id at the given position.
     *
     * @param x the X position
     * @param y the Y position
     * @param z the Z position
     * @return the block state id at the position
     */
    public short getBlockStateId(int x, int y, int z) {
        final Chunk chunk = getChunkAt(x, z);
        Check.notNull(chunk, "The chunk at " + x + ":" + z + " is not loaded");
        return chunk.getBlockStateId(x, y, z);
    }

    /**
     * Gives the block state id at the given position.
     *
     * @param x the X position
     * @param y the Y position
     * @param z the Z position
     * @return the block state id at the position
     */
    public short getBlockStateId(float x, float y, float z) {
        return getBlockStateId(Math.round(x), Math.round(y), Math.round(z));
    }

    /**
     * Gives the block state id at the given {@link BlockPosition}.
     *
     * @param blockPosition the block position
     * @return the block state id at the position
     */
    public short getBlockStateId(BlockPosition blockPosition) {
        return getBlockStateId(blockPosition.getX(), blockPosition.getY(), blockPosition.getZ());
    }

    /**
     * Gets the custom block object at the given position.
     *
     * @param x the X position
     * @param y the Y position
     * @param z the Z position
     * @return the custom block object at the position, null if not any
     */
    public CustomBlock getCustomBlock(int x, int y, int z) {
        final Chunk chunk = getChunkAt(x, z);
        Check.notNull(chunk, "The chunk at " + x + ":" + z + " is not loaded");
        return chunk.getCustomBlock(x, y, z);
    }

    /**
     * Gets the custom block object at the given {@link BlockPosition}.
     *
     * @param blockPosition the block position
     * @return the custom block object at the position, null if not any
     */
    public CustomBlock getCustomBlock(BlockPosition blockPosition) {
        return getCustomBlock(blockPosition.getX(), blockPosition.getY(), blockPosition.getZ());
    }

    /**
     * Sends a {@link BlockActionPacket} for all the viewers of the specific position.
     *
     * @param blockPosition the block position
     * @param actionId
     * @param actionParam
     * @see <a href="https://wiki.vg/Protocol#Block_Action">BlockActionPacket</a> for the action id &amp; param
     */
    public void sendBlockAction(BlockPosition blockPosition, byte actionId, byte actionParam) {
        final short blockStateId = getBlockStateId(blockPosition);
        final Block block = Block.fromStateId(blockStateId);

        BlockActionPacket blockActionPacket = new BlockActionPacket();
        blockActionPacket.blockPosition = blockPosition;
        blockActionPacket.actionId = actionId;
        blockActionPacket.actionParam = actionParam;
        blockActionPacket.blockId = block.getBlockId();

        final Chunk chunk = getChunkAt(blockPosition);
        chunk.sendPacketToViewers(blockActionPacket);
    }

    /**
     * Gets the block data at the given position.
     *
     * @param x the X position
     * @param y the Y position
     * @param z the Z position
     * @return the block data at the position, null if not any
     */
    public Data getBlockData(int x, int y, int z) {
        final Chunk chunk = getChunkAt(x, z);
        Check.notNull(chunk, "The chunk at " + x + ":" + z + " is not loaded");
        final int index = ChunkUtils.getBlockIndex(x, y, z);
        return chunk.getBlockData(index);
    }

    /**
     * Gets the block {@link Data} at the given {@link BlockPosition}.
     *
     * @param blockPosition the block position
     * @return the block data at the position, null if not any
     */
    public Data getBlockData(BlockPosition blockPosition) {
        return getBlockData(blockPosition.getX(), blockPosition.getY(), blockPosition.getZ());
    }

    /**
     * Sets the block {@link Data} at the given {@link BlockPosition}.
     *
     * @param x    the X position
     * @param y    the Y position
     * @param z    the Z position
     * @param data the data to be set, can be null
     */
    public void setBlockData(int x, int y, int z, Data data) {
        final Chunk chunk = getChunkAt(x, z);
        Check.notNull(chunk, "The chunk at " + x + ":" + z + " is not loaded");
        synchronized (chunk) {
            chunk.setBlockData(x, (byte) y, z, data);
        }
    }

    /**
     * Sets the block {@link Data} at the given {@link BlockPosition}.
     *
     * @param blockPosition the block position
     * @param data          the data to be set, can be null
     */
    public void setBlockData(BlockPosition blockPosition, Data data) {
        setBlockData(blockPosition.getX(), (byte) blockPosition.getY(), blockPosition.getZ(), data);
    }

    /**
     * Gets the {@link Chunk} at the given {@link BlockPosition}, null if not loaded.
     *
     * @param x the X position
     * @param z the Z position
     * @return the chunk at the given position, null if not loaded
     */
    public Chunk getChunkAt(float x, float z) {
        final int chunkX = ChunkUtils.getChunkCoordinate((int) x);
        final int chunkZ = ChunkUtils.getChunkCoordinate((int) z);
        return getChunk(chunkX, chunkZ);
    }

    /**
     * Checks if the {@link Chunk} at the position is loaded.
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return true if the chunk is loaded, false otherwise
     */
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return getChunk(chunkX, chunkZ) != null;
    }

    /**
     * Gets the {@link Chunk} at the given {@link BlockPosition}, null if not loaded.
     *
     * @param blockPosition the chunk position
     * @return the chunk at the given position, null if not loaded
     */
    public Chunk getChunkAt(BlockPosition blockPosition) {
        return getChunkAt(blockPosition.getX(), blockPosition.getZ());
    }

    /**
     * Gets the {@link Chunk} at the given {@link Position}, null if not loaded.
     *
     * @param position the chunk position
     * @return the chunk at the given position, null if not loaded
     */
    public Chunk getChunkAt(Position position) {
        return getChunkAt(position.getX(), position.getZ());
    }

    /**
     * Saves a {@link Chunk} without any callback.
     *
     * @param chunk the chunk to save
     */
    public void saveChunkToStorage(Chunk chunk) {
        saveChunkToStorage(chunk, null);
    }

    /**
     * Saves all {@link Chunk} without any callback.
     */
    public void saveChunksToStorage() {
        saveChunksToStorage(null);
    }

    /**
     * Gets the instance unique id.
     *
     * @return the instance unique id
     */
    public UUID getUniqueId() {
        return uniqueId;
    }

    @Override
    public Data getData() {
        return data;
    }

    @Override
    public void setData(Data data) {
        this.data = data;
    }

    @Override
    public Map<Class<? extends Event>, Collection<EventCallback>> getEventCallbacksMap() {
        return eventCallbacks;
    }

    // UNSAFE METHODS (need most of time to be synchronized)

    /**
     * Used when called {@link Entity#setInstance(Instance)}, it is used to refresh viewable chunks
     * and add viewers if {@code entity} is a {@link Player}.
     * <p>
     * Warning: unsafe, you probably want to use {@link Entity#setInstance(Instance)} instead.
     *
     * @param entity the entity to add
     */
    public void addEntity(Entity entity) {
        final Instance lastInstance = entity.getInstance();
        if (lastInstance != null && lastInstance != this) {
            lastInstance.removeEntity(entity); // If entity is in another instance, remove it from there and add it to this
        }
        AddEntityToInstanceEvent event = new AddEntityToInstanceEvent(this, entity);
        callCancellableEvent(AddEntityToInstanceEvent.class, event, () -> {
            final long[] visibleChunksEntity = ChunkUtils.getChunksInRange(entity.getPosition(), MinecraftServer.ENTITY_VIEW_DISTANCE);
            final boolean isPlayer = entity instanceof Player;

            if (isPlayer) {
                final Player player = (Player) entity;
                getWorldBorder().init(player);
            }

            // Send all visible entities
            for (long chunkIndex : visibleChunksEntity) {
                getEntitiesInChunk(chunkIndex).forEach(ent -> {
                    if (isPlayer) {
                        ent.addViewer((Player) entity);
                    }

                    if (ent instanceof Player) {
                        if (entity.isAutoViewable())
                            entity.addViewer((Player) ent);
                    }
                });
            }

            final Position entityPosition = entity.getPosition();
            final Chunk chunk = getChunkAt(entityPosition);
            Check.notNull(chunk, "You tried to spawn an entity in an unloaded chunk, " + entityPosition);
            addEntityToChunk(entity, chunk);
        });
    }

    /**
     * Used when an {@link Entity} is removed from the instance, it removes all of his viewers.
     * <p>
     * Warning: unsafe, you probably want to set the entity to another instance.
     *
     * @param entity the entity to remove
     */
    public void removeEntity(Entity entity) {
        final Instance entityInstance = entity.getInstance();
        if (entityInstance != this)
            return;

        RemoveEntityFromInstanceEvent event = new RemoveEntityFromInstanceEvent(this, entity);
        callCancellableEvent(RemoveEntityFromInstanceEvent.class, event, () -> {
            // Remove this entity from players viewable list and send delete entities packet
            entity.getViewers().forEach(entity::removeViewer);

            // Remove the entity from cache
            final Chunk chunk = getChunkAt(entity.getPosition());
            removeEntityFromChunk(entity, chunk);
        });
    }

    /**
     * Adds the specified {@link Entity} to the instance entities cache.
     * <p>
     * Warning: this is done automatically when the entity move out of his chunk.
     *
     * @param entity the entity to add
     * @param chunk  the chunk where the entity will be added
     */
    public void addEntityToChunk(Entity entity, Chunk chunk) {
        Check.notNull(chunk,
                "The chunk " + chunk + " is not loaded, you can make it automatic by using Instance#enableAutoChunkLoad(true)");
        Check.argCondition(!chunk.isLoaded(), "Chunk " + chunk + " has been unloaded previously");
        final long chunkIndex = ChunkUtils.getChunkIndex(chunk.getChunkX(), chunk.getChunkZ());
        synchronized (chunkEntities) {
            Set<Entity> entities = getEntitiesInChunk(chunkIndex);
            entities.add(entity);
            this.chunkEntities.put(chunkIndex, entities);

            this.entities.add(entity);
            if (entity instanceof Player) {
                this.players.add((Player) entity);
            } else if (entity instanceof EntityCreature) {
                this.creatures.add((EntityCreature) entity);
            } else if (entity instanceof ObjectEntity) {
                this.objectEntities.add((ObjectEntity) entity);
            } else if (entity instanceof ExperienceOrb) {
                this.experienceOrbs.add((ExperienceOrb) entity);
            }
        }
    }

    /**
     * Removes the specified {@link Entity} to the instance entities cache.
     * <p>
     * Warning: this is done automatically when the entity move out of his chunk.
     *
     * @param entity the entity to remove
     * @param chunk  the chunk where the entity will be removed
     */
    public void removeEntityFromChunk(Entity entity, Chunk chunk) {
        synchronized (chunkEntities) {
            if (chunk != null) {
                final long chunkIndex = ChunkUtils.getChunkIndex(chunk.getChunkX(), chunk.getChunkZ());
                Set<Entity> entities = getEntitiesInChunk(chunkIndex);
                entities.remove(entity);
                if (entities.isEmpty()) {
                    this.chunkEntities.remove(chunkIndex);
                } else {
                    this.chunkEntities.put(chunkIndex, entities);
                }
            }

            this.entities.remove(entity);
            if (entity instanceof Player) {
                this.players.remove(entity);
            } else if (entity instanceof EntityCreature) {
                this.creatures.remove(entity);
            } else if (entity instanceof ObjectEntity) {
                this.objectEntities.remove(entity);
            } else if (entity instanceof ExperienceOrb) {
                this.experienceOrbs.remove(entity);
            }
        }
    }

    private Set<Entity> getEntitiesInChunk(long index) {
        return chunkEntities.computeIfAbsent(index, i -> new CopyOnWriteArraySet<>());
    }

    /**
     * Schedules a block update at a given {@link BlockPosition}.
     * Does nothing if no {@link CustomBlock} is present at 'position'.
     * <p>
     * Cancelled if the block changes between this call and the actual update
     *
     * @param time     in how long this update must be performed?
     * @param unit     in what unit is the time expressed
     * @param position the location of the block to update
     */
    public abstract void scheduleUpdate(int time, TimeUnit unit, BlockPosition position);

    /**
     * Performs a single tick in the instance, including scheduled tasks from {@link #scheduleNextTick(Consumer)}.
     * <p>
     * Warning: this does not update chunks and entities.
     *
     * @param time the current time
     */
    public void tick(long time) {
        // scheduled tasks
        if (!nextTick.isEmpty()) {
            Consumer<Instance> callback;
            while ((callback = nextTick.poll()) != null) {
                callback.accept(this);
            }
        }

        {
            // time
            this.worldAge++;

            this.time += timeRate;

            // time needs to be send to players
            if (timeUpdate != null && !CooldownUtils.hasCooldown(time, lastTimeUpdate, timeUpdate)) {
                PacketWriterUtils.writeAndSend(getPlayers(), getTimePacket());
                this.lastTimeUpdate = time;
            }

        }

        this.worldBorder.update();
    }

    /**
     * Creates an explosion at the given position with the given strength.
     * The algorithm used to compute damages is provided by {@link #getExplosionSupplier()}.
     *
     * @param centerX  the center X
     * @param centerY  the center Y
     * @param centerZ  the center Z
     * @param strength the strength of the explosion
     * @throws IllegalStateException If no {@link ExplosionSupplier} was supplied
     */
    public void explode(float centerX, float centerY, float centerZ, float strength) {
        explode(centerX, centerY, centerZ, strength, null);
    }

    /**
     * Creates an explosion at the given position with the given strength.
     * The algorithm used to compute damages is provided by {@link #getExplosionSupplier()}.
     *
     * @param centerX        center X of the explosion
     * @param centerY        center Y of the explosion
     * @param centerZ        center Z of the explosion
     * @param strength       the strength of the explosion
     * @param additionalData data to pass to the explosion supplier
     * @throws IllegalStateException If no {@link ExplosionSupplier} was supplied
     */
    public void explode(float centerX, float centerY, float centerZ, float strength, Data additionalData) {
        final ExplosionSupplier explosionSupplier = getExplosionSupplier();
        Check.stateCondition(explosionSupplier == null, "Tried to create an explosion with no explosion supplier");
        final Explosion explosion = explosionSupplier.createExplosion(centerX, centerY, centerZ, strength, additionalData);
        explosion.apply(this);
    }

    /**
     * Gets the registered {@link ExplosionSupplier}, or null if none was provided
     *
     * @return the instance explosion supplier, null if none was provided
     */
    public ExplosionSupplier getExplosionSupplier() {
        return explosionSupplier;
    }

    /**
     * Registers the {@link ExplosionSupplier} to use in this instance
     *
     * @param supplier the explosion supplier
     */
    public void setExplosionSupplier(ExplosionSupplier supplier) {
        this.explosionSupplier = supplier;
    }

    /**
     * Gets the instance space
     * <p>
     * Used by the pathfinder for entities
     *
     * @return the instance space
     */
    public PFInstanceSpace getInstanceSpace() {
        return instanceSpace;
    }
}