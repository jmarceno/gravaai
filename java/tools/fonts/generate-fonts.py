#!/usr/bin/env python3
"""DM Sans static instances for JavaFX (plan §5.12).

JavaFX does not apply variable-font axes, so the app needs one static TTF per weight/style.
Run once (fonttools is installed in a scratch-disk venv, not a Maven dependency):

    /media/jmarceno/scratch/gravaai-fonttools/venv/bin/python java/tools/fonts/generate-fonts.py

Input:  src/assets/fonts/dm-sans/DMSans-Variable.ttf and DMSans-Italic-Variable.ttf
Output: java/gravaai-ui/src/main/resources/kit/fonts/DMSans-<Style>.ttf (+ OFL.txt copy)

The opsz (optical size) axis is pinned to its default (9pt): the UI runs at 12-14px, where the
small optical size is the correct one. Weight is pinned per file.
"""

import shutil
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / "src" / "assets" / "fonts" / "dm-sans"
OUT = REPO / "java" / "gravaai-ui" / "src" / "main" / "resources" / "kit" / "fonts"

WEIGHTS = [(400, "Regular"), (500, "Medium"), (600, "SemiBold"), (700, "Bold")]
FAMILY = "DM Sans"


def rename(font, style, weight):
    """Give JavaFX an unambiguous family/style pair (it maps style names to FontWeight)."""
    full = FAMILY + " " + style
    ps = "DMSans-" + style.replace(" ", "")
    names = {
        1: FAMILY,
        2: style,
        3: "1.000;GRV;" + ps,
        4: full,
        6: ps,
        16: FAMILY,
        17: style,
    }
    name_table = font["name"]
    for record in list(name_table.names):
        if record.nameID in names:
            record.string = names[record.nameID]
    # OS/2 weight class and bold/italic flags.
    os2 = font["OS/2"]
    os2.usWeightClass = weight
    bold = weight >= 700
    italic = style.endswith("Italic")
    regular = weight == 400 and not italic
    if bold:
        os2.fsSelection |= 1 << 5
        font["head"].macStyle |= 1
    else:
        os2.fsSelection &= ~(1 << 5)
        font["head"].macStyle &= ~1
    if italic:
        os2.fsSelection |= 1
        font["head"].macStyle |= 2
    else:
        os2.fsSelection &= ~1
        font["head"].macStyle &= ~2
    if regular:
        os2.fsSelection |= 1 << 6
    else:
        os2.fsSelection &= ~(1 << 6)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sources = [
        (SRC / "DMSans-Variable.ttf", "", "Italic"),
        (SRC / "DMSans-Italic-Variable.ttf", "Italic", "Italic"),
    ]
    # The file name suffix above is the style suffix; the second tuple entry is unused guard.
    for source, _unused, _ in sources:
        pass

    for weight, weight_name in WEIGHTS:
        for source, style_suffix in [
            (SRC / "DMSans-Variable.ttf", "Regular" if weight == 400 else weight_name),
            (SRC / "DMSans-Italic-Variable.ttf", ("Italic" if weight == 400 else weight_name + "Italic")),
        ]:
            font = TTFont(source)
            axes = {axis.axisTag: axis.defaultValue for axis in font["fvar"].axes}
            instances = dict(axes)
            instances["wght"] = weight
            instance = instantiateVariableFont(font, instances, updateFontNames=False)
            style = style_suffix
            rename(instance, style, weight)
            out = OUT / ("DMSans-" + style + ".ttf")
            instance.save(out)
            print("wrote", out.relative_to(REPO))

    shutil.copyfile(SRC / "OFL.txt", OUT / "OFL.txt")
    print("wrote", (OUT / "OFL.txt").relative_to(REPO))


if __name__ == "__main__":
    main()
