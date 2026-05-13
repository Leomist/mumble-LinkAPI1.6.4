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

1.1.2
- forge compatibility fix (part 2 – link actually works now):
  - replace FML event-bus registration with a background daemon thread (50 ms / 20 Hz)
  - the previous reflection approach was still silently broken: Guava EventBus ignores `@SubscribeEvent`; some 1.6.4 FML builds have no `bus()` method at all
  - thread approach is independent of FML EventBus API version; no silent registration failures

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
