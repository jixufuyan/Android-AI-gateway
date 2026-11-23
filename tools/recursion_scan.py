#!/usr/bin/env python3
"""扫描「函数自己调自己」——这类代码能编译，一跑就 StackOverflowError。

为什么单独写：这类 bug 靠编译器抓不到（调用合法、类型正确），日志又被 R8 混淆成
`v4.q3.b`，只能靠 mapping.txt 还原才知道是谁。AppSwitch 就是这么崩的。

用法：python3 tools/recursion_scan.py [源码目录，默认 app/src/main]
输出：每行一个可疑位置；没有输出表示干净。
"""
import glob
import os
import re
import sys

OP_CHARS = "=+&|,(-<*:/%"


def body_of(src: str, m: re.Match) -> str | None:
    """取函数体：块体做花括号配对，表达式体取到语句结束。"""
    i = src.find("(", m.end() - 1)
    if i < 0:
        return None

    # 先跳过参数列表
    depth, k = 0, i
    while k < len(src):
        c = src[k]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                break
        k += 1
    k += 1
    while k < len(src) and src[k] in " \t\r\n":
        k += 1
    if k >= len(src):
        return None

    if src[k] == "{":  # 块体
        depth, j = 0, k
        while j < len(src):
            if src[j] == "{":
                depth += 1
            elif src[j] == "}":
                depth -= 1
                if depth == 0:
                    return src[k : j + 1]
            j += 1
        return None

    if src[k] == "=":  # 表达式体
        depth, j = 0, k
        while j < len(src):
            c = src[j]
            if c in "([{":
                depth += 1
            elif c in ")]}":
                if depth == 0:
                    break
                depth -= 1
            elif c == "\n" and depth == 0:
                prev = src[:j].rstrip()[-1:]
                if prev not in OP_CHARS:
                    break
            j += 1
        return src[k:j]

    return None


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "app/src/main"
    hits: list[str] = []
    for path in glob.glob(os.path.join(root, "**", "*.kt"), recursive=True):
        src = open(path, encoding="utf-8").read()
        # 本文件 import 进来的同名符号：裸调用指向 import，不是自己
        imported = set(re.findall(r"^import\s+[\w.]*?(\w+)\s*$", src, re.M))
        for m in re.finditer(r"\bfun\s+(\w+)\s*\(", src):
            name = m.group(1)
            if name in imported:
                continue
            body = body_of(src, m)
            if not body:
                continue
            body = re.sub(r"//[^\n]*", "", body)
            body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
            if re.search(r"(?<![A-Za-z0-9_.])" + re.escape(name) + r"\s*\(", body):
                line = src[: m.start()].count("\n") + 1
                hits.append(f"{path}:{line}: {name}()")
    print("\n".join(hits))
    return 0


if __name__ == "__main__":
    sys.exit(main())
