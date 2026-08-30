# Attractors — Android live wallpaper

A native Android port of [attractors](https://github.com/steren/attractors)
([attractors.steren.fr](https://attractors.steren.fr)): generative art made of particles
flowing through a field of attractors.

A piece paints itself over about a minute, then holds still. It does not loop, it does not
breathe, it does not animate behind your apps — once it has finished painting, the
wallpaper costs nothing at all to display.

| Light theme | Dark theme | With text |
|---|---|---|
| ![](docs/light.jpg) | ![](docs/dark.jpg) | ![](docs/text.jpg) |

The first two follow the system: in the light theme the piece is painted on the accent
color Android derives from your home screen, in the dark theme on plain black. The third
uses the palette of the web page, with `A T T R A C T O R S` set as the text.

## Building and installing

The build needs a JDK 17 or newer:

```sh
export JAVA_HOME=/path/to/jdk        # e.g. ~/.local/share/jdks/jdk-21.0.12.1+1
./gradlew installDebug
```

Then pick *Attractors* from the wallpaper picker: long press the home screen, or Settings →
Wallpaper & style. There is no icon in the app drawer, because there is no app — this is a
wallpaper and nothing else. To go straight to its preview:

```sh
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER \
  --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT \
  fr.steren.attractors/.AttractorsWallpaperService
```

## Releases

Publishing a GitHub release builds the release APK and attaches it to that release, via
`.github/workflows/release-apk.yml`. A release tagged `v1.4.2` produces
`attractors-1.4.2.apk`. The workflow can also be run by hand, with a tag to rebuild and
re-attach that release's APK, or without one to just build.

To get a signed APK — and an unsigned one cannot be installed — add four repository
secrets. Generate a key once:

```sh
keytool -genkeypair -v -keystore upload.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 upload.jks        # the value of KEYSTORE_BASE64
```

| Secret | |
|---|---|
| `KEYSTORE_BASE64` | The keystore, base64 encoded |
| `KEYSTORE_PASSWORD` | Its password |
| `KEY_PASSWORD` | The key's own password |

The alias is not a secret — it is `upload` in the workflow, and it is in the certificate of
every APK regardless.

Keep `upload.jks` somewhere safe and out of the repository: Android identifies an app by
the key it was signed with, so losing it means no one who installed an earlier build can
upgrade. Without these secrets the workflow still runs, and attaches an APK named `-unsigned` along
with a warning, rather than something that looks installable and is not — Android refuses
to install an unsigned APK, from any source.

The same environment variables drive a local release build:

```sh
KEYSTORE_FILE=upload.jks KEYSTORE_PASSWORD=... KEY_ALIAS=upload KEY_PASSWORD=... \
  VERSION_NAME=1.4.2 VERSION_CODE=10402 ./gradlew :app:assembleRelease
```

## How it is put together

| | |
|---|---|
| `AttractorPiece` | The piece itself: the field, the particles, and the bitmap they paint |
| `AttractorsWallpaperService` | The `WallpaperService`, its render loop and its lifecycle |
| `PieceConfig` / `Palette` | What a piece looks like, and where the colors come from |
| `Settings` | The user's choices, read once and re-read only when they change |

Around 1400 lines of Kotlin. No Compose, and no dependency beyond `core-ktx` and
`preference-ktx`; the release APK is under a megabyte.

## What was ported

`AttractorPiece` is a port of `attractors.js`. The field, the movement of the particles,
the seeding, the trails, the shadows and the way the text bends the field around itself
all follow the original, so a piece rendered here looks like one the web page renders with
the same settings.

Two things needed reworking rather than translating:

**Shadows come to rest instead of piling up to black.** The web version stamps a nearly
transparent black onto an 8 bit canvas, and the rounding of that canvas is what stops the
shadows: a channel darkens by one step for as long as `shadow_opacity * channel` rounds up
to 1, so it comes to rest at `0.5 / shadow_opacity` and never goes below, and a channel
already darker than that never moves at all — which is why a dark background takes no
shadows. Skia rounds the other way, and the very same stamps take an area all the way to
black. So the limit is made explicit here: shadows are painted in the color the web version
comes to rest on, at the alpha that walks there at about one 8 bit step per stamp.

**Text outlines come from the platform.** The web version walks the path commands
opentype.js hands it. Here the outline comes from Android's own text engine, and
`PathMeasure` flattens each contour into the segments that become attractors. The font,
CamBam Stick, is the same one the web page uses. A string too long for a phone is shrunk to
fit rather than run off both edges.

Not ported: the two characters the library draws itself, `▲` and `⬣`, and the no-go zones —
both exist for the page's layout, which a wallpaper does not have.

## Colors

By default the wallpaper follows the system. In the light theme it paints on the accent
Android derives from your home screen; in the dark theme the background is plain black, so
the trails are the only thing an OLED panel has to light up, and they are mid tones of the
accent rather than pale ones. What makes the original piece easy to live behind is that its
trails are only a little lighter than what they are painted on, and against black, pale
trails are anything but.

Flipping the device between light and dark repaints the piece. Picking any other set of
colors in the settings opts out of all of it; `Original` is the palette of the web page.

It goes the other way too: with any palette other than `Follow the system`, the piece hands
its colors to the system through `onComputeColors`, so a device set to take its theme from
the wallpaper themes itself to match the piece.

That is deliberately not done for `Follow the system`, and it is the one case where it
would be wrong: those colors are read from the system's accent, and giving them back would
close a loop — the accent would be derived from a piece that was painted from the accent.
Every other palette is chosen outright, so nothing it publishes can feed back into it.

## Battery

Everything about the design is about doing nothing most of the time.

- **A piece finishes.** After the configured painting time the render loop is torn down.
  There is then no thread, no frame and no timer: measured at zero CPU. That is the state
  the wallpaper is in for almost all of its life. The default is 45 seconds of painting and
  a new piece every 6 hours — about three minutes of work a day.
- **Nothing is painted while it cannot be seen.** `onVisibilityChanged` stops the loop, so
  no frame is ever drawn behind an app, a lock screen or a dark screen.
- **A finished piece is kept on disk** and comes back after a reboot, or after the system
  reclaims the service, for one file read instead of a repaint.
- **Nothing runs in the background.** No alarm, no job, no wake lock, no broadcast
  receiver. Whether a new piece is due is worked out when the wallpaper becomes visible,
  which is the only moment it could matter.
- **A frame allocates nothing.** Positions, segments and shadow centers all live in
  primitive arrays allocated once, so painting never wakes the garbage collector.
- **A frame is three draw calls.** One `drawLines` per color for every trail of the frame,
  and one `drawPoints` for every shadow, rather than a call per particle.
- **The field skips what it cannot show.** An attractor more than three standard deviations
  away is worth one ten-thousandth of its weight at its center, so it is dropped before the
  square root and the exponential are paid for. What is left of the exponential is a table
  lookup — the field is normalized right after, so the error cannot reach the screen.
- **Frames are decoupled from the painting.** The frame rate only decides how often the
  piece reaches the screen. A long gap between frames is cut into short steps rather than
  clamped, so a piece takes the same time to paint whether it is watched at 60 frames per
  second or at 4. Copying a screenful of pixels is what a frame costs most — three and a
  half times the simulation, measured — so showing fewer of them is close to a straight
  saving. It is also why battery saver simply stretches the frame interval: the piece still
  finishes on time, it is just watched less often.
- **The copy to the screen goes through the GPU**, via `lockHardwareCanvas`, and the piece
  is marked opaque so that it needs no blending.

## Settings

Reachable from the gear in the wallpaper picker, which is the only screen the app has.

| | |
|---|---|
| Colors | Follow the system, one of five fixed palettes, or a new random one per piece |
| Text | Particles flow around it. Empty by default |
| Attractors | How many, 3 to 80 |
| Particle density | Sparse, normal or dense |
| Painting time | How long a piece paints before it holds still |
| New piece | Every time the wallpaper is shown, hourly, every 6 hours, daily, or only on a double tap |
| Double tap to repaint | Double tap the home screen for a new piece |
| Frame rate | 15, 30 or 60 frames per second while a piece paints |
| Slow down in battery saver | Show fewer frames while the device is saving power |

<img src="docs/settings.jpg" width="320" alt="The settings screen">

## License

The port follows the license of the original project. `1CamBam_Stick_2.ttf` is the font
shipped with it.
