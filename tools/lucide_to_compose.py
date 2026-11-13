#!/usr/bin/env python3
"""Converts Lucide SVG icons into client/shared/ui/.../design/RachaIcons.kt (Compose ImageVectors).

usage: python3 -I tools/lucide_to_compose.py <dir with lucide .svg files> <output .kt>
Download icons from https://unpkg.com/lucide-static@<version>/icons/<name>.svg (ISC license).
"""
import os
import re
import sys
import xml.etree.ElementTree as ET


def fmt(x):
    s = ('%.3f' % x).rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s


def circle(cx, cy, rx, ry=None):
    ry = rx if ry is None else ry
    return f"M{fmt(cx - rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2 * rx)} 0a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2 * rx)} 0z"


def rect(x, y, w, h, rx, ry):
    if rx == 0 and ry == 0:
        return f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z"
    rx, ry = min(rx, w / 2), min(ry, h / 2)
    return (f"M{fmt(x + rx)} {fmt(y)}h{fmt(w - 2 * rx)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(ry)}v{fmt(h - 2 * ry)}"
            f"a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(ry)}h{fmt(-(w - 2 * rx))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(-ry)}"
            f"v{fmt(-(h - 2 * ry))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(-ry)}z")


def points(p, close):
    nums = [float(n) for n in re.findall(r'-?\d*\.?\d+', p)]
    d = 'M' + ' L'.join(f"{fmt(a)} {fmt(b)}" for a, b in zip(nums[0::2], nums[1::2]))
    return d + ('z' if close else '')


def paths_of(svg):
    out = []
    for el in ET.parse(svg).getroot().iter():
        tag, a = el.tag.split('}')[-1], el.attrib
        f = lambda k, d=None: float(a.get(k, d))
        if tag == 'path':
            out.append(a['d'])
        elif tag == 'circle':
            out.append(circle(f('cx'), f('cy'), f('r')))
        elif tag == 'ellipse':
            out.append(circle(f('cx'), f('cy'), f('rx'), f('ry')))
        elif tag == 'rect':
            out.append(rect(f('x', 0), f('y', 0), f('width'), f('height'), f('rx', a.get('ry', 0)), f('ry', a.get('rx', 0))))
        elif tag == 'line':
            out.append(f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}")
        elif tag in ('polyline', 'polygon'):
            out.append(points(a['points'], tag == 'polygon'))
        elif tag != 'svg':
            raise SystemExit(f'unsupported <{tag}> in {svg}')
    return out


def main(src, out):
    icons = sorted(n[:-4] for n in os.listdir(src) if n.endswith('.svg'))
    body = []
    for name in icons:
        ident = ''.join(p.capitalize() for p in name.split('-'))
        joined = ', '.join(f'"{p}"' for p in paths_of(os.path.join(src, name + '.svg')))
        body += [f'    /** lucide:{name} */', f'    val {ident}: ImageVector by lazy {{ icon("{name}", {joined}) }}', '']
    header = [
        'package io.github.roberbarras.racha.ui.design', '',
        'import androidx.compose.ui.graphics.Color', 'import androidx.compose.ui.graphics.SolidColor',
        'import androidx.compose.ui.graphics.StrokeCap', 'import androidx.compose.ui.graphics.StrokeJoin',
        'import androidx.compose.ui.graphics.vector.ImageVector', 'import androidx.compose.ui.graphics.vector.addPathNodes',
        'import androidx.compose.ui.unit.dp', '',
        '// Generated from Lucide (ISC, see licenses/Lucide-ISC.txt) by tools/lucide_to_compose.py. Stroke icons on a',
        '// 24 dp grid, tinted at draw time. Regenerate instead of editing by hand.', '',
        'object RachaIcons {',
    ]
    footer = [
        '    private fun icon(name: String, vararg paths: String): ImageVector =',
        '        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {',
        '            paths.forEach { data ->',
        '                addPath(',
        '                    pathData = addPathNodes(data),',
        '                    stroke = SolidColor(Color.Black),',
        '                    strokeLineWidth = 2f,',
        '                    strokeLineCap = StrokeCap.Round,',
        '                    strokeLineJoin = StrokeJoin.Round,',
        '                )',
        '            }',
        '        }.build()', '}', '',
    ]
    with open(out, 'w') as fh:
        fh.write('\n'.join(header + body + footer))


if __name__ == '__main__':
    main(*sys.argv[1:3])
