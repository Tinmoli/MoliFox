#!/usr/bin/env python3
# 负责从狐狸原图生成启动器品牌图标：去背景 -> 透明母版 -> 多尺寸 PNG / ICO。
#
# 用法（项目根目录，需 Pillow）：
#   <python> tools/generate_app_icons.py <狐狸原图|透明母版> [输出目录]
#   <python> tools/generate_app_icons.py assets/brand/fox-mark.png --check   # 只校验
# 输出目录默认 desktop-app/src/main/resources/icons/app/。
#
# 原图是「深色背景 + 狐狸」的正方形图：按背景色阈值取前景，再从行/列跨度补满外轮廓，
# 使与背景同色的内部分隔线留在狐狸内部；外圈 2px 抗锯齿带丢弃，背景写全透明。
# 抠图结果裁到狐狸外接矩形，四周补等量留白（PAD），存为 assets/brand/fox-mark.png。
# 已经是透明母版的输入会跳过抠图，直接缩放。

import struct
import sys
from io import BytesIO
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SIZES = (16, 24, 32, 48, 64, 128, 256)
# ICO 内 16~128 用 32 位 DIB 条目；256 用 PNG 条目（Vista 起支持，体积更小）。
DIB_SIZES = (16, 24, 32, 48, 64, 128)
MASTER = Path("assets/brand/fox-mark.png")
BG_TOL = 14  # 与背景色的最大通道差，超过即视为前景
PAD = 0.05  # 裁切后四周留白（占较长边）



def shift(array: np.ndarray, dy: int, dx: int, fill=0):
    """把二维/三维数组整体平移，越界处补 fill。"""
    height, width = array.shape[:2]
    out = np.full_like(array, fill)
    src_y = slice(max(dy, 0), height + min(dy, 0))
    dst_y = slice(max(-dy, 0), height + min(-dy, 0))
    src_x = slice(max(dx, 0), width + min(dx, 0))
    dst_x = slice(max(-dx, 0), width + min(-dx, 0))
    out[dst_y, dst_x] = array[src_y, src_x]
    return out


def erode(mask: np.ndarray, radius: int) -> np.ndarray:
    """4 邻域腐蚀，去掉边缘 radius 像素。"""
    out = mask
    for _ in range(radius):
        out = (
            out
            & shift(out, 1, 0, False)
            & shift(out, -1, 0, False)
            & shift(out, 0, 1, False)
            & shift(out, 0, -1, False)
        )
    return out


def background_probe(rgb: np.ndarray):
    """返回背景中位色、逐像素色差、以及「与画布外连通的背景」掩码。"""
    border = np.concatenate(
        [rgb[:4].reshape(-1, 3), rgb[-4:].reshape(-1, 3), rgb[:, :4].reshape(-1, 3), rgb[:, -4:].reshape(-1, 3)]
    )
    background = np.median(border, axis=0)
    distance = np.abs(rgb - background).max(axis=2)
    probe = Image.fromarray(np.where(distance > BG_TOL, 0, 255).astype(np.uint8)).copy()
    ImageDraw.floodfill(probe, (0, 0), 128)
    return background, distance, np.asarray(probe) == 128


def fill_silhouette(foreground: np.ndarray) -> np.ndarray:
    """按行/列跨度补满外轮廓：与背景同色的内部分隔线因此留在狐狸内部。"""
    height, width = foreground.shape
    rows = np.zeros_like(foreground)
    columns = np.zeros_like(foreground)
    for y in range(height):
        xs = np.nonzero(foreground[y])[0]
        if xs.size:
            rows[y, xs.min() : xs.max() + 1] = True
    for x in range(width):
        ys = np.nonzero(foreground[:, x])[0]
        if ys.size:
            columns[ys.min() : ys.max() + 1, x] = True
    return rows & columns


