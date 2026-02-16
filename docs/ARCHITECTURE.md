# Rewind Mod Architecture

A Fabric mod for Minecraft 1.21.11 that records world changes and can rewind them on demand.

## Overview

The mod records all block changes, block entity updates, and entity lifecycle events into a ring buffer. When the `/timeline rewind` command is executed, it reverses these changes to restore the world to a previous state.

## Core Components

### 1. TimelineManager (`core/TimelineManager.java`)

The central coordinator for the rewind system. Singleton per server lifecycle.

**Responsibilities:**
- Manages a ring buffer of `TickFrame` objects (default: 600 frames = 30 seconds)
- Coordinates recording state (recording/rewinding flags)
- Handles frame lifecycle (beginTick, endTick, commitCurrentFrame)
- Provides frames for rewind operations
- Enforces memory limits (default: 50MB)

**State:**
- `recording` - Pause/resume recording
- `rewinding` - True only while a rewind is in progress
- `frozen` - When true, no new frames and no emergency frames are created; rewind still works

**Key Methods:**
- `initialize(MinecraftServer)` - Called on server start
- `shutdown()` - Called on server stop
- `beginTick(long gameTime)` - Starts a new frame, commits any pending emergency frame (skipped when frozen)
- `endTick()` - Commits the current frame to the ring buffer (skipped when frozen)
- `ensureCurrentFrame()` - Creates an emergency frame for changes between ticks (skipped when frozen)
- `freeze()` - Commits any pending frame, then sets frozen; no more recording until unfreeze
- `getFramesForRewind(int tickCount)` - Returns frames in reverse chronological order

**Ring Buffer Implementation:**
```
frames[0..599] - Fixed-size array
writeHead - Next position to write
frameCount - Number of valid frames (0 to maxFrames)
```

### 2. TickRecorder (`core/TickRecorder.java`)

Static utility class providing hooks for mixins to record changes.

**Responsibilities:**
- Records block state changes
- Records block entity NBT changes
- Tracks entity spawns, despawns, and state updates
- Manages entity state snapshots between ticks

**Key Methods:**
- `recordBlockChange(world, pos, oldState, newState, oldBE, newBE)` - Records block changes
- `recordBlockEntityChange(world, pos, blockEntity, oldNbt, newNbt)` - Records BE NBT changes
- `beginTickEntityTracking(world)` - Snapshots entities at tick start
- `endTickEntityTracking(world)` - Detects entity changes at tick end
- `recordEntityRemoval(entity)` - Called when entity unloads

**Entity Tracking:**
- `previousEntityStates` - Map of UUID to last known NBT state
- `entitiesAtTickStart` - Set of UUIDs present at tick start
- `removedEntitiesThisTick` - Entities removed during current tick

### 3. RewindPlan (`core/RewindPlan.java`)

Immutable plan describing what a rewind would do (block target states, entity ops). Used for both execution and preview (dry-run). Contains `BlockKey`, `EntitySpawnInfo`, and maps/sets for blocks and entities.

### 4. RewindExecutor (`core/RewindExecutor.java`)

Builds and applies rewind plans. No longer performs world writes directly; it uses `buildPlan(frames)` and `applyPlan(server, plan)`.

**Rewind Phases (inside applyPlan):**
1. **Collect Entity Operations** - Identify entities to remove (spawned after target) and respawn (despawned after target)
2. **Remove Entities** - Discard entities that were spawned after the target time
3. **Restore Entity States** - Apply old position/velocity/health to existing entities
4. **Respawn Entities** - Recreate entities that were despawned
5. **Restore Blocks** - Apply old block states (oldest state wins for each position)
6. **Restore Block Entities** - Apply old NBT to standalone block entity changes

**Key Logic:**
- Frames are processed newest-to-oldest
- Uses `put()` (not `putIfAbsent`) so the oldest frame's state wins
- Disables recording during rewind to prevent feedback loops
- Returns detailed `RewindResult` with statistics

### 5. TimelineCommand (`command/TimelineCommand.java`)

Brigadier command registration for `/timeline`.

**Commands:**
- `/timeline rewind <seconds>` - Rewind 1-30 seconds
- `/timeline status` - Show recording state, frozen state, and buffer info
- `/timeline clear` - Clear all recorded frames
- `/timeline pause` / `resume` - Pause or resume recording
- `/timeline freeze` - Freeze timeline (commit pending frame, then stop all recording; rewind still works)
- `/timeline unfreeze` - Unfreeze
- `/timeline preview <seconds>` - Build a rewind plan with caps, send to commanding player (players only)
- `/timeline preview clear` - Send clear-preview packet to player

**Permissions:** Requires OP level 2 (gamemaster)

### 6. Preview (server → client)

- **PreviewPayload** (`network/PreviewPayload.java`) - CustomPayload with block entries (dimension, pos, kind) and entity entries (dimension, x,y,z, kind, type). Caps: MAX_BLOCKS=2000, MAX_ENTITY_OPS=500, MAX_RADIUS_SQ=64².
- **PreviewSender** (`network/PreviewSender.java`) - Builds plan, filters by player radius, builds payload, sends to player via `ServerPlayNetworking.send`. `sendClearToPlayer` sends empty payload.
- **RewindClientMod** - Client entrypoint: registers payload type and `ClientPlayNetworking.registerGlobalReceiver`; stores payload in PreviewRenderer.
- **PreviewRenderer** (`client/PreviewRenderer.java`) - Holds current payload; expires after 10s. `getCurrentPreview()` for use by a render hook (e.g. mixin or Fabric WorldRenderEvents when available).

## Data Structures

### TickFrame (`data/TickFrame.java`)

Represents all changes within a single server tick.

