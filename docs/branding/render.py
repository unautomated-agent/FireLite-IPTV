"""Render launcher PNGs directly from the Android vector masters (CairoSVG 2.9.1)."""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

import cairosvg

ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"
SVG = "http://www.w3.org/2000/svg"
RES = Path(__file__).resolve().parents[2] / "app/src/main/res"
ET.register_namespace("", SVG)


def attr(node, name, default=None):
    return node.get(ANDROID + name, default)


def element(parent, name, **attributes):
    return ET.SubElement(parent, "{" + SVG + "}" + name, attributes)


def paths(vector, parent, defs):
    for node in vector:
        if node.tag == "group":
            group = element(parent, "g", transform=(
                f"translate({attr(node, 'translateX', '0')} {attr(node, 'translateY', '0')}) "
                f"scale({attr(node, 'scaleX', '1')} {attr(node, 'scaleY', '1')})"))
            paths(node, group, defs)
        elif node.tag == "path":
            fill = attr(node, "fillColor", "#000000")
            gradient = node.find(AAPT + "attr/gradient")
            if gradient is not None:
                if attr(gradient, "type") != "linear":
                    raise ValueError("Only linear gradients are supported")
                name = f"gradient{len(defs)}"
                output = element(defs, "linearGradient", id=name, gradientUnits="userSpaceOnUse",
                                 x1=attr(gradient, "startX"), y1=attr(gradient, "startY"),
                                 x2=attr(gradient, "endX"), y2=attr(gradient, "endY"))
                for stop in gradient:
                    element(output, "stop", offset=attr(stop, "offset"),
                            **{"stop-color": attr(stop, "color")})
                fill = f"url(#{name})"
            element(parent, "path", d=attr(node, "pathData"), fill=fill,
                    **{"fill-rule": "evenodd" if attr(node, "fillType") == "evenOdd" else "nonzero"})
        else:
            raise ValueError(f"Unsupported vector node: {node.tag}")


def artwork(names, icon=False):
    svg = ET.Element("{" + SVG + "}svg", {
        "viewBox": "18 18 72 72" if icon else "0 0 320 180",
        "width": "72" if icon else "320", "height": "72" if icon else "180",
    })
    defs = element(svg, "defs")
    group = element(svg, "g")
    if icon:
        clip = element(defs, "clipPath", id="mask")
        element(clip, "rect", x="18", y="18", width="72", height="72", rx="16")
        group.set("clip-path", "url(#mask)")
    for name in names:
        master = Path(__file__).with_name("banner.xml") if name == "banner" else RES / "drawable" / f"{name}.xml"
        paths(ET.parse(master).getroot(), group, defs)
    return ET.tostring(svg)


def render(svg, destination, width, height):
    destination.parent.mkdir(parents=True, exist_ok=True)
    cairosvg.svg2png(bytestring=svg, write_to=str(destination),
                    output_width=width, output_height=height)
    print(f"{destination}: {width}x{height}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previews", type=Path)
    args = parser.parse_args()
    icon = artwork(["ic_launcher_background", "ic_launcher_foreground"], icon=True)
    banner = artwork(["banner"])
    for density, size in [("ldpi", 36), ("mdpi", 48), ("hdpi", 72),
                          ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        render(icon, RES / f"mipmap-{density}/ic_launcher.png", size, size)
    for density, width, height in [("xhdpi", 320, 180), ("xxhdpi", 480, 270), ("xxxhdpi", 640, 360)]:
        render(banner, RES / f"drawable-{density}/banner.png", width, height)
    render(banner, RES / "drawable/banner.png", 160, 90)
    render(banner, RES / "drawable-nodpi/fire_tv_launcher.png", 1280, 720)
    if args.previews:
        render(icon, args.previews / "icon-512.png", 512, 512)
        render(banner, args.previews / "banner-1280x720.png", 1280, 720)


if __name__ == "__main__":
    main()
