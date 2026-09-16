# Channel-Z TV

Unofficial Android TV / Fire TV / Android client for the [Channel-Z CyTube channel](https://cytu.be/r/Channel-Z).
Fork of [Mikes 420 Grindhouse App](https://github.com/kburna243/mikes-420grindhouse-app) (GPL v3) re-pointed at Channel-Z.

The full source lives in `source.zip`; the GitHub Actions workflow unpacks it and builds the APKs.

**Get the APK:** Actions tab → latest "Build APKs" run → download the `channel-z-tv-apks` artifact.
`channel-z-tv-light.apk` is the TV build; sideload it with the Downloader app or `adb install`.

To publish a Release with the APKs attached, create a tag like `v1.0.0` (Releases → Draft a new release → new tag).