def cut_out(image: Image.Image) -> Image.Image:
    """去掉背景，返回裁到狐狸外接框、四周等距留白的透明方图。"""
    rgb = np.asarray(image.convert("RGB")).astype(np.float32)
    background, distance, outside = background_probe(rgb.astype(np.int16))
    foreground = distance > BG_TOL
    # 狐狸整体轮廓：行/列跨度补满，并保留所有封闭区域（内部深色分隔线随之不透明）。
    solid = fill_silhouette(foreground) | ~outside
    if not solid.any():
        raise SystemExit("抠图失败：未找到前景，请检查输入是否为深色背景的狐狸原图")

    # 把狐狸颜色向背景方向延伸若干像素：外圈拿去当「透明区的填充色」，
    # 缩小后不会把深色背景混进边缘。
    known = erode(solid, 3)
    colour = rgb.copy()
    for _ in range(8):
        missing = ~known
        if not missing.any():
            break
        for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
            available = shift(known, dy, dx, False) & missing
            if available.any():
                colour[available] = shift(colour, dy, dx, 0.0)[available]
                known |= available

    # 边缘 2 像素是与背景混色的抗锯齿带，直接舍弃，换成整像素的狐狸本色；
    # 再给 alpha 一点点羽化，保证 512 这类大图边缘不发毛。
    inner = erode(solid, 2)
    alpha = Image.fromarray(np.where(inner, 255, 0).astype(np.uint8)).filter(
        ImageFilter.GaussianBlur(0.5)
    )
    alpha_array = np.asarray(alpha).astype(np.float32) / 255.0
    out = np.empty((*alpha_array.shape, 4), np.uint8)
    out[..., :3] = np.clip(np.where(inner[..., None], rgb, colour), 0.0, 255.0).astype(np.uint8)
    out[..., 3] = np.asarray(alpha)

    # 裁到狐狸外接框，再补等边距使其成为正方形；画布透明。
    ys, xs = np.nonzero(out[..., 3] > 6)
    crop = out[ys.min() : ys.max() + 1, xs.min() : xs.max() + 1]
    height, width = crop.shape[:2]
    side = int(round(max(height, width) * (1 + 2 * PAD)))
    canvas = np.zeros((side, side, 4), np.uint8)
    top, left = (side - height) // 2, (side - width) // 2
    canvas[top : top + height, left : left + width] = crop
    return Image.fromarray(canvas, "RGBA")


def resize_master(master: Image.Image, size: int) -> Image.Image:
    """预乘 alpha 后 Lanczos 缩放，再解预乘；避免透明区颜色渗进边缘。"""
    rgba = np.asarray(master).astype(np.float32) / 255.0
    alpha = rgba[..., 3:4]
    premultiplied = np.concatenate([rgba[..., :3] * alpha, alpha], axis=2)
    channels = [
        Image.fromarray(premultiplied[..., i].astype(np.float32), mode="F").resize(
            (size, size), Image.LANCZOS
        )
        for i in range(4)
    ]
    scaled = np.stack([np.asarray(channel, dtype=np.float32) for channel in channels], axis=2)
    scaled_alpha = np.clip(scaled[..., 3:4], 0.0, 1.0)
    scaled_rgb = np.clip(scaled[..., :3] / np.maximum(scaled_alpha, 1e-6), 0.0, 1.0)
    combined = np.concatenate([scaled_rgb, scaled_alpha], axis=2)
    return Image.fromarray((combined * 255.0 + 0.5).astype(np.uint8), "RGBA")


