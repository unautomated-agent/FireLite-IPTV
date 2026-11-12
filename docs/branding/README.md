# FireLite launcher artwork

The icon vector drawables in `app/src/main/res/drawable` are the masters:
`ic_launcher_background`, `ic_launcher_foreground`, and `ic_launcher_monochrome`.
The banner master is `docs/branding/banner.xml`; it is deliberately outside the packaged resources.
The rounded widescreen TV has a small pedestal and three rising signal bars, with a cyan-to-periwinkle
frame over a deep blue-to-violet background. Its silhouette fits inside the adaptive icon's central
66-dp safe circle on the 108-dp canvas, including the monochrome layer. Bold bezels and simple bars
keep the mark legible in the smallest legacy icon and in the 320×180 TV banner.
The banner wordmark is outlined DejaVu Sans Bold; no font is needed at runtime.
The cyan `Lite` uses the UI accent, #4FC3F7.

The adaptive launcher resource is in `mipmap-anydpi-v26`. Legacy icons use a rounded
72-dp crop of the same vector layers, rendered separately at 36/48/72/96/144/192 px.
The banner has a 160×90 PNG fallback and 320×180, 480×270, and 640×360 PNGs
at xhdpi/xxhdpi/xxxhdpi. No raster is enlarged to produce another density.
Both application and launcher activity explicitly reference the raster TV banner.
Their icon reference (including roundIcon) resolves through `launcher_icon`: on television
configurations this is the opaque 1280×720 `drawable-nodpi/fire_tv_launcher.png`, so Fire OS
launchers loading the icon instead of the Leanback banner also receive widescreen artwork.
Other configurations retain the existing square adaptive icon and legacy fallbacks.

Amazon requires a separate 1280×720 Fire TV icon for Appstore/Live App Testing:
https://developer.amazon.com/docs/app-testing/live-app-testing-getting-started.html
These store assets are distinct from sideloaded APK resources. Amazon's Fire TV display
configuration documents `television` UI mode and xhdpi/tvdpi densities:
https://developer.amazon.com/docs/fire-tv/display-and-layout.html
Android's 320×180 xhdpi banner guidance:
https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines
Some Fire OS versions have reported sideloaded mipmap-icon handling problems:
https://www.aftvnews.com/how-to-fix-missing-or-broken-icons-for-sideloaded-apps-on-amazon-fire-tv/
APK artwork cannot guarantee every Fire OS home-screen presentation or invalidate launcher caches;
verify the installed update on the affected physical device.

Regenerate PNG resources and optional review previews using Python 3 and CairoSVG 2.9.1:

```sh
python3 -m venv .venv-artwork
.venv-artwork/bin/pip install CairoSVG==2.9.1
.venv-artwork/bin/python docs/branding/render.py --previews /path/to/previews
```

The renderer translates the subset of Android vector paths/groups/linear gradients
used here into SVG, then rasterizes each target size directly. Preview filenames are
`icon-512.png` and `banner-1280x720.png`; previews are not packaged in the APK.
