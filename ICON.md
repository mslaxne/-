# Launcher icon

The launcher icon uses the user-provided square image as its source artwork.
The app does not generate or repaint the subject.

## Android resources

- Adaptive icon: `mipmap-anydpi-v26/ic_launcher.xml`
- Adaptive round icon: `mipmap-anydpi-v26/ic_launcher_round.xml`
- Foreground artwork: `drawable-nodpi/ic_launcher_art.png`
- Solid background: `launcher_background`
- Themed monochrome layer: `drawable/ic_launcher_monochrome.xml`

The foreground places the source artwork inside the adaptive-icon safe area with
rounded corners. The launcher applies the device-specific circle, squircle or
rounded-square mask. The monochrome layer is a simple speech-bubble agent glyph
for Android themed icons.

## Regeneration

```powershell
python tools\make_launcher_icon.py `
  "<source-image>" `
  app\src\main\res --preview "<preview-image>"
```

The source JPEG is not copied into the repository. Only the derived Android
foreground PNG is stored.