def dib_entry(image: Image.Image) -> bytes:
    """32 位 DIB：BITMAPINFOHEADER + BGRA 倒序行 + 按 alpha 生成的 AND 掩码。"""
    width, height = image.size
    rgba = np.asarray(image)
    rows = []
    for y in range(height - 1, -1, -1):
        pixels = rgba[y].astype(np.uint16)
        bgra = np.stack([pixels[:, 2], pixels[:, 1], pixels[:, 0], pixels[:, 3]], axis=1)
        rows.append(bgra.astype(np.uint8).tobytes())
    xor = b"".join(rows)
    stride = ((width + 31) // 32) * 4
    mask = bytearray(stride * height)
    for y in range(height):
        for x in range(width):
            if rgba[y, x, 3] < 128:  # 透明像素在 AND 掩码里置 1
                mask[(height - 1 - y) * stride + (x // 8)] |= 0x80 >> (x % 8)
    header = struct.pack(
        "<IiiHHIIiiII",
        40,  # biSize
        width,
        height * 2,  # XOR 图 + AND 掩码
        1,  # biPlanes
        32,  # biBitCount
        0,  # biCompression = BI_RGB
        width * height * 4,  # biSizeImage = XOR 图大小
        0, 0, 0, 0,
    )
    return header + xor + bytes(mask)


def write_ico(images: dict, target: Path) -> None:
    entries = []
    for size in SIZES:
        image = images[size]
        if size in DIB_SIZES:
            data, bit_count = dib_entry(image), 32
        else:
            buffer = BytesIO()
            image.save(buffer, "PNG", optimize=True)
            data, bit_count = buffer.getvalue(), 32
        entries.append((size, bit_count, data))
    offset = 6 + 16 * len(entries)
    out = bytearray(struct.pack("<HHH", 0, 1, len(entries)))
    for size, bit_count, data in entries:
        out += struct.pack(
            "<BBBBHHII",
            0 if size >= 256 else size,
            0 if size >= 256 else size,
            0,  # 调色板颜色数
            0,  # 保留
            1,  # 平面数
            bit_count,
            len(data),
            offset,
        )
        offset += len(data)
    for _, _, data in entries:
        out += data
    target.write_bytes(bytes(out))


def verify(target: Path, master: Image.Image) -> None:
    ico = Image.open(target / "icon.ico")
    sizes = sorted(ico.info["sizes"])
    if sizes != [(size, size) for size in SIZES]:
        raise SystemExit(f"icon.ico 尺寸不符：{sizes}")
    for size in SIZES:
        ico.size = (size, size)
        alpha = np.asarray(ico.convert("RGBA"))[..., 3]
        if alpha.min() != 0 or alpha.max() != 255:
            raise SystemExit(f"icon.ico 的 {size} 帧缺少透明/不透明像素：{alpha.min()}~{alpha.max()}")
    for name, size in (("icon.png", 256),):
        with Image.open(target / name) as image:
            if image.size != (size, size):
                raise SystemExit(f"{name} 应为 {size}×{size}，实为 {image.size}")
            expected = np.asarray(resize_master(master, size))
            if not np.array_equal(np.asarray(image.convert("RGBA")), expected):
                raise SystemExit(f"{name} 与母版缩放结果不一致")
            if np.asarray(image)[..., 3].min() != 0:
                raise SystemExit(f"{name} 背景不是透明的")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    arguments = [value for value in sys.argv[1:] if not value.startswith("--")]
    check_only = "--check" in sys.argv[1:]
    if not arguments:
        print(__doc__)
        return 2
    source = Path(arguments[0])
    output = Path(arguments[1]) if len(arguments) > 1 else Path(
        "desktop-app/src/main/resources/icons/app"
    )
    base = Image.open(source).convert("RGBA")
    if base.width != base.height:
        raise SystemExit(f"源图必须是正方形：{base.size}")
    opaque = np.asarray(base)[..., 3].min() >= 250
    if opaque and check_only:
        raise SystemExit("--check 需要透明母版，不能直接用带背景的原图")
    master = cut_out(base) if opaque else base
    if opaque:
        MASTER.parent.mkdir(parents=True, exist_ok=True)
        master.save(MASTER, "PNG", optimize=True)
        print(f"透明母版 {MASTER}  {MASTER.stat().st_size} bytes  {master.size[0]}×{master.size[1]}")

    if not check_only:
        output.mkdir(parents=True, exist_ok=True)
        images = {size: resize_master(master, size) for size in SIZES}
        images[256].save(output / "icon.png", "PNG", optimize=True)
        write_ico(images, output / "icon.ico")
    verify(output, master)
    for name in ("icon.png", "icon.ico"):
        print(f"{output / name}  {(output / name).stat().st_size} bytes")
    print("校验通过：尺寸、透明通道与母版一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())