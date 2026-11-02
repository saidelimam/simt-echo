# ECHO design sources

`echo-step.svg` is the source of record for the **Echo Step** brand mark
(`docs/research/luminous-freeze.md` §2). It has two layers: `#field` (the
adaptive-icon background) and `#mark` (footprint + three contour echoes).
`echo-step-512.png` is a 512 px render for store and docs use.

The launcher layers are rendered from it, not drawn by hand:

| Output | How |
|---|---|
| `androidApp/src/main/res/mipmap-<d>/ic_launcher_foreground.png` | `#mark` only (delete the `#field` group), sizes mdpi 108, hdpi 162, xhdpi 216, xxhdpi 324, xxxhdpi 432 px |
| `androidApp/src/main/res/mipmap-<d>/ic_launcher_monochrome.png` | as foreground, with `url(#echoFade)` and `url(#sole)` replaced by `#FFFFFF` (themed icon reads alpha only) |
| `androidApp/src/main/res/drawable/ic_launcher_background.xml` | `#fieldGlow` stops copied into a vector radial gradient |
| `androidApp/src/main/res/drawable/splash_echo_step.xml` | foot path from `values/brand.xml`, animated echoes (freeze §2 "Animated forms") |

Render command used (librsvg):

```bash
rsvg-convert -w 432 -h 432 foreground.svg -o ic_launcher_foreground.png
```

`rsvg-convert` is a designer tool, not a build dependency: the PNGs are
committed and the Gradle build never renders them. The in-app mark
(`ui/brand/EchoMark.kt`) draws the same geometry live; change both together.
