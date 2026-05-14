mumble-LinkAPI
==============

a generic interface (library) to provide positional audio information to mumble using the link plugin


The difference to a regular plugin is that instead of developing a native plugin and submitting it to mumble, one can use the provided library and call upon the native methods directly.

This is initially intended to work mainly for JNA (https://github.com/twall/jna) but is not limited to it. It should work with anything that is able to call native methods directly (i.e. BridJ, Scripts, AutoIt etc.), simply provide this library. 
Alternatively you can bake this library into your C-compatible code. 

For more information about the different data that can be committed see http://mumble.sourceforge.net/Link

Please review the [LinkAPI.h](https://github.com/zsawyer/mumble-LinkAPI/blob/master/src/LinkAPI.h) for a set of exported functions which can be called.


Changelog:
----------

1.1.4
- add in-game chat notifications for all Mumble link state changes:
  - world join: status banner showing mod version, OS, and current link state
    (searching or already active)
  - link established: green "ACTIVE" message with server context and player name
  - link restored after mid-session loss: green "RESTORED" message
  - link lost (Mumble closed / write error): red "INACTIVE" message with
    retry hint
  - context change (different server within same session): gold update message
- chat messages are queued via ConcurrentLinkedQueue and delivered safely from
  the background polling thread via mc.thePlayer.addChatMessage()
- improve MC 1.6.4 chat reliability:
  - retry failed chat deliveries instead of dropping join/status lines
  - add fallback GUI chat print path for environments where mapped player chat
    method names differ
- add client command `/mumble` with tab completion and diagnostics:
  - `/mumble help` lists all available options
  - `/mumble status|values|stats|checks|diag|all` displays live API/link data
  - `/mumble banner` re-displays join banner
  - `/mumble reconnect` forces link re-open
  - `/mumble test [text]` queues a chat pipeline test message

- eliminate intermediate native DLL entirely:
  - implement Mumble Link shared-memory protocol directly in Java via JNA
  - `MumbleLink.java` opens Windows "MumbleLink" named mapping or Linux
    "/MumbleLink.{uid}" POSIX shared memory directly (no LinkAPI.dll needed)
  - removes `NativeLoader`, `LinkAPILibrary`, and all bundled DLL files
  - removes `LinkAPILibrary`'s eager static initializers as a failure path
  - approach is identical to what Mumble's own reference implementations use

- minecraft 1.6.4 forge compatibility fix:
  - avoid hard-linking against one specific FMLCommonHandler#bus() return type
  - register tick handler via reflection to support 1.6.4 FML variants without NoSuchMethodError

1.1.0
- reliability overhaul for real-time clients (including Minecraft 1.6.4 integrations):
  - initialize now always refreshes name/description/version instead of skipping updates when version matches
  - commit now validates shared-memory initialization (prevents null-pointer crashes)
  - bounded wide-string writes now guarantee null-termination and clear trailing bytes
  - context writes now clear stale bytes to avoid leaking old payload data
  - relock now restores metadata only when backup metadata is known
- test runner portability/reliability fixes for Linux builds (header case, includes, safer wide-string test setup)

1.0.0
- basic release version
