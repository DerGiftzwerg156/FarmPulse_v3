#!/usr/bin/env python3
"""Generates mod/FS25_RPSim/icon_RPSim.dds (512x512, BC1/DXT1) without external dependencies.

Design (FarmPulse tokens): dark background #0B0F0D, accent #38B000 frame and a bold "F" with a pulse line.
Every shape is aligned to the 4x4 block grid, so each BC1 block holds at most two colours and is encoded exactly.
"""
import struct
import sys
from pathlib import Path

SIZE = 512
BG = (0x0B, 0x0F, 0x0D)
FG = (0x38, 0xB0, 0x00)


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


def pixel_is_fg(x, y, rects):
    return any(x0 <= x < x1 and y0 <= y < y1 for x0, y0, x1, y1 in rects)


def build():
    rects = shapes()
    for rect in rects:
        assert all(v % 4 == 0 for v in rect), rect
    c0, c1 = rgb565(FG), rgb565(BG)
    assert c0 > c1  # 4-colour mode: index 0 = c0 (FG), index 1 = c1 (BG)
    blocks = bytearray()
    for by in range(0, SIZE, 4):
        for bx in range(0, SIZE, 4):
            bits = 0
            for py in range(4):
                for px in range(4):
                    idx = 0 if pixel_is_fg(bx + px, by + py, rects) else 1
                    bits |= idx << (2 * (py * 4 + px))
            blocks += struct.pack('<HHI', c0, c1, bits)
    header = struct.pack(
        '<4sIIIIIII44s' 'II4sIIIII' 'IIIII',
        b'DDS ', 124, 0x1 | 0x2 | 0x4 | 0x1000 | 0x80000, SIZE, SIZE, len(blocks), 0, 0, b'\0' * 44,
        32, 0x4, b'DXT1', 0, 0, 0, 0, 0,          # DDS_PIXELFORMAT: size, DDPF_FOURCC, fourCC, unused
        0x1000, 0, 0, 0, 0)                        # caps: DDSCAPS_TEXTURE
    assert len(header) == 128
    return header + bytes(blocks)


if __name__ == '__main__':
    out = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[2] / 'mod/FS25_RPSim/icon_RPSim.dds')
    data = build()
    out.write_bytes(data)
    print(f'{out} ({len(data)} bytes)')
