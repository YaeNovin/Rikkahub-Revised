# Synthetic color fixtures

The four PNGs are generated from explicit RGB palettes and mathematical curves
by `scripts/generate_color_fixtures.py`. They contain no user images, screenshots
or third-party wallpaper artwork. Rebuild from the repository root with:

```text
python scripts/generate_color_fixtures.py
```

The tests exercise representative hue support, chroma, quantization and portrait
cropping. These synthetic cases do not replace private device-image evaluation.
