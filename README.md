# PNG to G-code JavaFX GUI

This version is fully GUI-driven for normal use.

## What the user selects
- Input PNG
- Output folder

## What the app auto-finds
- `tools/CircularScribbleArt.exe`
- `tools/CGV_LAB_to_SVG_GCODE.py`
- `python.exe` from a common Windows Python install or PATH

## One-time setup
1. Put `CircularScribbleArt.exe` into the project's `tools` folder.
2. Keep `CGV_LAB_to_SVG_GCODE.py` in the `tools` folder.
3. Make sure Python is installed on Windows.

## Run in development
```bash
mvn javafx:run
```

## Package later
You can still use `jpackage` later, but this project is now set up so the GUI only asks for PNG and output paths.
