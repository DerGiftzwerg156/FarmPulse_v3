#!/usr/bin/env python3
"""Generates every FarmPulse icon from one design, without external dependencies.

  mod/FS25_RPSim/icon_RPSim.dds        512x512 BC1/DXT1 mod icon
  frontend/public/favicon.ico          16/32/48/256 px (PNG entries) - web app, start script shortcut
  frontend/public/favicon.svg          vector favicon for modern browsers
  frontend/public/apple-touch-icon.png 180x180 home-screen icon

Design (FarmPulse tokens): dark background #0B0F0D, accent #38B000 frame and a bold "F" with a pulse line.
Every shape is aligned to the 4x4 block grid, so each BC1 block holds at most two colours and is encoded exactly.
"""
import math
import struct
import zlib
from pathlib import Path

SIZE = 512
BG = (0x0B, 0x0F, 0x0D)
FG = (0x38, 0xB0, 0x00)
ROOT = Path(__file__).resolve().parents[2]


def rgb565(c):
    r, g, b = c
    return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)


def shapes():
    """Rectangles (x0, y0, x1, y1) in pixels, multiples of 4."""
    r = []
    # frame
    t = 24
    r += [(32, 32, 480, 32 + t), (32, 480 - t, 480, 480), (32, 32, 32 + t, 480), (480 - t, 32, 480, 480)]
    # letter F
    r += [(152, 112, 216, 400), (152, 112, 368, 172), (152, 232, 328, 288)]
    # pulse line under the letter
    r += [(96, 424, 272, 436), (272, 396, 284, 436), (284, 396, 300, 408), (300, 396, 312, 448),
          (312, 436, 332, 448), (332, 424, 344, 448), (344, 424, 416, 436)]
    return r


def mask():
    """SIZE x SIZE coverage mask (1 = FG) as a list of rows."""
    rows = [bytearray(SIZE) for _ in range(SIZE)]
    for x0, y0, x1, y1 in shapes():
        for y in range(y0, y1):
            rows[y][x0:x1] = b'\x01' * (x1 - x0)
    return rows


# ---------------------------------------------------------------- DDS (mod)
def build_dds(rows):
    for rect in shapes():
        assert all(v % 4 == 0 for v in rect), rect
    c0, c1 = rgb565(FG), rgb565(BG)
    assert c0 > c1  # 4-colour mode: index 0 = c0 (FG), index 1 = c1 (BG)
    blocks = bytearray()
    for by in range(0, SIZE, 4):
        for bx in range(0, SIZE, 4):
            bits = 0
            for py in range(4):
                for px in range(4):
                    idx = 0 if rows[by + py][bx + px] else 1
                    bits |= idx << (2 * (py * 4 + px))
            blocks += struct.pack('<HHI', c0, c1, bits)
    header = struct.pack(
        '<4sIIIIIII44s' 'II4sIIIII' 'IIIII',
        b'DDS ', 124, 0x1 | 0x2 | 0x4 | 0x1000 | 0x80000, SIZE, SIZE, len(blocks), 0, 0, b'\0' * 44,
        32, 0x4, b'DXT1', 0, 0, 0, 0, 0,          # DDS_PIXELFORMAT: size, DDPF_FOURCC, fourCC, unused
        0x1000, 0, 0, 0, 0)                        # caps: DDSCAPS_TEXTURE
    assert len(header) == 128
    return header + bytes(blocks)


# ---------------------------------------------------------------- PNG / ICO (web, start script)
def box_weights(n):
    """For each of n output pixels: [(source index, weight)] of the SIZE source pixels it covers (area filter)."""
    scale = SIZE / n
    out = []
    for i in range(n):
        a, b = i * scale, (i + 1) * scale
        out.append([(s, min(b, s + 1) - max(a, s)) for s in range(int(a), min(SIZE, math.ceil(b)))
                    if min(b, s + 1) > max(a, s)])
    return out


def render(rows, n):
    """n x n RGB rows, FG coverage blended over BG (anti-aliased downscale)."""
    w = box_weights(n)
    horiz = [[sum(row[s] * f for s, f in cols) for cols in w] for row in rows]
    area = (SIZE / n) ** 2
    img = []
    for srcs in w:
        line = bytearray()
        for x in range(n):
            cov = sum(horiz[s][x] * f for s, f in srcs) / area
            line += bytes(round(bg + (fg - bg) * cov) for bg, fg in zip(BG, FG))
        img.append(line)
    return img


def png(img):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    n = len(img)
    raw = b''.join(b'\0' + bytes(line) for line in img)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', n, n, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def ico(rows, sizes=(16, 32, 48, 256)):
    images = [png(render(rows, n)) for n in sizes]
    out = struct.pack('<HHH', 0, 1, len(images))
    offset = 6 + 16 * len(images)
    for n, data in zip(sizes, images):
        out += struct.pack('<BBBBHHII', n % 256, n % 256, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    return out + b''.join(images)


# ---------------------------------------------------------------- SVG (web)
def svg():
    hexc = '#%02X%02X%02X'
    path = ''.join(f'M{x0} {y0}h{x1 - x0}v{y1 - y0}h{x0 - x1}z' for x0, y0, x1, y1 in shapes())
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {SIZE} {SIZE}">'
            f'<rect width="{SIZE}" height="{SIZE}" fill="{hexc % BG}"/>'
            f'<path fill="{hexc % FG}" d="{path}"/></svg>\n').encode()


if __name__ == '__main__':
    rows = mask()
    outputs = {
        'mod/FS25_RPSim/icon_RPSim.dds': build_dds(rows),
        'frontend/public/favicon.ico': ico(rows),
        'frontend/public/favicon.svg': svg(),
        'frontend/public/apple-touch-icon.png': png(render(rows, 180)),
    }
    for rel, data in outputs.items():
        (ROOT / rel).write_bytes(data)
        print(f'{rel} ({len(data)} bytes)')
