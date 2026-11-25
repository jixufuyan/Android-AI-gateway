#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按「两边都保留」策略解决 AndroidManifest.xml 的合并冲突。

为什么需要专门的合并器
----------------------
AndroidManifest.xml 的冲突几乎总是同一种形态：**两边各自往 <application> 里插了几个
<activity>/<service>**。git 只会说「这一块冲突了」，但语义上两边都要。

而且还有两个坑：

1. **版本号冲突必须保留本地版**（versionCode/versionName 在 build.gradle.kts 里，
   与本脚本无关，但同一个 PR 常一起改）。
2. `<activity>` 的 `android:name` 一旦重复，aapt2 直接报 duplicate。
   所以合并时必须按 name 去重，不能盲目把两边块贴在一起。

用法：
    python3 tools/merge_manifest.py <冲突文件>

退出码：
    0 = 已解决（无冲突标记残留）
    1 = 还有解析不了的部分，需要人工
"""

import re
import sys

CONFLICT = re.compile(
    r'<<<<<<< [^\n]*\n(.*?)\n?=======\n(.*?)\n?>>>>>>> [^\n]*\n',
    re.S,
)

# 顶层节点开始/结束（用来判断「这一块是不是完整的 XML 片段」）
ELEMENT = re.compile(r'<([A-Za-z_][\w:.-]*)', re.S)
NAME_ATTR = re.compile(r'android:name\s*=\s*"([^"]+)"')


def split_atoms(block):
    """把一段 XML 文本切成「顶层子元素序列」，每项是 (kind, text)。

    kind: 'element'（完整元素，含自闭合）或 'text'（注释/空白/散文本）
    """
    out = []
    i = 0
    n = len(block)
    buf = []
    while i < n:
        if block.startswith('<!--', i):
            j = block.find('-->', i)
            j = n if j < 0 else j + 3
            buf.append(block[i:j])
            out.append(('text', ''.join(buf)))
            buf = []
            i = j
            continue
        if block[i] == '<' and i + 1 < n and (block[i + 1].isalpha() or block[i + 1] in '_/'):
            m = ELEMENT.match(block, i)
            if not m:
                buf.append(block[i])
                i += 1
                continue
            if m.group(1) == '/':
                buf.append(block[i])
                i += 1
                continue
            tag = m.group(1)
            # 开始标签的结束位置（考虑引号内的 >）
            k = i
            quote = None
            while k < n:
                c = block[k]
                if quote:
                    if c == quote:
                        quote = None
                elif c in '"\'':
                    quote = c
                elif c == '>':
                    break
                k += 1
            if k >= n:
                buf.append(block[i:])
                i = n
                break
            if block[k - 1] == '/':          # 自闭合
                out.append(('element', block[i:k + 1]))
                i = k + 1
                continue
            # 找配对的闭合标签（允许同名嵌套）
            depth = 1
            j = k + 1
            close = re.compile(r'<(/?)' + re.escape(tag) + r'\b', re.S)
            end = n
            while True:
                mm = close.search(block, j)
                if not mm:
                    end = n
                    break
                if mm.group(1) == '/':
                    depth -= 1
                    if depth == 0:
                        gt = block.find('>', mm.start())
                        end = n if gt < 0 else gt + 1
                        break
                else:
                    # 同名开始标签，但要排除自闭合
                    gt = block.find('>', mm.start())
                    if gt > 0 and block[gt - 1] == '/':
                        pass
                    else:
                        depth += 1
                j = mm.end()
            out.append(('element', block[i:end]))
            i = end
            continue
        buf.append(block[i])
        i += 1
    if buf:
        out.append(('text', ''.join(buf)))
    return out


def name_of(elem):
    m = NAME_ATTR.search(elem)
    return m.group(1) if m else None


def collect_names(xml_text):
    """收集一段 XML 里出现的组件名。"""
    return set(NAME_ATTR.findall(xml_text))


def merge_side(ours, theirs, known=None):
    """两边都保留：保留 ours 的顺序，追加 theirs 里「还没有」的元素。

    ``known`` 是「本文件里冲突区之外已经存在的组件名」，必须传进来 ——
    否则上游把 `.Main` 写进冲突块、而本地 `.Main` 在冲突块外面时，
    合并结果里会出现两个 `.Main`，aapt2 直接报 duplicate。
    """
    known = set(known or ())

    a_items = split_atoms(ours)
    b_items = split_atoms(theirs)

    have = set(known)
    for kind, txt in a_items:
        if kind == 'element':
            nm = name_of(txt)
            if nm:
                have.add(nm)

    # 找出 ours 里最后一个元素的位置：新增项插在它后面
    last = -1
    for i, (kind, _) in enumerate(a_items):
        if kind == 'element':
            last = i

    added = []
    for kind, txt in b_items:
        if kind != 'element':
            continue
        nm = name_of(txt)
        if nm:
            if nm in have:
                continue                # 已在（本地或冲突区外）→ 不重复
            have.add(nm)
        added.append(txt)

    if not added:
        result = ours
    else:
        def norm(txt):
            """整体左移再加 8 空格基准缩进，避免和外面已有的缩进叠加。"""
            raw = [ln.rstrip() for ln in txt.strip('\n').split('\n')]
            raw = [ln for ln in raw if ln.strip()]
            if not raw:
                return ''
            base = min(len(ln) - len(ln.lstrip()) for ln in raw)
            return '\n'.join('        ' + ln[base:] for ln in raw)

        body = '\n'.join(norm(t) for t in added)
        if last < 0:
            # ours 里没有元素，整个贴上去
            result = ours.rstrip('\n') + '\n' + body + '\n'
        else:
            head = ''.join(t for _, t in a_items[:last + 1])
            tail = ''.join(t for _, t in a_items[last + 1:])
            # head 末尾可能有换行+缩进空白，先剥掉再统一补一个换行
            head = head.rstrip('\n')
            head = head.rstrip(' \t')
            tail = tail.lstrip('\n')
            tail = tail.lstrip(' \t')
            result = head + '\n' + body + '\n        ' + tail

    # 注释紧贴元素时补换行，避免 `-->` 和 `<activity` 粘在一行
    result = re.sub(r'(-->)(<)', r'\1\n        \2', result)
    return result


def main():
    if len(sys.argv) < 2:
        print('用法: merge_manifest.py <文件>', file=sys.stderr)
        return 2
    path = sys.argv[1]
    with open(path, encoding='utf-8') as f:
        src = f.read()

    if '<<<<<<<' not in src:
        print('  没有冲突标记，跳过')
        return 0

    unresolved = []

    # 顺序处理：冲突区之外的文本先扫出已有组件名，逐个冲突块合并时带上，
    # 这样「本地某组件在冲突区外、上游同一组件在冲突区内」也不会重复。
    pieces = []
    known = set()
    pos = 0
    for m in CONFLICT.finditer(src):
        before = src[pos:m.start()]
        pieces.append(before)
        known |= collect_names(before)
        try:
            merged = merge_side(m.group(1), m.group(2), known)
        except Exception as e:
            unresolved.append(str(e))
            merged = m.group(0)
        pieces.append(merged)
        known |= collect_names(merged)
        pos = m.end()
    tail_text = src[pos:]
    pieces.append(tail_text)
    known |= collect_names(tail_text)

    out = ''.join(pieces)

    if '<<<<<<<' in out:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(out)
        print('  仍残留冲突标记：%s' % (unresolved or ['未知原因']))
        return 1

    with open(path, 'w', encoding='utf-8') as f:
        f.write(out)

    # 自检：<application> 里出现的 activity name 不能重复
    seen = {}
    dup = []
    for m in re.finditer(r'<activity\b[^>]*android:name\s*=\s*"([^"]+)"', out):
        nm = m.group(1)
        if nm in seen:
            dup.append(nm)
        seen[nm] = 1
    if dup:
        print('  警告：activity 重名（aapt2 会报 duplicate）：%s' % ', '.join(sorted(set(dup))))
        return 1

    print('  已按「两边都保留 + 按 name 去重」解决：共 %d 个 activity' % len(seen))
    return 0


if __name__ == '__main__':
    sys.exit(main())
