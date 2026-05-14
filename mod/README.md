# MumbleLink Minecraft 1.6.4 Forge Mod

Integrates Mumble positional audio into Minecraft 1.6.4 using the [LinkAPI](../README.md)
native C library. Drop the release JAR into your `mods/` folder and start Minecraft with Forge.

## Installation

1. Install [Minecraft Forge for 1.6.4](https://files.minecraftforge.net/) (build 9.11.1.965 or later).
2. Copy `release/mumblelink-1.6.4.jar` into the `mods/` folder of your Minecraft installation.
3. Start Mumble and enable the **Link** plugin (Mumble → Configure → Plugins → Link to game).
4. Start Minecraft.  Positional audio activates automatically once you load or join a world.

## How it works

Every ~50 ms (background daemon thread at 20 Hz), the mod:

| Step | What it does |
|------|-------------|
| 1 | Reads the player's eye position (`posX`, `posY + eyeHeight`, `posZ`) |
| 2 | Converts yaw/pitch to a unit look-vector |
| 3 | Calls `commitVectorsAvatarAsCamera(pos, front, top)` |
| 4 | Calls `commitIdentity(playerName)` |
| 5 | Calls `commitContext(serverIP)` when the server changes |

When the player leaves the world, `unlinkMumble()` is called so Mumble falls back to
non-positional voice.

### Coordinate system

| Minecraft | Mumble |
|-----------|--------|
| +X (East) | +X     |
| +Y (Up)   | +Y     |
| +Z (South)| +Z     |

## Native libraries bundled in the JAR

| Path in JAR | Platform |
|-------------|---------|
| `native/windows/x86/LinkAPI.dll`   | Windows 32-bit |
| `native/windows/amd64/LinkAPI.dll` | Windows 64-bit |
| `native/linux/amd64/libLinkAPI.so` | Linux 64-bit   |

The `NativeLoader` class extracts the appropriate library to a temp directory and sets
`jna.library.path` before JNA initialises.

## Building from source

```bash
# Native library + tests
make -C ide/netbeans        # Windows DLLs (requires Cygwin/MinGW)
# or
bash ide/scripts/make.sh    # Linux

# Mod JAR
cd mod
gradle build                # requires Gradle 6+ and internet access for Forge toolchain
# OR compile manually:
# (see mod/build.gradle for classpath details)
```

The release JAR is also pre-built at `release/mumblelink-1.6.4.jar`.

## Dependencies

- [JNA 4.0.0](https://github.com/java-native-access/jna) – bundled inside the mod JAR.
- Minecraft Forge 9.11.x for MC 1.6.4 – installed by the player; not bundled.
