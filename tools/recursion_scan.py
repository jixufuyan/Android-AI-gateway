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
    src = blank_strings(src)
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
    # 跳过返回类型声明：`fun boom(n: Int): Int { ... }`
    # 原来这里直接看 src[k]，遇到 ':' 就 return None —— 于是「带显式返回类型的函数」
    # 从来没被扫描过（正是最容易写递归的那一类）。泛型里的 <> 也要一起跳。
    while k < len(src):
        while k < len(src) and src[k] in " \t\r\n":
            k += 1
        if k >= len(src) or src[k] in "{=":
            break
        if src[k] == ":":
            k += 1
            depth_t = 0
            while k < len(src):
                c = src[k]
                if c in "([<":
                    depth_t += 1
                elif c in ")]>":
                    depth_t -= 1
                elif c in "{\n=" and depth_t <= 0:
                    break
                elif c == "," and depth_t <= 0:
                    pass
                k += 1
            continue
        if src[k] in "\"'":     # 理论上不会到这，保险起见别死循环
            break
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



def blank_strings(src: str) -> str:
    """把字符串/字符字面量的**内容**换成空格，长度严格不变。

    为什么要做：Kotlin 的原始字符串里常有 `{`、`}`（JSON 模板、正则），
    不屏蔽掉它们，花括号配对会错位。

    为什么必须**等长**：调用方用偏移量去切片、算行号。少一个字符，
    后面所有解析都会错位到别的函数体上（这个坑真的踩过：一个 `= expr` 函数
    被解析成了二十行外另一个函数的 body）。
    """
    n = len(src)
    out = list(src)
    i = 0
    while i < n:
        c = src[i]
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            for k in range(i + 3, j):
                if out[k] != '\n':
                    out[k] = ' '
            i = j + 3
            continue
        if c in ('"', "'"):
            q = c
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == q:
                    break
                if src[j] == '\n':
                    break
                j += 1
            for k in range(i + 1, min(j, n)):
                if out[k] != '\n':
                    out[k] = ' '
            i = j + 1
            continue
        i += 1
    return ''.join(out)


NESTED_FUN = re.compile(r"\bfun\s+\w+\s*\(", re.M)


def nested_self_call(body: str, name: str) -> bool:
    """函数体里是否在**同一层**调用了自己。

    为什么不能只看「body 里出现了 name(」：
    匿名类重写（`webViewClient = object : ... { override fun onCreateWindow(...) }`）
    里调用外层私有函数是合法的、常见的写法，一点都不递归。但它确实出现在
    外层函数的 body 文本里 —— 直接搜就会误报。

    判据：把 body 里每个嵌套函数（`fun xxx(`）的那一层切出来，
    那些区间里出现的同名调用不算；只看嵌套区间之外的调用。
    """
    # 所有嵌套函数体的区间（从 `fun 名字(` 的花括号配对起止）
    spans = []
    for m in NESTED_FUN.finditer(body):
        i = body.find("{", m.end())
        if i < 0:
            continue
        depth, j = 0, i
        while j < len(body):
            if body[j] == "{":
                depth += 1
            elif body[j] == "}":
                depth -= 1
                if depth == 0:
                    spans.append((i, j))
                    break
            j += 1

    pat = re.compile(r"(?<![A-Za-z0-9_.])" + re.escape(name) + r"\s*\(")
    for m in pat.finditer(body):
        pos = m.start()
        if any(a <= pos <= b for a, b in spans):
            continue
        return True
    return False


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
            if nested_self_call(body, name):
                line = src[: m.start()].count("\n") + 1
                hits.append(f"{path}:{line}: {name}()")
    print("\n".join(hits))
    return 0


if __name__ == "__main__":
    sys.exit(main())
