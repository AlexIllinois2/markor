#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Markor 圆润图标集生成器。
同一份几何同时产出：
  1) preview.html —— 用无头浏览器截图自查
  2) res/drawable/*.xml —— Android VectorDrawable

语法（统一，避免"有的尖有的圆"）：
  - 24x24 网格，内容控制在 3..21，给描边留余量
  - 线性图标：stroke 1.9，圆头圆角（round cap/join）
  - 面性图标：几何带 1.1~1.3 的圆角（fillet），没有一处直角
"""
import math
import os
import sys

STROKE = 1.9
FILLET = 1.15

# ---------------------------------------------------------------- 几何工具


def f(v):
    """格式化数字：去掉多余小数，保证路径简短"""
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def pt(p):
    return "%s,%s" % (f(p[0]), f(p[1]))


def line(x1, y1, x2, y2):
    return "M%s L%s" % (pt((x1, y1)), pt((x2, y2)))


def poly(pts, close=False):
    d = "M" + " L".join(pt(p) for p in pts)
    return d + (" Z" if close else "")


def circle(cx, cy, r):
    return ("M%s a%s,%s 0 1 0 %s,0 a%s,%s 0 1 0 -%s,0 Z"
            % (pt((cx - r, cy)), f(r), f(r), f(2 * r), f(r), f(r), f(2 * r)))


def dot(cx, cy, r):
    """实心小圆点（用于列表圆点、省略号）"""
    return circle(cx, cy, r)


def rrect(x, y, w, h, r):
    r = min(r, w / 2.0, h / 2.0)
    return ("M%s h%s a%s,%s 0 0 1 %s,%s v%s a%s,%s 0 0 1 -%s,%s "
            "h-%s a%s,%s 0 0 1 -%s,-%s v-%s a%s,%s 0 0 1 %s,-%s Z"
            % (pt((x + r, y)), f(w - 2 * r), f(r), f(r), f(r), f(r),
               f(h - 2 * r), f(r), f(r), f(r), f(r),
               f(w - 2 * r), f(r), f(r), f(r), f(r),
               f(h - 2 * r), f(r), f(r), f(r), f(r)))


def arc(cx, cy, r, a0, a1):
    """圆弧（度，顺时针为正）"""
    a0r, a1r = math.radians(a0), math.radians(a1)
    p0 = (cx + r * math.cos(a0r), cy + r * math.sin(a0r))
    p1 = (cx + r * math.cos(a1r), cy + r * math.sin(a1r))
    large = 1 if abs(a1 - a0) > 180 else 0
    sweep = 0 if a1 > a0 else 1
    return "M%s A%s,%s 0 %d %d %s" % (pt(p0), f(r), f(r), large, sweep, pt(p1))


def rounded_poly(pts, r=FILLET):
    """把多边形每个角按半径 r 抹圆，返回闭合路径"""
    n = len(pts)
    segs = []
    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        v1 = (p0[0] - p1[0], p0[1] - p1[1])
        v2 = (p2[0] - p1[0], p2[1] - p1[1])
        l1, l2 = math.hypot(*v1), math.hypot(*v2)
        u1 = (v1[0] / l1, v1[1] / l1)
        u2 = (v2[0] / l2, v2[1] / l2)
        c = max(-1.0, min(1.0, u1[0] * u2[0] + u1[1] * u2[1]))
        ang = math.acos(c)
        if ang < 1e-4 or abs(ang - math.pi) < 1e-4:
            segs.append(("L", p1, None))
            continue
        d = min(r / math.tan(ang / 2.0), l1 * 0.45, l2 * 0.45)
        a = (p1[0] + u1[0] * d, p1[1] + u1[1] * d)
        b = (p1[0] + u2[0] * d, p1[1] + u2[1] * d)
        segs.append(("Q", a, (p1, b)))
    out = []
    for kind, a, extra in segs:
        if kind == "L":
            out.append("L" + pt(a))
            continue
        ctrl, end = extra
        out.append("Q%s %s" % (pt(ctrl), pt(end)))
    first = segs[0][1] if segs[0][0] == "L" else (
        segs[0][1] if segs[0][0] == "Q" else segs[0][1])
    # 从第一个线段的终点起笔（闭合后形状一致，但避免起点落在圆弧中间）
    body = "".join(out)
    return "M" + pt(first) + body + " Z"


def star(cx, cy, r_out, r_in, points=5, rot=-90):
    pts = []
    for i in range(points * 2):
        r = r_out if i % 2 == 0 else r_in
        a = math.radians(rot + i * 180.0 / points)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def gear(cx, cy, r_out, r_in, teeth=7, tooth=0.62, ccw=False):
    """齿轮：每个齿是外圆弧，齿间是内圆弧，全部抹圆。

    ccw=True 时点序反向：外轮廓顺时针 + 内孔逆时针，靠非零环绕规则镂空，
    这样不依赖 API 24 才有的 fillType，低版本同样能挖出中心孔。
    """
    pts = []
    order = -1 if ccw else 1
    for i in range(teeth):
        base = i * 360.0 / teeth
        half = (360.0 / teeth) * tooth / 2.0
        for rr, off in ((r_out, -half), (r_out, half), (r_in, half + 4),
                        (r_in, 360.0 / teeth - half - 4)):
            a = math.radians(base + off)
            pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


# ---------------------------------------------------------------- 图标定义
# 每个图标 = (paths) ; path = (d, style)
# style: 's' 描边（默认） / 'f' 填充
ICONS = {}


def icon(name, *paths):
    ICONS[name] = paths


# —— 导航 / 骨架 ——

icon("ic_folder_white_24dp",
     ("M3.4 8.4c0-1.15.93-2.08 2.08-2.08h3.06c.62 0 1.2.27 1.6.74l1.2 1.44h7.44c1.15 0 2.08.93 2.08 2.08v6.98c0 1.15-.93 2.08-2.08 2.08H5.48c-1.15 0-2.08-.93-2.08-2.08z", "s"))
ICONS["ic_folder_gray_24dp"] = ICONS["ic_folder_white_24dp"]

icon("ic_file_white_24dp",
     ("M5.2 6.0c0-1.7 1.4-3.1 3.1-3.1h5.0l5.5 5.6v9.5c0 1.7-1.4 3.1-3.1 3.1H8.3"
      "c-1.7 0-3.1-1.4-3.1-3.1z", "s"),
     (poly([(13.1, 3.1), (13.1, 8.4), (18.6, 8.4)]), "s"))
ICONS["ic_file_gray_24dp"] = ICONS["ic_file_white_24dp"]

icon("ic_assignment_turned_in_black_24dp",
     (rrect(4.4, 4.2, 15.2, 16.6, 3.4), "s"),
     (rrect(9.0, 2.4, 6.0, 3.6, 1.7), "f"),
     (poly([(8.6, 13.0), (11.1, 15.5), (15.9, 10.6)]), "s"))

icon("ic_lightning_black_24dp",
     (poly([(13.2, 3.2), (5.0, 13.6), (10.4, 13.6), (9.8, 20.8), (19.0, 10.4), (13.6, 10.4)],
           close=True), "f"))

icon("ic_settings_black_24dp",
     (rounded_poly(gear(12, 12, 9.6, 7.15, teeth=6), 0.85)
      + " " + circle(12, 12, 2.9), "f"))

icon("ic_search_black_24dp",
     (circle(10.9, 10.9, 6.3), "s"),
     (line(15.5, 15.5, 19.6, 19.6), "s"))

icon("ic_sort_black_24dp",
     (line(4.0, 6.6, 20.0, 6.6), "s"),
     (line(4.0, 12.0, 14.6, 12.0), "s"),
     (line(4.0, 17.4, 9.6, 17.4), "s"))

icon("baseline_close_24",
     (line(6.6, 6.6, 17.4, 17.4), "s"),
     (line(17.4, 6.6, 6.6, 17.4), "s"))

icon("ic_add_white_24dp",
     (line(12.0, 5.2, 12.0, 18.8), "s"),
     (line(5.2, 12.0, 18.8, 12.0), "s"))

icon("ic_arrow_back_white_24dp",
     (line(19.4, 12.0, 4.9, 12.0), "s"),
     (poly([(11.2, 5.5), (4.6, 12.0), (11.2, 18.5)]), "s"))

icon("ic_more_horiz_black_24dp",
     (dot(5.4, 12.0, 1.55), "f"),
     (dot(12.0, 12.0, 1.55), "f"),
     (dot(18.6, 12.0, 1.55), "f"))

icon("ic_check_black_24dp",
     (poly([(5.0, 12.7), (9.5, 17.2), (19.0, 7.3)]), "s"))

# —— 文件页选择态 / 常用动作 ——

icon("ic_star_black_24dp",
     (rounded_poly(star(12, 12.4, 9.2, 4.0), 1.0), "f"))

icon("ic_star_border_black_24dp",
     (rounded_poly(star(12, 12.4, 8.6, 4.5), 1.0), "s"))

icon("ic_gs_rename_black_24dp",
     ("M16.4 3.8a2.35 2.35 0 0 1 3.32 3.32L8.9 17.94l-4.5 1.16 1.16-4.5z", "s"))

icon("ic_edit_black_24dp",
     ("M16.4 3.8a2.35 2.35 0 0 1 3.32 3.32L8.9 17.94l-4.5 1.16 1.16-4.5z", "s"))

icon("ic_info_black_24dp",
     (circle(12, 12, 8.4), "s"),
     (line(12.0, 11.1, 12.0, 16.6), "s"),
     (dot(12.0, 7.9, 1.15), "f"))

icon("ic_delete_black_24dp",
     (line(4.2, 6.7, 19.8, 6.7), "s"),
     ("M9.5 6.7V5.3c0-.94.76-1.7 1.7-1.7h1.6c.94 0 1.7.76 1.7 1.7v1.4", "s"),
     ("M6.5 6.7l.83 11.9c.08 1.14.98 2.0 2.12 2.0h5.1c1.14 0 2.04-.86 2.12-2.0l.83-11.9", "s"),
     (line(10.2, 10.6, 10.2, 16.6), "s"),
     (line(13.8, 10.6, 13.8, 16.6), "s"))

# —— 编辑器顶栏 ——

icon("ic_undo_black_24dp",
     (poly([(8.8, 5.4), (4.4, 9.8), (8.8, 14.2)]), "s"),
     ("M4.4 9.8h9.2a5.6 5.6 0 0 1 0 11.2h-1.9", "s"),
     ("M20.4 20.2v-4.8", "s"))

ICONS["ic_undo_black_24dp"] = (
    (poly([(8.8, 5.2), (4.4, 9.6), (8.8, 14.0)]), "s"),
    ("M4.4 9.6h9.4a5.7 5.7 0 0 1 0 11.4h-2.2", "s"),
)

icon("ic_redo_black_24dp",
     (poly([(15.2, 5.2), (19.6, 9.6), (15.2, 14.0)]), "s"),
     ("M19.6 9.6h-9.4a5.7 5.7 0 0 0 0 11.4h2.2", "s"))

icon("ic_save_black_24dp",
     (rrect(3.4, 3.4, 17.2, 17.2, 3.4), "s"),
     (rrect(7.2, 3.4, 7.4, 5.2, 1.4), "s"),
     (rrect(7.2, 13.4, 9.6, 7.2, 1.8), "s"))

icon("ic_visibility_black_24dp",
     ("M2.9 12S6.5 5.6 12 5.6 21.1 12 21.1 12 17.5 18.4 12 18.4 2.9 12 2.9 12z", "s"),
     (circle(12, 12, 3.05), "s"))

icon("ic_visibility_off_black_24dp",
     ("M2.9 12S6.5 5.6 12 5.6 21.1 12 21.1 12 17.5 18.4 12 18.4 2.9 12 2.9 12z", "s"),
     (circle(12, 12, 3.05), "s"),
     (line(4.4, 19.6, 19.6, 4.4), "s"))

icon("ic_history_black_24dp",
     ("M4.6 8.9A8.2 8.2 0 1 1 3.8 12", "s"),
     (poly([(3.7, 4.6), (4.2, 9.2), (8.7, 8.3)]), "s"),
     (line(12.0, 7.9, 12.0, 12.2), "s"),
     (line(12.0, 12.2, 15.3, 14.0), "s"))

# —— 底部动作栏：列表 / 结构 ——

icon("ic_list_black_24dp",
     (dot(5.2, 6.9, 1.3), "f"),
     (dot(5.2, 12.0, 1.3), "f"),
     (dot(5.2, 17.1, 1.3), "f"),
     (line(9.2, 6.9, 20.0, 6.9), "s"),
     (line(9.2, 12.0, 20.0, 12.0), "s"),
     (line(9.2, 17.1, 20.0, 17.1), "s"))

icon("ic_format_list_numbered_black_24dp",
     ("M4.6 6.0l1.0-.8V8.9", "s"),
     ("M3.8 12.0c0-.7.6-1.2 1.3-1.2s1.2.5 1.2 1.2c0 1.3-2.5 1.7-2.5 3.1h2.5", "s"),
     ("M3.8 19.4h2.4l-1.4 1.8c.9 0 1.5.5 1.5 1.3", "s"),
     (line(9.4, 6.6, 20.0, 6.6), "s"),
     (line(9.4, 12.6, 20.0, 12.6), "s"),
     (line(9.4, 18.6, 20.0, 18.6), "s"))

icon("ic_check_box_black_24dp",
     (rrect(3.6, 3.6, 16.8, 16.8, 4.4), "s"),
     (poly([(8.0, 12.2), (11.0, 15.2), (16.5, 9.3)]), "s"))

icon("ic_code_black_24dp",
     (poly([(15.4, 7.2), (20.2, 12.0), (15.4, 16.8)]), "s"),
     (poly([(8.6, 7.2), (3.8, 12.0), (8.6, 16.8)]), "s"))

icon("ic_format_quote_black_24dp",
     ("M9.6 6.6c-2.9.7-4.6 2.7-4.6 5.6v5.2h5.2v-5.2H6.9c.1-1.7 1.1-2.9 2.7-3.5z", "f"),
     ("M20.4 6.6c-2.9.7-4.6 2.7-4.6 5.6v5.2h5.2v-5.2h-3.3c.1-1.7 1.1-2.9 2.7-3.5z", "f"))

icon("ic_link_black_24dp",
     ("M10.6 13.6a4.0 4.0 0 0 0 5.7 0l2.9-2.9a4.0 4.0 0 0 0-5.7-5.7l-1.4 1.4", "s"),
     ("M13.4 10.4a4.0 4.0 0 0 0-5.7 0l-2.9 2.9a4.0 4.0 0 0 0 5.7 5.7l1.4-1.4", "s"))

icon("ic_image_black_24dp",
     (rrect(3.4, 4.4, 17.2, 15.2, 3.4), "s"),
     (circle(8.7, 9.7, 1.65), "s"),
     (poly([(4.2, 17.4), (9.6, 11.9), (13.4, 15.7), (16.1, 13.0), (20.2, 17.2)]), "s"))

icon("ic_keyboard_voice_black_24dp",
     (rrect(9.0, 2.9, 6.0, 10.6, 3.0), "s"),
     ("M5.7 11.6a6.3 6.3 0 0 0 12.6 0", "s"),
     (line(12.0, 17.9, 12.0, 21.0), "s"))

icon("ic_keyboard_black_24dp",
     (rrect(2.8, 6.3, 18.4, 11.4, 2.9), "s"),
     (dot(6.6, 10.0, 0.85), "f"),
     (dot(9.9, 10.0, 0.85), "f"),
     (dot(13.2, 10.0, 0.85), "f"),
     (dot(16.5, 10.0, 0.85), "f"),
     (line(8.4, 14.4, 15.6, 14.4), "s"))

icon("ic_access_time_black_24dp",
     (circle(12, 12, 8.4), "s"),
     (line(12.0, 7.4, 12.0, 12.2), "s"),
     (line(12.0, 12.2, 15.7, 13.9), "s"))

icon("ic_format_indent_increase_black_24dp",
     (line(10.4, 6.4, 20.0, 6.4), "s"),
     (line(10.4, 12.0, 20.0, 12.0), "s"),
     (line(10.4, 17.6, 20.0, 17.6), "s"),
     (poly([(3.6, 8.4), (7.0, 12.0), (3.6, 15.6)]), "s"))

icon("ic_format_indent_decrease_black_24dp",
     (line(10.4, 6.4, 20.0, 6.4), "s"),
     (line(10.4, 12.0, 20.0, 12.0), "s"),
     (line(10.4, 17.6, 20.0, 17.6), "s"),
     (poly([(7.0, 8.4), (3.6, 12.0), (7.0, 15.6)]), "s"))

icon("ic_baseline_arrow_upward_24",
     (line(12.0, 19.6, 12.0, 5.0), "s"),
     (poly([(5.9, 11.1), (12.0, 4.8), (18.1, 11.1)]), "s"))

icon("ic_baseline_arrow_downward_24",
     (line(12.0, 4.4, 12.0, 19.0), "s"),
     (poly([(5.9, 12.9), (12.0, 19.2), (18.1, 12.9)]), "s"))

icon("ic_arrow_drop_down_black_24dp",
     (poly([(6.9, 9.9), (12.0, 15.1), (17.1, 9.9)]), "s"))

icon("ic_sort_by_alpha_black_24dp",
     (poly([(3.6, 18.8), (7.0, 5.6), (10.4, 18.8)]), "s"),
     (line(5.0, 14.4, 9.0, 14.4), "s"),
     (poly([(13.4, 5.6), (20.2, 5.6), (13.4, 18.8), (20.2, 18.8)]), "s"))

icon("ic_date_range_black_24dp",
     (rrect(3.4, 5.2, 17.2, 15.4, 3.2), "s"),
     (line(3.4, 10.0, 20.6, 10.0), "s"),
     (line(8.4, 2.8, 8.4, 7.2), "s"),
     (line(15.6, 2.8, 15.6, 7.2), "s"),
     (dot(8.6, 14.4, 1.1), "f"),
     (dot(12.0, 14.4, 1.1), "f"),
     (dot(15.4, 14.4, 1.1), "f"))

icon("ic_share_black_24dp",
     (circle(6.2, 12.0, 2.5), "s"),
     (circle(17.6, 6.2, 2.5), "s"),
     (circle(17.6, 17.8, 2.5), "s"),
     (line(8.4, 10.8, 15.3, 7.4), "s"),
     (line(8.4, 13.2, 15.3, 16.6), "s"))

icon("ic_view_module_black_24dp",
     (rrect(3.4, 3.9, 17.2, 16.2, 3.2), "s"),
     (line(3.4, 12.0, 20.6, 12.0), "s"),
     (line(10.8, 3.9, 10.8, 20.1), "s"),
     (line(16.4, 3.9, 16.4, 20.1), "s"))

icon("ic_baseline_wrap_text_24",
     (line(3.6, 6.0, 20.2, 6.0), "s"),
     (line(3.6, 11.4, 13.6, 11.4), "s"),
     ("M13.6 11.4h2.6a3.3 3.3 0 0 1 0 6.6H9.2", "s"),
     (poly([(9.2, 14.9), (6.6, 18.0), (9.2, 21.1)]), "s"))

icon("ic_line_numbers_24dp",
     (line(10.4, 6.6, 20.4, 6.6), "s"),
     (line(10.4, 12.0, 20.4, 12.0), "s"),
     (line(10.4, 17.4, 20.4, 17.4), "s"),
     ("M4.0 5.4l1.0-.8v4.4", "s"),
     ("M3.4 12.0c0-.7.6-1.2 1.3-1.2s1.2.5 1.2 1.2c0 1.3-2.5 1.7-2.5 3.0h2.5", "s"),
     ("M3.4 16.9h2.4l-1.4 1.7c.9 0 1.5.5 1.5 1.3", "s"))

icon("ic_baseline_keyboard_return_24",
     (line(18.4, 4.8, 18.4, 15.0), "s"),
     (line(18.4, 15.0, 8.6, 15.0), "s"),
     (poly([(12.0, 11.4), (8.4, 15.0), (12.0, 18.6)]), "s"))

icon("ic_vertical_align_center_black_24dp",
     (line(12.0, 3.2, 12.0, 9.0), "s"),
     (line(12.0, 15.0, 12.0, 20.8), "s"),
     (poly([(9.4, 6.2), (12.0, 3.6), (14.6, 6.2)]), "s"),
     (poly([(9.4, 17.8), (12.0, 20.4), (14.6, 17.8)]), "s"),
     (line(4.4, 12.0, 19.6, 12.0), "s"))

icon("ic_rotate_left_black_24dp",
     ("M4.6 9.0A8.2 8.2 0 1 1 3.9 12.2", "s"),
     (poly([(3.7, 4.7), (4.3, 9.3), (8.8, 8.4)]), "s"))

icon("ic_open_in_browser_black_24dp",
     (rrect(3.4, 4.4, 17.2, 15.2, 3.2), "s"),
     (line(3.4, 9.2, 20.6, 9.2), "s"),
     (dot(6.4, 6.8, 0.8), "f"),
     (dot(9.0, 6.8, 0.8), "f"))

icon("ic_baseline_open_in_new_24",
     (poly([(18.4, 13.2), (18.4, 18.6), (5.4, 18.6), (5.4, 5.6), (10.8, 5.6)]), "s"),
     (line(13.2, 4.0, 20.0, 4.0), "s"),
     (line(20.0, 4.0, 20.0, 10.8), "s"),
     (line(20.0, 4.0, 12.4, 11.6), "s"))

icon("ic_home_black_24dp",
     ("M3.6 10.2L12 3.4l8.4 6.8v9.0a2.0 2.0 0 0 1-2.0 2.0H5.6a2.0 2.0 0 0 1-2.0-2.0z", "s"),
     ("M9.5 21.2v-6.6a1.1 1.1 0 0 1 1.1-1.1h2.8a1.1 1.1 0 0 1 1.1 1.1v6.6", "s"))

icon("baseline_download_24",
     (line(12.0, 3.6, 12.0, 14.6), "s"),
     (poly([(7.5, 10.2), (12.0, 14.8), (16.5, 10.2)]), "s"),
     ("M4.0 16.4v2.0a2.0 2.0 0 0 0 2.0 2.0h12.0a2.0 2.0 0 0 0 2.0-2.0v-2.0", "s"))

icon("ic_favorite_black_24dp",
     ("M12 20.4 4.6 13a4.95 4.95 0 0 1 0-7 4.95 4.95 0 0 1 7 0l.4.4.4-.4a4.95 4.95 0 0 1 7 0 4.95 4.95 0 0 1 0 7z", "f"))

icon("ic_widgets_black_24dp",
     (rrect(3.5, 3.5, 7.0, 7.0, 2.1), "s"),
     (rrect(13.5, 3.5, 7.0, 7.0, 2.1), "s"),
     (rrect(3.5, 13.5, 7.0, 7.0, 2.1), "s"),
     (rrect(13.5, 13.5, 7.0, 7.0, 2.1), "s"))

icon("ic_border_outer_black_24dp",
     (rrect(3.5, 3.5, 7.0, 7.0, 2.1), "s"),
     (rrect(13.5, 3.5, 7.0, 7.0, 2.1), "s"),
     (rrect(3.5, 13.5, 7.0, 7.0, 2.1), "s"),
     (rrect(13.5, 13.5, 7.0, 7.0, 2.1), "s"))

icon("ic_invert_colors_black_24dp",
     ("M12 3.4c3.2 3.6 5.4 6.5 5.4 9.2a5.4 5.4 0 0 1-10.8 0c0-2.7 2.2-5.6 5.4-9.2z", "s"),
     ("M12 9.4v8.4", "s"))

icon("ic_lock_outline_black_24dp",
     (rrect(4.9, 10.0, 14.2, 10.4, 3.0), "s"),
     ("M8.4 10.0V8.0a3.6 3.6 0 0 1 7.2 0v2.0", "s"),
     (dot(12.0, 15.2, 1.25), "f"))

icon("ic_baseline_account_tree_24",
     (rrect(3.2, 8.6, 5.4, 5.4, 1.8), "s"),
     (rrect(15.4, 3.3, 5.4, 5.4, 1.8), "s"),
     (rrect(15.4, 15.3, 5.4, 5.4, 1.8), "s"),
     (line(8.6, 11.3, 12.0, 11.3), "s"),
     (line(12.0, 6.0, 12.0, 18.0), "s"),
     (line(12.0, 6.0, 15.3, 6.0), "s"),
     (line(12.0, 18.0, 15.3, 18.0), "s"))

icon("ic_bug_report_black_24dp",
     (rrect(7.5, 7.0, 9.0, 12.8, 4.5), "s"),
     (line(9.7, 7.2, 8.4, 4.2), "s"),
     (line(14.3, 7.2, 15.6, 4.2), "s"),
     (line(7.5, 11.6, 4.4, 11.6), "s"),
     (line(7.5, 15.4, 4.8, 16.8), "s"),
     (line(16.5, 11.6, 19.6, 11.6), "s"),
     (line(16.5, 15.4, 19.2, 16.8), "s"))

icon("ic_baseline_rule_folder_24",
     ("M3.4 8.4c0-1.15.93-2.08 2.08-2.08h3.06c.62 0 1.2.27 1.6.74l1.2 1.44h7.44c1.15 0 2.08.93 2.08 2.08v6.98c0 1.15-.93 2.08-2.08 2.08H5.48c-1.15 0-2.08-.93-2.08-2.08z", "s"),
     (poly([(9.0, 13.6), (10.8, 15.4), (14.2, 11.9)]), "s"))

icon("ic_duplicate_lines_black_24dp",
     (rrect(8.2, 3.4, 12.4, 12.4, 3.0), "s"),
     ("M15.8 18.4a2.4 2.4 0 0 1-2.4 2.2H6.0a2.6 2.6 0 0 1-2.6-2.6V9.4a2.4 2.4 0 0 1 2.2-2.4", "s"),
     (line(11.0, 9.6, 17.8, 9.6), "s"))

icon("ic_baseline_file_copy_24",
     (rrect(8.2, 3.4, 12.4, 12.4, 3.0), "s"),
     ("M15.8 18.4a2.4 2.4 0 0 1-2.4 2.2H6.0a2.6 2.6 0 0 1-2.6-2.6V9.4a2.4 2.4 0 0 1 2.2-2.4", "s"),
     (line(11.0, 9.6, 17.8, 9.6), "s"))

icon("ic_archive_black_24dp",
     (rrect(3.6, 3.6, 16.8, 3.9, 1.7), "s"),
     ("M5.2 8.6v9.6a2.1 2.1 0 0 0 2.1 2.1h9.4a2.1 2.1 0 0 0 2.1-2.1V8.6", "s"),
     (line(10.2, 13.0, 13.8, 13.0), "s"))

icon("ic_bookmark_black_24dp",
     ("M6.4 4.6a1.8 1.8 0 0 1 1.8-1.8h7.6a1.8 1.8 0 0 1 1.8 1.8v15.4l-5.6-4.2-5.6 4.2z", "s"))

icon("ic_sync_black_24dp",
     ("M20.0 11.9a8.0 8.0 0 0 0-13.9-5.1L3.7 9.2", "s"),
     (poly([(3.6, 4.6), (3.7, 9.3), (8.4, 9.2)]), "s"),
     ("M4.0 12.1a8.0 8.0 0 0 0 13.9 5.1l2.4-2.4", "s"),
     (poly([(20.4, 19.4), (20.3, 14.7), (15.6, 14.8)]), "s"))

icon("ic_share_black_24dp",
     (circle(6.2, 12.0, 2.5), "s"),
     (circle(17.6, 6.2, 2.5), "s"),
     (circle(17.6, 17.8, 2.5), "s"),
     (line(8.4, 10.8, 15.3, 7.4), "s"),
     (line(8.4, 13.2, 15.3, 16.6), "s"))

icon("ic_attach_file_black_24dp",
     ("M20.4 11.2l-8.6 8.6a5.6 5.6 0 0 1-7.9-7.9l8.2-8.2a3.7 3.7 0 0 1 5.3 5.3"
      "l-8.2 8.2a1.9 1.9 0 0 1-2.6-2.6l7.6-7.6", "s"))

icon("ic_print_black_24dp",
     ("M6.6 9.0V4.4a1.2 1.2 0 0 1 1.2-1.2h8.4a1.2 1.2 0 0 1 1.2 1.2V9.0", "s"),
     (rrect(4.0, 9.0, 16.0, 7.6, 2.4), "s"),
     ("M6.6 16.6h10.8v3.0a1.4 1.4 0 0 1-1.4 1.4H8.0a1.4 1.4 0 0 1-1.4-1.4z", "s"))


# —— 菜单里还会露脸的几个（补齐，避免新旧混用） ——

icon("ic_format_shapes_black_24dp",
     (circle(9.2, 9.2, 4.6), "s"),
     (rrect(11.6, 11.6, 9.0, 9.0, 2.8), "s"))

icon("ic_format_color_fill_black_24dp",
     ("M11.6 3.3 4.4 10.5a1.7 1.7 0 0 0 0 2.4l5.9 5.9a1.7 1.7 0 0 0 2.4 0l4.9-4.9z", "s"),
     ("M8.4 6.5l6.6 6.6", "s"),
     (rrect(4.2, 18.6, 15.6, 3.2, 1.6), "f"))

icon("ic_baseline_align_horizontal_left_24",
     (line(3.6, 5.6, 20.2, 5.6), "s"),
     (line(3.6, 10.4, 15.6, 10.4), "s"),
     (line(3.6, 15.2, 20.2, 15.2), "s"),
     (line(3.6, 20.0, 13.2, 20.0), "s"))

icon("ic_format_size_black_24dp",
     (poly([(2.8, 19.2), (6.6, 6.4), (10.4, 19.2)]), "s"),
     (line(4.2, 14.6, 9.0, 14.6), "s"),
     (poly([(14.6, 9.4), (17.4, 6.2), (20.2, 9.4)]), "s"),
     (poly([(14.6, 14.8), (17.4, 18.0), (20.2, 14.8)]), "s"))

icon("ic_photo_camera_black_24dp",
     ("M8.0 6.6 9.4 4.4h5.2l1.4 2.2h3.4a1.9 1.9 0 0 1 1.9 1.9v9.2a1.9 1.9 0 0 1-1.9 1.9H4.6a1.9 1.9 0 0 1-1.9-1.9V8.5a1.9 1.9 0 0 1 1.9-1.9z", "s"),
     (circle(12.0, 13.2, 3.6), "s"))

icon("ic_format_text_case_black_24dp",
     (poly([(3.0, 15.4), (6.0, 5.2), (9.0, 15.4)]), "s"),
     (line(4.2, 11.8, 7.8, 11.8), "s"),
     (circle(15.6, 12.4, 2.9), "s"),
     (line(18.5, 9.5, 18.5, 15.4), "s"))

icon("fountain_pen",
     ("M12 3.2 17.2 12a5.6 5.6 0 0 1-5.2 8.6 5.6 5.6 0 0 1-5.2-8.6z", "s"),
     (line(12.0, 8.6, 12.0, 14.2), "s"),
     (dot(12.0, 17.0, 1.2), "f"))

# ---------------------------------------------------------------- 输出


def svg_icon(name):
    """把图标渲染成 SVG 片段（描边 / 填充分开成 path 元素）"""
    out = []
    for d, style in ICONS[name]:
        if style == "fe":
            out.append('<path d="%s" fill="currentColor" fill-rule="evenodd"/>' % d)
        elif style == "f":
            out.append('<path d="%s" fill="currentColor" stroke="currentColor" '
                       'stroke-width="0.9" stroke-linejoin="round"/>' % d)
        else:
            out.append('<path d="%s" fill="none" stroke="currentColor" '
                       'stroke-width="%s" stroke-linecap="round" '
                       'stroke-linejoin="round"/>' % (d, STROKE))
    return "".join(out)


def build_preview(path):
    names = sorted(ICONS)
    cards = []
    for n in names:
        cards.append(
            '<div class="cell"><svg viewBox="0 0 24 24" width="44" height="44">%s</svg>'
            '<svg viewBox="0 0 24 24" width="22" height="22">%s</svg>'
            '<span>%s</span></div>' % (svg_icon(n), svg_icon(n), n))

    big = "".join('<div class="big"><svg viewBox="0 0 24 24" width="96" height="96">%s</svg>'
                  '<small>%s</small></div>' % (svg_icon(n), n) for n in names[:12])

    detail_names = [n for n in (
        "ic_settings_black_24dp", "ic_file_white_24dp", "ic_baseline_wrap_text_24",
        "ic_baseline_keyboard_return_24", "ic_bug_report_black_24dp",
        "ic_baseline_account_tree_24", "ic_sort_by_alpha_black_24dp",
        "ic_archive_black_24dp", "ic_attach_file_black_24dp", "ic_image_black_24dp",
        "ic_undo_black_24dp", "ic_redo_black_24dp", "ic_format_list_numbered_black_24dp",
        "ic_lightning_black_24dp", "ic_assignment_turned_in_black_24dp",
        "ic_star_black_24dp", "ic_delete_black_24dp", "ic_folder_white_24dp",
    ) if n in ICONS]
    detail = "".join('<div class="big"><svg viewBox="0 0 24 24" width="96" height="96">%s</svg>'
                     '<small>%s</small></div>' % (svg_icon(n), n) for n in detail_names)

    html = """<!doctype html><html><head><meta charset="utf-8"><style>
body{margin:0;font:12px/1.4 -apple-system,"Segoe UI",sans-serif;background:#F7F8FA;color:#111}
h2{font:600 13px/1 sans-serif;letter-spacing:.08em;text-transform:uppercase;color:#5A6172;margin:22px 24px 10px}
.light,.dark{padding:6px 20px 18px}
.dark{background:#12141A;color:#E8EAF0}
.grid{display:flex;flex-wrap:wrap;gap:4px}
.cell{width:104px;display:flex;flex-direction:column;align-items:center;gap:2px;padding:8px 4px;border-radius:14px;background:#fff;border:1px solid #E6E9EF}
.dark .cell{background:#1B1E27;border-color:#262A35}
.cell span{font-size:8.5px;opacity:.55;text-align:center;word-break:break-all;line-height:1.15}
.row{display:flex;gap:14px;flex-wrap:wrap;align-items:flex-end}
.big{display:flex;flex-direction:column;align-items:center;gap:4px;padding:10px;border-radius:18px;background:#fff;border:1px solid #E6E9EF}
.dark .big{background:#1B1E27;border-color:#262A35}
.big small{font-size:9px;opacity:.5}
.navbar{display:flex;gap:0;background:#fff;border-radius:26px;padding:10px 26px;box-shadow:0 6px 24px rgba(20,24,40,.08);width:max-content}
.navbar div{width:84px;display:flex;flex-direction:column;align-items:center;gap:4px;font-size:11px;color:#8B93A5}
.navbar div.on{color:#12141A}
</style></head><body>
<h2>问题复核（96px）</h2><div class="light"><div class="row">%s</div></div>
<h2>浅色 · 全部</h2><div class="light"><div class="grid">%s</div></div>
<h2>深色 · 全部</h2><div class="dark"><div class="grid">%s</div></div>
<h2>底部导航实尺寸模拟（23px）</h2><div class="light"><div class="navbar">
<div class="on"><svg viewBox="0 0 24 24" width="23" height="23">%s</svg>文件</div>
<div><svg viewBox="0 0 24 24" width="23" height="23">%s</svg>To-Do</div>
<div><svg viewBox="0 0 24 24" width="23" height="23">%s</svg>QuickNote</div>
<div><svg viewBox="0 0 24 24" width="23" height="23">%s</svg>设置</div>
</div></div>
</body></html>""" % (
        detail, "".join(cards), "".join(cards),
        svg_icon("ic_folder_white_24dp"),
        svg_icon("ic_assignment_turned_in_black_24dp"),
        svg_icon("ic_lightning_black_24dp"),
        svg_icon("ic_settings_black_24dp"),
    )
    open(path, "w", encoding="utf-8").write(html)


def vector_xml(name):
    paths = ICONS[name]
    body = []
    for d, style in paths:
        if style == "fe":
            body.append('    <path\n'
                        '        android:fillColor="#FF000000"\n'
                        '        android:fillType="evenOdd"\n'
                        '        android:pathData="%s" />' % d)
        elif style == "f":
            body.append('    <path\n'
                        '        android:fillColor="#FF000000"\n'
                        '        android:fillType="nonZero"\n'
                        '        android:pathData="%s"\n'
                        '        android:strokeColor="#FF000000"\n'
                        '        android:strokeLineJoin="round"\n'
                        '        android:strokeWidth="1.1" />' % d)
        else:
            body.append('    <path\n'
                        '        android:fillColor="#00000000"\n'
                        '        android:pathData="%s"\n'
                        '        android:strokeColor="#FF000000"\n'
                        '        android:strokeLineCap="round"\n'
                        '        android:strokeLineJoin="round"\n'
                        '        android:strokeWidth="%s" />' % (d, STROKE))
    return ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n'
            '    android:height="24dp"\n'
            '    android:viewportWidth="24"\n'
            '    android:viewportHeight="24">\n'
            + "\n".join(body) + "\n</vector>\n")


def build_nav_preview(path):
    """按真机尺寸模拟底部导航 / 编辑器工具栏 / 动作栏，检查小尺寸辨识度"""
    def sv(n, size=23):
        return '<svg viewBox="0 0 24 24" width="%d" height="%d">%s</svg>' % (
            size, size, svg_icon(n))

    nav_icons = [("ic_folder_white_24dp", "文件"), ("ic_assignment_turned_in_black_24dp", "To-Do"),
                 ("ic_lightning_black_24dp", "QuickNote"), ("ic_settings_black_24dp", "设置")]
    nav = "".join('<div class="%s">%s<span>%s</span></div>' % (
        "on" if i == 0 else "", sv(n), t) for i, (n, t) in enumerate(nav_icons))

    toolbar_icons = ["ic_arrow_back_white_24dp", "ic_undo_black_24dp", "ic_redo_black_24dp",
                     "ic_save_black_24dp", "ic_visibility_black_24dp",
                     "ic_search_black_24dp", "ic_more_horiz_black_24dp"]
    tb = "".join('<div class="tb">%s</div>' % sv(n, 22) for n in toolbar_icons)

    bar_icons = ["ic_sort_by_alpha_black_24dp", "ic_star_black_24dp", "ic_history_black_24dp",
                 "ic_check_box_black_24dp", "ic_access_time_black_24dp",
                 "ic_keyboard_black_24dp", "ic_lightning_black_24dp", "ic_delete_black_24dp"]
    bar = "".join('<div class="ab">%s</div>' % sv(n, 21) for n in bar_icons)

    html = """<!doctype html><html><head><meta charset="utf-8"><style>
*{box-sizing:border-box}
body{margin:0;padding:26px;font:13px/1.4 -apple-system,"Segoe UI",sans-serif;background:#F7F8FA;color:#12141A}
.dark{background:#12141A;color:#E8EAF0;margin-top:14px;padding:26px;margin-left:-26px;margin-right:-26px}
.nav{display:flex;background:#fff;border-radius:26px;padding:10px 6px;box-shadow:0 10px 28px rgba(20,24,40,.10);width:366px}
.dark .nav{background:#1A1D25;box-shadow:none;border:1px solid #262A35}
.nav div{flex:1;display:flex;flex-direction:column;align-items:center;gap:3px;font-size:11px;color:#8B93A5;padding:5px 0;border-radius:16px}
.nav div.on{color:#12141A;background:#EDEFF5}
.dark .nav div.on{color:#E8EAF0;background:#2C3140}
.head{display:flex;align-items:center;gap:16px;background:#fff;border-radius:22px;padding:10px 18px;width:366px;margin-bottom:12px}
.dark .head{background:#1A1D25}
.tb{color:#12141A;display:flex;align-items:center}
.dark .tb{color:#E8EAF0}
.ab{display:flex;align-items:center;justify-content:center;width:44px;height:44px;border-radius:14px;background:#F2F4F8;color:#12141A}
.dark .ab{background:#22262F;color:#E8EAF0}
.bar{display:flex;gap:6px;background:#fff;border-radius:22px;padding:8px;width:366px}
.dark .bar{background:#1A1D25}
h3{font:600 11px/1 sans-serif;letter-spacing:.1em;text-transform:uppercase;color:#5A6172;margin:18px 0 8px}
.dark h3{color:#8891A3}
</style></head><body>
<h3>底部导航 · 真机尺寸</h3><div class="nav">%s</div>
<h3>编辑器顶栏</h3><div class="head">%s</div>
<h3>动作栏（21dp）</h3><div class="bar">%s</div>
<div class="dark">
<h3>底部导航 · 深色</h3><div class="nav">%s</div>
<h3>动作栏 · 深色</h3><div class="bar">%s</div>
</div></body></html>""" % (nav, tb, bar, nav, bar)
    open(path, "w", encoding="utf-8").write(html)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    build_preview(os.path.join(here, "preview.html"))
    build_nav_preview(os.path.join(here, "nav.html"))
    if "--emit" in sys.argv:
        res = os.path.abspath(os.path.join(here, "..", "..", "app", "src", "main", "res", "drawable"))
        n = 0
        for name in ICONS:
            open(os.path.join(res, name + ".xml"), "w", encoding="utf-8").write(vector_xml(name))
            n += 1
        print("wrote %d drawables to %s" % (n, res))
    else:
        print("preview only (%d icons)" % len(ICONS))


if __name__ == "__main__":
    main()
