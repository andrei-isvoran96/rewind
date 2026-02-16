# Rewind

A Fabric mod for Minecraft 1.21.11 that lets you rewind world state up to 30 seconds on demand.

![demo.gif](demo.gif)

## Features

- **Block Rewind** - Restores destroyed, placed, or modified blocks
- **Entity Rewind** - Restores entity positions, health, and respawns killed mobs
- **Block Entity Support** - Tracks chest contents, furnace state, etc.
- **Memory Efficient** - Ring buffer with configurable memory limits
- **Server & Singleplayer** - Works on dedicated servers and in singleplayer worlds

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.16.14+
- Fabric API 0.119.5+
- Java 21

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/)
2. Download the latest release from [Releases](https://github.com/andrei-isvoran96/rewind/releases)
3. Place the JAR file in your `mods` folder
4. Launch the game

## Commands

All commands require OP level 2 (gamemaster permissions).

| Command | Description |
|---------|-------------|
| `/timeline rewind <seconds>` | Rewind the world by 1-30 seconds |
| `/timeline status` | Show recording status, buffer info, and frozen state |
| `/timeline clear` | Clear all recorded history |
| `/timeline pause` | Pause recording |
| `/timeline resume` | Resume recording |
| `/timeline freeze` | Freeze timeline (no new frames, no emergency frames; rewind still works) |
| `/timeline unfreeze` | Unfreeze timeline |
| `/timeline preview <seconds>` | Send a rewind-preview diff to your client (1-30s; overlay expires in 10s) |
| `/timeline preview clear` | Clear the preview overlay (players only) |

## Usage

1. Start playing - the mod automatically records all changes
2. Something goes wrong (creeper explosion, accidental break, etc.)
3. Run `/timeline rewind 10` to undo the last 10 seconds
4. The world is restored to its previous state

## What Gets Tracked

### Fully Supported
- Block placement and destruction
- Block state changes (doors, levers, redstone, etc.)
- Block entity data (chests, furnaces, signs, etc.)
- Entity spawning and despawning
- Entity position and movement
- Living entity health

### Partially Supported (v1 limitations)
- Entity inventory and AI state (position/health only for now)
- Player inventory and XP (not tracked in v1)

### Not Tracked
- Player position (players are not rewound)
- Unloaded chunks (changes in unloaded chunks aren't recorded)

## Configuration

Current defaults (configuration file coming in future version):

| Setting | Value | Description |
|---------|-------|-------------|
| Max Rewind | 30 seconds | Maximum rewind duration |
| Buffer Size | 600 frames | Number of ticks stored (30s × 20 TPS) |
| Memory Limit | 50 MB | Maximum memory usage |

## Building from Source

```bash
# Clone the repository
git clone https://github.com/andrei-isvoran96/rewind.git
cd rewind

# Build the mod
./gradlew build

# The JAR will be in build/libs/rewind-1.0.0.jar
```

## Development

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed technical documentation.

### Project Structure

```
src/main/java/io/github/rewind/
├── RewindMod.java           # Entry point
├── command/                 # /timeline commands
├── config/                  # Configuration
├── core/                    # Core logic
│   ├── TimelineManager.java # Ring buffer management
│   ├── TickRecorder.java    # Change recording
│   └── RewindExecutor.java  # Rewind execution
├── data/                    # Data structures
└── mixin/                   # Minecraft hooks
```

## Known Issues

- Rewinding during active explosions may cause visual glitches
- Some modded blocks/entities may not restore correctly
- Rapid successive rewinds may cause brief lag

## Roadmap

- [ ] Configuration file
- [ ] Full entity NBT support
- [ ] Player inventory tracking (opt-in)
- [x] Rewind preview (server sends diff; client stores it; 3D overlay can be extended via mixin)
- [ ] Per-dimension toggle
- [ ] Undo rewind command

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Credits

Built with [Fabric](https://fabricmc.net/) for Minecraft 1.21.11.
