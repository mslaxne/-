from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageStat


def adaptive_foreground(source: Image.Image) -> Image.Image:
    canvas_size = 432
    content_size = 280
    supersample = 4
    content = source.resize(
        (content_size * supersample, content_size * supersample),
        Image.Resampling.LANCZOS,
    ).convert("RGBA")
    mask = Image.new("L", content.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle(
        (0, 0, content.width - 1, content.height - 1),
        radius=int(content.width * 0.22),
        fill=255,
    )
    content.putalpha(mask)
    content = content.resize(
        (content_size, content_size),
        Image.Resampling.LANCZOS,
    )

    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    offset = (canvas_size - content_size) // 2
    canvas.alpha_composite(content, (offset, offset))
    return canvas


def render_icon(
    foreground: Image.Image,
    size: int,
    round_icon: bool,
    background: tuple[int, int, int],
) -> Image.Image:
    image = Image.new("RGBA", (size, size), (*background, 255))
    layer = foreground.resize((size, size), Image.Resampling.LANCZOS)
    image.alpha_composite(layer)
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    if round_icon:
        draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        draw.rounded_rectangle(
            (0, 0, size - 1, size - 1),
            radius=int(size * 0.22),
            fill=255,
        )
    image.putalpha(mask)
    return image


def detect_background(source: Image.Image) -> tuple[int, int, int]:
    width, height = source.size
    inset = max(8, min(width, height) // 20)
    strips = [
        source.crop((0, 0, width, inset)),
        source.crop((0, 0, inset, height)),
        source.crop((width - inset, 0, width, height)),
    ]
    means = [ImageStat.Stat(strip).mean for strip in strips]
    return tuple(
        round(sum(mean[channel] for mean in means) / len(means))
        for channel in range(3)
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("resource_root", type=Path)
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()

    source = Image.open(args.source).convert("RGB")
    if source.width != source.height:
        side = min(source.size)
        left = (source.width - side) // 2
        top = (source.height - side) // 2
        source = source.crop((left, top, left + side, top + side))

    art_directory = args.resource_root / "drawable-nodpi"
    art_directory.mkdir(parents=True, exist_ok=True)
    foreground = adaptive_foreground(source)
    background = detect_background(source)
    foreground.save(
        art_directory / "ic_launcher_art.png",
        optimize=True,
    )
    print("BACKGROUND=#{:02X}{:02X}{:02X}".format(*background))

    if args.preview:
        args.preview.parent.mkdir(parents=True, exist_ok=True)
        preview = Image.new("RGBA", (800, 400), (244, 246, 247, 255))
        preview.alpha_composite(
            render_icon(foreground, 320, True, background),
            (60, 40),
        )
        preview.alpha_composite(
            render_icon(foreground, 320, False, background),
            (420, 40),
        )
        preview.convert("RGB").save(args.preview, optimize=True)


if __name__ == "__main__":
    main()