**Fields:**
- `gameTime` - Server world time when recorded
- `realTimestamp` - System time when recorded
- `blockDeltas` - List of block changes
- `blockEntityDeltas` - List of BE NBT changes
- `entityDeltas` - List of entity changes
- `sealed` - Whether frame is finalized
- `estimatedMemoryBytes` - Memory tracking

### BlockDelta (`data/BlockDelta.java`)

Records a block state change.

**Fields:**
- `dimension` - Registry key for the world
- `packedPos` - BlockPos packed as long
- `oldState` / `newState` - Block states before/after
- `oldBlockEntityNbt` / `newBlockEntityNbt` - Optional BE data

### BlockEntityDelta (`data/BlockEntityDelta.java`)

Records an in-place block entity NBT change (no block state change).

**Fields:**
- `dimension`, `packedPos`, `blockEntityType`
- `oldNbt` / `newNbt` - NBT before/after

### EntityDelta (`data/EntityDelta.java`)

Records entity lifecycle and state changes.

**Types:**
- `SPAWN` - Entity appeared (records new state)
- `DESPAWN` - Entity removed (records old state)
- `UPDATE` - Entity state changed (records both states)

**Fields:**
- `dimension`, `entityId` (UUID), `type`
- `entityType` - Identifier for spawning
- `oldState` / `newState` - Simplified NBT (position, velocity, health)

## Mixins

### BlockChangeMixin (`mixin/BlockChangeMixin.java`)

**Target:** `World.setBlockState(BlockPos, BlockState, int, int)`

Captures all block state changes including natural world updates (fire spread, leaf decay, etc).

### PlayerBlockBreakMixin (`mixin/PlayerBlockBreakMixin.java`)

**Target:** `ServerPlayerInteractionManager.tryBreakBlock(BlockPos)`

Captures player block breaking specifically. Essential because player breaks happen between ticks and create "emergency frames".

### BlockEntityMixin (`mixin/BlockEntityMixin.java`)

**Target:** `BlockEntity.markDirty()`

Captures block entity NBT changes. Stores old NBT on first markDirty call per tick.

### ServerWorldMixin (`mixin/ServerWorldMixin.java`)

**Target:** `ServerWorld.tick(BooleanSupplier)`

Calls `TickRecorder.beginTickEntityTracking()` at tick start and `endTickEntityTracking()` at tick end.

## Event Handlers (RewindMod.java)

- `CommandRegistrationCallback` - Registers `/timeline` commands
- `ServerLifecycleEvents.SERVER_STARTED` - Initializes TimelineManager
- `ServerLifecycleEvents.SERVER_STOPPING` - Shuts down TimelineManager
- `ServerTickEvents.START_SERVER_TICK` - Calls `TimelineManager.beginTick()`
- `ServerTickEvents.END_SERVER_TICK` - Calls `TimelineManager.endTick()`
- `ServerEntityEvents.ENTITY_UNLOAD` - Calls `TickRecorder.recordEntityRemoval()`

## Configuration (config/RewindConfig.java)

Default values (not yet exposed to users):
- `MAX_REWIND_SECONDS` = 30
- `DEFAULT_MAX_FRAMES` = 600
- `TICKS_PER_SECOND` = 20
- `MAX_MEMORY_BYTES` = 50MB

## Important Implementation Details

### Emergency Frames

Player block breaking happens during network packet processing, which occurs between server ticks. The normal flow is:

1. `beginTick()` creates new frame
2. Changes recorded to frame
3. `endTick()` commits frame

But player breaks happen before `beginTick()`, so:

1. Player breaks block → no current frame exists
2. `ensureCurrentFrame()` creates emergency frame
3. Break recorded to emergency frame
4. Next `beginTick()` detects non-empty frame, commits it first
5. Then creates new frame for the tick

### Frame Ordering for Rewind

Frames are retrieved newest-to-oldest. When multiple changes affect the same position:
- We want the **oldest** `oldState` (the state from before the rewind window)
- Solution: Iterate frames newest→oldest, use `put()` so last write (oldest) wins

### Entity State Simplification (v1)

Full entity NBT serialization is complex and version-dependent. v1 only tracks:
- Position (X, Y, Z)
- Rotation (Yaw, Pitch)
- Velocity (VelX, VelY, VelZ)
- OnGround flag
- Health (for LivingEntity)

Full NBT (inventory, AI state, etc.) deferred to v2.

### Excluded from Tracking

- Players (handled separately if at all)
- Lightning bolts (transient)
- Markers (technical entity)
- Client-side changes

## File Structure

```
src/main/java/io/github/rewind/
├── RewindMod.java              # Mod entry point
├── command/
│   └── TimelineCommand.java    # /timeline commands
├── config/
│   └── RewindConfig.java       # Configuration constants
├── core/
│   ├── TimelineManager.java    # Ring buffer management
│   ├── TickRecorder.java       # Change recording hooks
│   └── RewindExecutor.java     # Rewind execution logic
├── data/
│   ├── TickFrame.java          # Single tick's changes
│   ├── BlockDelta.java         # Block change record
│   ├── BlockEntityDelta.java   # BE change record
│   └── EntityDelta.java        # Entity change record
└── mixin/
    ├── BlockChangeMixin.java       # World.setBlockState hook
    ├── PlayerBlockBreakMixin.java  # Player break hook
    ├── BlockEntityMixin.java       # BE.markDirty hook
    └── ServerWorldMixin.java       # World tick hooks

src/main/resources/
├── fabric.mod.json             # Mod metadata
└── rewind.mixins.json          # Mixin configuration
```

## Future Improvements (v2+)

- Full entity NBT serialization
- Player inventory/XP tracking
- Chunk loading edge cases
- Piston/moving block handling
- Scheduled block tick restoration
- Configuration file for users
- Per-dimension toggle
- Rewind preview/confirmation
