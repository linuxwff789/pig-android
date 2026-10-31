#!/usr/bin/env python3
"""Generate app/src/main/assets/uipack-editor-schema.json.

The in-app UI pack editor is schema driven: every scalar key from
`UiPack.kt` / `Types.kt` gets a typed control (colour, token, number, bool,
text, enum) and a Chinese label. Structural values (header node trees,
`byTool`, `nav.json`, `strings.json`, windows, ...) stay raw-JSON editors.

Run from the repo root after changing the pack format:

    python3 tools/gen_uipack_schema.py --check   # CI: fail when stale
    python3 tools/gen_uipack_schema.py           # rewrite the asset
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/linuxwff/pigapp"
OUT = ROOT / "app/src/main/assets/uipack-editor-schema.json"
DOC = ROOT / "docs/ui-pack.zh-CN.md"


# --------------------------------------------------------------------------
# Kotlin data class parsing
# --------------------------------------------------------------------------

def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def split_top_level(text: str) -> list[str]:
    parts: list[str] = []
    depth = 0
    current: list[str] = []
    in_string = False
    escaped = False
    for ch in text:
        if in_string:
            current.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
            current.append(ch)
        elif ch in "([{<":
            depth += 1
            current.append(ch)
        elif ch in ")]}>":
            depth -= 1
            current.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    if "".join(current).strip():
        parts.append("".join(current))
    return parts


def parse_classes(path: Path) -> dict[str, list[tuple[str, str, str]]]:
    text = strip_comments(path.read_text())
    classes: dict[str, list[tuple[str, str, str]]] = {}
    for match in re.finditer(r"data class (\w+)\(", text):
        name = match.group(1)
        start = match.end()
        depth = 1
        index = start
        in_string = False
        escaped = False
        while index < len(text) and depth > 0:
            ch = text[index]
            if in_string:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == '"':
                    in_string = False
            elif ch == '"':
                in_string = True
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            index += 1
        body = text[start:index - 1]
        fields: list[tuple[str, str, str]] = []
        for chunk in split_top_level(body):
            chunk = chunk.strip()
            m = re.match(r"val (\w+):\s*(.+?)\s*=\s*(.+)$", chunk, flags=re.S)
            if not m:
                continue
            field, typ, default = m.group(1), m.group(2).strip(), m.group(3).strip()
            fields.append((field, typ, default))
        classes[name] = fields
    return classes


def kotlin_unquote(raw: str) -> str | None:
    m = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', raw.strip(), flags=re.S)
    if not m:
        return None
    return m.group(1).replace('\\"', '"').replace("\\n", "\n")


def parse_default(raw: str, typ: str):
    raw = raw.strip().rstrip(",")
    if raw == "null":
        return None
    if raw == "true":
        return True
    if raw == "false":
        return False
    color = re.fullmatch(r"Color\(0x([0-9A-Fa-f]{8})\)", raw)
    if color:
        argb = color.group(1).upper()
        if argb.startswith("FF"):
            return f"#{argb[2:]}"
        return f"#{argb}"
    if "Color.Transparent" in raw:
        return "#00000000"
    quoted = kotlin_unquote(raw)
    if quoted is not None:
        return quoted
    if re.fullmatch(r"-?\d+", raw):
        return int(raw)
    if re.fullmatch(r"-?\d+\.\d+[fF]?", raw):
        return float(raw.rstrip("fF"))
    if re.fullmatch(r"-?\d+[fF]", raw):
        return float(raw[:-1])
    if raw.startswith("listOf("):
        inner = raw[len("listOf("):-1]
        return [parse_default(p, "String") for p in split_top_level(inner)]
    if raw in ("emptyList()", "setOf()", "emptySet()"):
        return []
    if raw in ("emptyMap()", "mapOf()", "JSONObject()"):
        return {}
    return None


# --------------------------------------------------------------------------
# Section mapping
# --------------------------------------------------------------------------

# data class -> (file, json path, group id, Chinese title, help)
SECTIONS = {
    "UiTheme": ("theme/colors.json", [], "theme", "主题色",
                "全局配色、圆角、阴影与透明度。"),
    "TypeScale": ("theme/typography.json", [], "typography", "排版",
                  "字号、字重、行高、字体族与字距。"),
    "UiComponents": ("components.json", [], "components", "组件开关",
                     "组件显隐、尺寸与文本行数上限。"),
    "UiBubbleAppearance": ("layout/chat.json", ["chat", "bubble"], "bubble",
                           "聊天气泡", "气泡内边距、边框、圆角与配色。"),
    "UiToolCardAppearance": ("layout/chat.json", ["chat", "toolCard"], "toolCard",
                             "工具卡片", "工具调用卡片的尺寸、配色与单工具覆盖。"),
    "UiThinkingAppearance": ("layout/chat.json", ["chat", "thinking"], "thinking",
                             "思考块", "推理过程折叠块的显隐与样式。"),
    "UiMarkdownAppearance": ("layout/chat.json", ["chat", "markdown"], "markdown",
                             "Markdown", "正文里的链接、行内代码、代码块、表格与引用。"),
    "UiChromeAppearance": ("layout/chat.json", ["chat", "chrome"], "chrome",
                           "Chrome", "状态栏、Toast、对话框、筛选胶囊、回到底部按钮。"),
    "UiDrawerAppearance": ("layout/chat.json", ["drawer"], "drawer",
                           "抽屉", "侧边抽屉的背景、间距、按钮与会话行。"),
    "UiHeader": ("layout/chat.json", ["header"], "header",
                 "顶栏", "顶栏容器、简单行（rows）与像素级节点树（body）。"),
}

# UiLayout fields are spread over several objects in layout/chat.json.
CHAT_FIELDS = [
    "assistantLabel", "toolCards", "toolGroups", "showUsage",
    "chatPaddingH", "chatPaddingV", "chatBottomPadding", "messageSpacing",
    "emptyAvatarSize", "codeBlockRadius", "codeBlockBorderAlpha",
]
COMPOSER_FIELDS = [f for f in (
    "composerPlaceholder composerMaxLines sendOnEnter composerFontSize "
    "composerTextColor composerMinHeight composerPaddingH composerPaddingV "
    "composerRadius composerInputRadius composerInputAlpha "
    "composerInputBorderAlpha composerElevation composerSurfaceAlpha "
    "composerButtonIconSize composerAttachTint composerAttachBackground "
    "composerAttachBackgroundAlpha composerAttachGap composerAttachIconSize "
    "composerInputSurface composerCursorColor composerPlaceholderAlpha "
    "composerSendStyle composerSendTint composerSendBackground "
    "composerSendGlow composerStopBackground composerStopBackgroundAlpha "
    "composerStopTint composerThumbSize composerThumbRadius composerThumbGap "
    "composerInnerPaddingH composerInnerPaddingV composerMarginH composerMarginV "
    "composerMaxWidth composerMaxHeight composerSelectionColor "
    "composerSelectionAlpha composerSelectionHandleColor composerDisabledSurface "
    "composerDisabledBorderAlpha composerDisabledTextColor "
    "composerErrorBorderColor composerErrorTextColor composerFontFamily "
    "composerLineHeight composerLetterSpacing composerTextAlign "
    "composerPlaceholderFontSize composerSendDisabledBackground "
    "composerSendDisabledTint composerSendShape composerStopShape "
    "composerAttachShape composerSendRadius composerStopRadius "
    "composerAttachRadius composerSendSize composerStopSize composerAttachSize "
    "composerSendPadding composerStopPadding composerAttachPadding "
    "composerStopBorderWidth composerStopBorderColor composerPlaceholderColor"
).split()]
LAYOUT_GROUPS = [
    ("chat", "聊天区", "layout/chat.json", ["chat"], CHAT_FIELDS,
     "消息间距、内边距、工具卡折叠模式与代码块。"),
    ("composer", "输入框", "layout/chat.json", ["composer"], COMPOSER_FIELDS,
     "输入框、发送/停止/附件按钮、缩略图与选区。"),
    ("drawer", "抽屉", "layout/chat.json", ["drawer"], None,
     "侧边抽屉（由 UiDrawerAppearance 生成）。"),
]

LAYOUT_KEYS = {
    "sidebarWidthDp": "sidebar.widthDp",
    "chatPaddingH": "chat.paddingH",
    "chatPaddingV": "chat.paddingV",
    "chatBottomPadding": "chat.bottomPadding",
    "composerPlaceholder": "composer.placeholder",
    "composerInputBorderAlpha": "composer.borderAlpha",
    "composerPlaceholderColor": "composer.placeholderColor",
    "showSkinSwitcher": "skinSwitcher.show",
    "skinSwitcherPlacement": "skinSwitcher.placement",
    "skinSwitcherStyle": "skinSwitcher.style",
    "showPackManager": "packManager.show",
    "packManagerPlacement": "packManager.placement",
}
COMPOSER_KEY_OVERRIDES = {
    "composerPlaceholder": "placeholder",
    "composerInputBorderAlpha": "borderAlpha",
}


def layout_field_info(field: str) -> tuple[str, str, str] | None:
    """field -> (group id, json path, json key)."""
    if field in LAYOUT_KEYS:
        path = LAYOUT_KEYS[field]
        group = path.split(".")[0]
        return group, path, path.split(".")[-1]
    if field.startswith("composer"):
        key = COMPOSER_KEY_OVERRIDES.get(field)
        if key is None:
            key = field[len("composer"):]
            key = key[0].lower() + key[1:]
        return "composer", f"composer.{key}", key
    if field in ("sendOnEnter",):
        return "composer", f"composer.{field}", field
    if field in CHAT_FIELDS:
        key = LAYOUT_KEYS.get(field, field)
        key = key.split(".")[-1]
        return "chat", f"chat.{key}", key
    if field in ("drawer", "bubble", "toolCard", "thinking", "markdown",
                 "chrome", "header", "settings", "dataSources", "bindings",
                 "actions"):
        return None
    return None

# --------------------------------------------------------------------------
# Labels, options, ranges
# --------------------------------------------------------------------------

TOKENS = [
    "primary", "secondary", "tertiary", "onPrimary", "onSurface",
    "onSurfaceVariant", "surface", "surfaceVariant", "background",
    "outline", "outlineVariant", "error", "success", "warning",
    "none", "transparent",
]

LABELS = {
    # theme
    "background": "背景", "surface": "表面", "surfaceVariant": "次表面",
    "primary": "主色", "secondary": "次色", "accent": "强调色",
    "text": "正文", "textMuted": "次要文字", "danger": "危险色",
    "cornerRadius": "全局圆角", "avatarShadow": "头像光晕",
    "bubbleShadow": "气泡阴影", "onPrimary": "主色上的文字",
    "onSecondary": "次色上的文字", "onAccent": "强调色上的文字",
    "outline": "边框色", "outlineVariant": "淡边框", "success": "成功色",
    "warning": "警告色", "scrim": "遮罩", "bubbleStart": "气泡渐变起点",
    "bubbleEnd": "气泡渐变终点", "cardRadius": "卡片圆角",
    "bubbleRadius": "气泡圆角", "chipRadius": "胶囊圆角",
    "composerRadius": "输入框圆角", "composerElevation": "输入框阴影",
    "bubbleElevation": "气泡阴影", "fabElevation": "回到底部阴影",
    "bubbleStartAlpha": "气泡起点透明度", "bubbleEndAlpha": "气泡终点透明度",
    "bubbleBorderAlpha": "气泡边框透明度", "cardBorderAlpha": "卡片边框透明度",
    "chipAlpha": "胶囊背景透明度",
    # typography
    "body": "正文字号", "label": "标签字号", "h1": "一级标题",
    "h2": "二级标题", "h3": "三级标题", "h4": "四级标题",
    "h5": "五级标题", "h6": "六级标题", "code": "代码字号",
    "lineHeight": "行高", "fontFamily": "正文字体", "headingFontFamily": "标题字体",
    "labelFontFamily": "标签字体", "codeFontFamily": "代码字体",
    "bodyWeight": "正文字重", "headingWeight": "标题字重",
    "labelWeight": "标签字重", "letterSpacing": "字距",
    # components
    "showHeaderAvatar": "顶栏头像", "headerAvatarSize": "顶栏头像尺寸",
    "showHeaderOnline": "在线圆点", "showDrawerBrand": "抽屉品牌区",
    "showPanels": "抽屉面板", "showSessions": "会话列表",
    "showSessionBadges": "会话徽标", "showSessionSize": "会话大小",
    "showCopy": "复制按钮", "showSelection": "文本选择",
    "showSuggestions": "建议", "showReadOnlyBanner": "只读横幅",
    "showPendingBanner": "待处理横幅", "showSteerHint": "插队提示",
    "showStatusBar": "状态栏", "assistantAvatar": "助手头像",
    "messageAvatarSize": "消息头像尺寸", "bubbleMaxWidthDp": "气泡最大宽度",
    "toolSummaryLines": "工具摘要行数", "toolExpandedLines": "工具展开行数",
    "codeFenceMaxLines": "代码块行数", "tableMaxLines": "表格行数",
    "composerButtonSize": "输入框按钮尺寸",
    # chat
    "assistantLabel": "助手名称", "toolCards": "工具卡模式",
    "toolGroups": "工具卡分组", "showUsage": "显示用量",
    "paddingH": "水平内边距", "paddingV": "垂直内边距",
    "bottomPadding": "底部留白", "messageSpacing": "消息间距",
    "emptyAvatarSize": "空状态头像尺寸", "codeBlockRadius": "代码块圆角",
    "codeBlockBorderAlpha": "代码块边框透明度", "widthDp": "宽度",
    # bubble / toolCard / thinking
    "borderWidth": "边框宽度", "textColor": "文字色",
    "assistantBubble": "助手气泡", "assistantBackgroundAlpha": "助手背景透明度",
    "assistantBorderAlpha": "助手边框透明度", "assistantTextColor": "助手文字色",
    "borderColor": "边框色", "assistantBorderColor": "助手边框色",
    "userBackground": "用户气泡背景", "userBackgroundAlpha": "用户气泡背景透明度",
    "textAlign": "文字对齐", "radiusTL": "左上圆角", "radiusTR": "右上圆角",
    "radiusBR": "右下圆角", "radiusBL": "左下圆角",
    "backgroundAlpha": "背景透明度", "radius": "圆角", "borderAlpha": "边框透明度",
    "padding": "内边距", "collapsedPaddingH": "折叠态水平内边距",
    "collapsedPaddingV": "折叠态垂直内边距", "iconBoxSize": "图标框尺寸",
    "iconSize": "图标尺寸", "iconRadius": "图标圆角",
    "iconAlpha": "图标底透明度", "titleSize": "标题字号",
    "metricsColor": "指标色", "errorBorderColor": "错误边框色",
    "titleColor": "标题色", "iconTint": "图标色", "iconBackground": "图标底色",
    "diffAddColor": "新增行色", "diffRemoveColor": "删除行色",
    "diffContextColor": "上下文行色", "codeFontSize": "代码字号",
    "groupRadius": "分组圆角", "groupPadding": "分组内边距",
    "maxWidth": "最大宽度", "byTool": "单工具配色",
    "show": "显示", "collapsedByDefault": "默认折叠",
    "expandWhileStreaming": "流式时展开", "showCount": "显示字数",
    "title": "标题", "fontSize": "字号", "italic": "斜体",
    "maxHeight": "最大高度", "collapsedGlyph": "折叠图标",
    "expandedGlyph": "展开图标",
    # markdown / chrome
    "linkColor": "链接色", "inlineCodeBackground": "行内代码背景",
    "inlineCodeColor": "行内代码文字", "codeBlockBackground": "代码块背景",
    "codeBlockAlpha": "代码块透明度", "codeBlockBorderColor": "代码块边框色",
    "quoteTextColor": "引用文字色", "tableHeaderBackground": "表头背景",
    "tableBorderColor": "表格边框色", "tableBorderAlpha": "表格边框透明度",
    "tableBorderWidth": "网格线宽", "tableColumnLines": "列分隔线",
    "tableOuterBorder": "表格外框", "tableCornerRadius": "表格圆角",
    "tableCellPaddingH": "单元格水平内边距", "tableCellPaddingV": "单元格垂直内边距",
    "tableTextColor": "表格文字色", "tableHeaderTextColor": "表头文字色",
    "tableHeaderBold": "表头加粗", "tableTextSize": "表格字号",
    "tableHeaderTextSize": "表头字号", "tableZebraColor": "斑马纹颜色",
    "tableZebraAlpha": "斑马纹透明度", "hrColor": "分隔线色",
    "statusColor": "状态文字色", "statusSize": "状态字号",
    "statusSpinnerColor": "转圈颜色", "statusSpinnerSize": "转圈尺寸",
    "statusBackground": "状态背景", "statusBackgroundAlpha": "状态背景透明度",
    "statusPaddingH": "状态水平内边距", "statusPaddingV": "状态垂直内边距",
    "toastContainer": "提示条背景", "toastText": "提示条文字",
    "toastAction": "提示条动作", "toastShape": "提示条圆角",
    "toastInsetH": "提示条水平缩进", "dialogSurface": "对话框背景",
    "dialogRadius": "对话框圆角", "dialogMaxWidth": "对话框最大宽度",
    "dialogTitleColor": "对话框标题色", "dialogTextColor": "对话框正文色",
    "chipBackground": "胶囊背景", "chipBackgroundAlpha": "胶囊背景透明度",
    "chipLabelColor": "胶囊文字色", "chipShape": "胶囊形状",
    "emptyTitleColor": "空状态标题色", "fabBackground": "回到底部背景",
    "fabBackgroundAlpha": "回到底部背景透明度", "fabTint": "回到底部图标色",
    "fabTintAlpha": "回到底部图标透明度",
    # drawer
    "gradientAlpha": "渐变透明度", "spacing": "间距",
    "brandAvatarSize": "品牌头像尺寸", "brandTagline": "品牌标语",
    "brandCardAlpha": "品牌卡透明度", "brandPills": "品牌胶囊",
    "buttonHeight": "按钮高度", "buttonRadius": "按钮圆角",
    "buttonIconSize": "按钮图标尺寸", "sectionLabelSize": "分组标题字号",
    "sectionLabelUppercase": "分组标题大写", "sessionRowRadius": "会话行圆角",
    "sessionSelectedAlpha": "选中行背景透明度", "showSessionId": "显示会话 ID",
    "showSessionTime": "显示会话时间", "searchRadius": "搜索框圆角",
    "modules": "抽屉模块", "sizes": "尺寸档位", "name": "名称", "version": "版本",
    # header / layout misc
    "height": "高度", "heightDp": "高度", "divider": "底部分隔线", "body": "节点树",
    "rows": "简单行", "placement": "位置", "style": "样式",
    "defaultSize": "默认尺寸", "showSizeSwitcher": "尺寸切换",
    # composer
    "placeholder": "占位文字", "maxLines": "最大行数",
    "sendOnEnter": "回车发送", "minHeight": "最小高度",
    "inputRadius": "输入框圆角", "inputAlpha": "输入框透明度",
    "elevation": "阴影", "surfaceAlpha": "表面透明度",
    "attachTint": "附件图标色", "attachBackground": "附件底色",
    "attachBackgroundAlpha": "附件底色透明度", "attachGap": "附件间距",
    "attachIconSize": "附件图标尺寸", "inputSurface": "输入框底色",
    "cursorColor": "光标色", "placeholderAlpha": "占位文字透明度",
    "sendStyle": "发送按钮样式", "sendTint": "发送图标色",
    "sendBackground": "发送按钮背景", "sendGlow": "发送按钮光晕",
    "stopBackground": "停止按钮背景", "stopBackgroundAlpha": "停止背景透明度",
    "stopTint": "停止图标色", "thumbSize": "缩略图尺寸",
    "thumbRadius": "缩略图圆角", "thumbGap": "缩略图间距",
    "innerPaddingH": "内层水平内边距", "innerPaddingV": "内层垂直内边距",
    "marginH": "水平外边距", "marginV": "垂直外边距",
    "selectionColor": "选区色", "selectionAlpha": "选区透明度",
    "selectionHandleColor": "选区手柄色", "disabledSurface": "禁用背景",
    "disabledBorderAlpha": "禁用边框透明度", "disabledTextColor": "禁用文字色",
    "errorTextColor": "错误文字色", "placeholderFontSize": "占位文字字号",
    "sendDisabledBackground": "发送禁用背景", "sendDisabledTint": "发送禁用图标色",
    "sendShape": "发送按钮形状", "stopShape": "停止按钮形状",
    "attachShape": "附件按钮形状", "sendRadius": "发送按钮圆角",
    "stopRadius": "停止按钮圆角", "attachRadius": "附件按钮圆角",
    "sendSize": "发送按钮尺寸", "stopSize": "停止按钮尺寸",
    "attachSize": "附件按钮尺寸", "sendPadding": "发送按钮内边距",
    "stopPadding": "停止按钮内边距", "attachPadding": "附件按钮内边距",
    "stopBorderWidth": "停止按钮边框宽度", "stopBorderColor": "停止按钮边框色",
    "placeholderColor": "占位文字颜色",
}

OPTIONS = {
    ("chat", "toolCards"): ["collapsible", "expanded", "collapsed"],
    ("thinking", "background"): ["surface", "surfaceVariant", "background", "transparent"],
    ("toolCard", "background"): ["surface", "surfaceVariant", "background", "transparent"],
    ("drawer", "background"): ["theme", "surface", "gradient", "transparent"],
    ("header", "background"): ["gradient", "surface", "transparent"],
    ("bubble", "textAlign"): ["start", "center", "end"],
    ("composer", "textAlign"): ["start", "center", "end"],
    ("composer", "sendStyle"): ["brand", "solid"],
    ("composer", "sendShape"): ["circle", "rounded"],
    ("composer", "stopShape"): ["circle", "rounded"],
    ("composer", "attachShape"): ["circle", "rounded"],
    ("chrome", "chipShape"): ["circle", "rounded"],
    ("skinSwitcher", "placement"): ["drawer", "settings", "both"],
    ("packManager", "placement"): ["drawer", "settings", "both"],
    ("skinSwitcher", "style"): ["chips", "list"],
    ("bubble", "userBackground"): ["brand"] + TOKENS,
    ("chat", "assistantLabel"): None,  # plain text
}

JSON_FIELDS = {
    ("header", "body"), ("header", "rows"), ("toolCard", "byTool"),
    ("settings", "sizes"), ("drawer", "modules"),
}

TOKEN_KEYS = {
    "textColor", "borderColor", "assistantTextColor", "assistantBorderColor",
    "userBackground", "statusColor", "statusSpinnerColor", "statusBackground",
    "toastContainer", "toastText", "toastAction", "dialogSurface",
    "dialogTitleColor", "dialogTextColor", "chipBackground", "chipLabelColor",
    "emptyTitleColor", "fabBackground", "fabTint", "linkColor",
    "inlineCodeBackground", "inlineCodeColor", "codeBlockBackground",
    "codeBlockBorderColor", "quoteTextColor", "tableHeaderBackground",
    "tableBorderColor", "hrColor", "metricsColor", "titleColor", "iconTint",
    "iconBackground", "diffAddColor", "diffRemoveColor", "diffContextColor",
    "attachTint", "attachBackground", "inputSurface", "cursorColor",
    "sendTint", "sendBackground", "stopBackground", "stopTint",
    "selectionColor", "selectionHandleColor", "disabledSurface",
    "disabledTextColor", "errorBorderColor", "errorTextColor",
    "sendDisabledBackground", "sendDisabledTint", "stopBorderColor",
    "placeholderColor",
}

RANGE_OVERRIDES = {
    ("typography", "body"): (8, 40), ("typography", "label"): (8, 40),
    ("typography", "code"): (8, 40),
    ("components", "headerAvatarSize"): (16, 96),
    ("components", "messageAvatarSize"): (12, 64),
    ("components", "bubbleMaxWidthDp"): (200, 1200),
    ("components", "toolSummaryLines"): (1, 20),
    ("components", "toolExpandedLines"): (20, 2000),
    ("components", "codeFenceMaxLines"): (20, 2000),
    ("components", "tableMaxLines"): (1, 20),
    ("components", "composerButtonSize"): (32, 72),
    ("composer", "maxWidth"): (0, 1200), ("composer", "maxHeight"): (0, 600),
    ("composer", "maxLines"): (1, 12), ("composer", "minHeight"): (0, 200),
    ("thinking", "maxHeight"): (60, 800),
    ("markdown", "tableBorderWidth"): (0, 4),
    ("markdown", "tableCellPaddingH"): (0, 24),
    ("markdown", "tableCellPaddingV"): (0, 24),
    ("markdown", "tableTextSize"): (0, 32),
    ("markdown", "tableHeaderTextSize"): (0, 32),
    ("toolCard", "maxWidth"): (0, 2000),
    ("chrome", "dialogMaxWidth"): (0, 1200),
    ("header", "height"): (0, 200),
    ("sidebar", "widthDp"): (240, 560),
    ("chat", "emptyAvatarSize"): (16, 128),
    ("drawer", "brandAvatarSize"): (16, 96),
    ("drawer", "buttonHeight"): (0, 96),
}


def field_type(group: str, field: str, typ: str) -> str:
    if (group, field) in JSON_FIELDS:
        return "json"
    if (group, field) in OPTIONS:
        return "options"
    if typ == "Color" or typ == "Color?":
        return "color"
    if typ == "Boolean":
        return "bool"
    if typ == "Int" or typ == "Int?":
        return "int"
    if typ == "Float":
        return "float"
    if field in ("fontFamily", "headingFontFamily", "labelFontFamily", "codeFontFamily"):
        return "font"
    if field in TOKEN_KEYS or field.endswith("Color") or field.endswith("Tint") or \
            field.endswith("Surface") or (group in ("thinking", "toolCard") and field == "background"):
        return "token"
    return "string"


def range_for(group: str, field: str, default, typ: str):
    if (group, field) in RANGE_OVERRIDES:
        return RANGE_OVERRIDES[(group, field)]
    if typ == "float":
        if field in ("lineHeight", "composerLineHeight"):
            return (1.0, 2.5)
        if field == "letterSpacing" or field == "composerLetterSpacing":
            return (-2.0, 4.0)
        if isinstance(default, (int, float)) and 0 <= default <= 1:
            return (0.0, 1.0)
        return (0.0, 10.0)
    if typ == "int":
        if isinstance(default, int) and default < 0:
            return (-1, 40)
        if isinstance(default, int) and default > 64:
            return (0, max(64, default * 2))
        return (0, 64)
    return None


def step_for(field: str, typ: str):
    if typ == "int":
        return 1
    if typ == "float":
        return 0.01 if "Alpha" in field else 0.1
    return None


# --------------------------------------------------------------------------
# Docs descriptions
# --------------------------------------------------------------------------

def doc_descriptions() -> dict[tuple[str, str], str]:
    """Pull the 说明 column out of docs/ui-pack.zh-CN.md tables.

    Headings such as `### 聊天气泡（chat.bubble）` or `## header` set the
    current section. Keys are indexed by section id and by the section path so
    two sections that both use `background` do not steal each other's hint.
    """
    if not DOC.is_file():
        return {}
    out: dict[tuple[str, str], str] = {}

    def index(section: str, path: str, key: str, desc: str) -> None:
        for name in {section, path, path.split(".")[-1]} - {""}:
            out.setdefault((name, key), desc)

    section = ""
    path = ""
    for line in DOC.read_text().splitlines():
        if line.startswith("#"):
            heading = line.lstrip("#").strip()
            ticked = re.search(r"`([a-zA-Z0-9_.\/-]+)`", heading)
            path = ticked.group(1) if ticked else ""
            if path.endswith(".json"):
                path = ""
            section = path.split(".")[-1] if path else ""
            if not section:
                file_match = re.search(r"([a-zA-Z0-9_-]+)\.json", heading)
                if file_match:
                    section = file_match.group(1)
                    path = section
                elif heading.lower() in ("header",):
                    section = "header"
                    path = "header"
            continue
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) < 2 or not cells[0].startswith("`"):
            continue
        key = cells[0].strip("`").split(".")[-1]
        if " / " in cells[0]:
            key = cells[0].strip("`").split(" / ")[0]
        desc = re.sub(r"[*_`]", "", cells[-1]).strip()
        if not desc or desc.startswith("---"):
            continue
        index(section, path, key, desc)
    return out


# --------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------

def build() -> dict:
    classes = {}
    classes.update(parse_classes(SRC / "UiPack.kt"))
    classes.update(parse_classes(SRC / "Types.kt"))
    docs = doc_descriptions()

    groups: dict[str, dict] = {}
    order: list[str] = []

    def group(gid: str, title: str, file: str, path: list[str], desc: str) -> dict:
        if gid not in groups:
            groups[gid] = {
                "id": gid, "title": title, "file": file, "path": path,
                "desc": desc, "fields": [],
            }
            order.append(gid)
        return groups[gid]

    def add_field(gid: str, title: str, file: str, path: list[str], desc: str,
                  field: str, typ: str, default, key: str | None = None,
                  extra: dict | None = None) -> None:
        g = group(gid, title, file, path, desc)
        key = key or field
        ftype = field_type(gid, key, typ)
        entry = {
            "key": key,
            "path": path + [key],
            "type": ftype,
            "label": LABELS.get(key, LABELS.get(field, key)),
            "default": default,
        }
        if extra:
            entry.update(extra)
        rng = range_for(gid, key, default, ftype)
        if rng:
            entry["min"], entry["max"] = rng
        step = step_for(key, ftype)
        if step is not None:
            entry["step"] = step
        if (gid, key) in OPTIONS and OPTIONS[(gid, key)]:
            entry["options"] = OPTIONS[(gid, key)]
        elif ftype == "token":
            entry["options"] = TOKENS
        if (gid, key) in OPTIONS and OPTIONS[(gid, key)] is None:
            entry["type"] = "string"
        if typ == "Color?" or typ == "Int?":
            entry["nullable"] = True
        help_text = docs.get((gid, key)) or docs.get(("", key))
        if help_text and len(help_text) <= 80:
            entry["desc"] = help_text
        g["fields"].append(entry)

    # Scalar appearance classes.
    field_keys = {("header", "heightDp"): "height"}
    for cls, (file, path, gid, title, desc) in SECTIONS.items():
        for field, typ, raw_default in classes.get(cls, []):
            default = parse_default(raw_default, typ)
            add_field(gid, title, file, path, desc, field, typ, default,
                      key=field_keys.get((gid, field)))

    # UiLayout: chat / composer / drawer / misc groups.
    layout_fields = {f: (t, d) for f, t, d in classes.get("UiLayout", [])}
    for name in CHAT_FIELDS:
        typ, raw = layout_fields[name]
        add_field("chat", "聊天区", "layout/chat.json", ["chat"],
                  "消息间距、内边距、工具卡折叠模式与代码块。",
                  name, typ, parse_default(raw, typ), key=LAYOUT_KEYS.get(name, name).split(".")[-1])
    for name in COMPOSER_FIELDS:
        typ, raw = layout_fields[name]
        key = COMPOSER_KEY_OVERRIDES.get(name)
        if key is None:
            key = name[len("composer"):] if name.startswith("composer") else name
            key = key[0].lower() + key[1:]
        add_field("composer", "输入框", "layout/chat.json", ["composer"],
                  "输入框、发送/停止/附件按钮、缩略图与选区。",
                  name, typ, parse_default(raw, typ), key=key)
    if "sidebarWidthDp" in layout_fields:
        typ, raw = layout_fields["sidebarWidthDp"]
        add_field("sidebar", "侧边栏", "layout/chat.json", ["sidebar"],
                  "抽屉宽度。", "sidebarWidthDp", typ, parse_default(raw, typ),
                  key="widthDp")
    for name, key, desc in (
        ("showSkinSwitcher", "show", "在抽屉/设置里显示皮肤切换器。"),
        ("skinSwitcherPlacement", "placement", "皮肤切换器出现的位置。"),
        ("skinSwitcherStyle", "style", "胶囊或列表。"),
    ):
        typ, raw = layout_fields[name]
        add_field("skinSwitcher", "皮肤切换", "layout/chat.json", ["skinSwitcher"],
                  "皮肤（skins.json）切换器。", name, typ, parse_default(raw, typ), key=key)
    for name, key in (
        ("showPackManager", "show"),
        ("packManagerPlacement", "placement"),
    ):
        typ, raw = layout_fields[name]
        add_field("packManager", "pack 管理", "layout/chat.json", ["packManager"],
                  "内置的 UI pack 管理入口。", name, typ, parse_default(raw, typ), key=key)

    # settings object (layout/chat.json)
    typ, raw = layout_fields.get("settings", ("UiSettingsLayout", ""))
    for f, t, r in classes.get("UiSettingsLayout", []):
        if f == "sizes":
            add_field("settings", "设置对话框", "layout/chat.json", ["settings"],
                      "设置对话框的尺寸档位。", f, t, [
                          {"id": "compact", "label": "紧凑", "width": 0.78, "height": 0.60},
                          {"id": "standard", "label": "标准", "width": 0.92, "height": 0.82},
                          {"id": "large", "label": "大", "width": 0.97, "height": 0.94},
                          {"id": "full", "label": "全屏", "width": 1.0, "height": 1.0},
                      ], key="sizes")
        else:
            add_field("settings", "设置对话框", "layout/chat.json", ["settings"],
                      "设置对话框的尺寸档位。", f, t, parse_default(r, t))

    # manifest
    add_field("manifest", "包信息", "manifest.json", [], "pack 名称与版本。",
              "name", "String", "Pig default")
    add_field("manifest", "包信息", "manifest.json", [], "pack 名称与版本。",
              "version", "Int", 1)

    raw = [
        {"id": "strings", "title": "界面文案", "file": "strings.json",
         "desc": "key → 文案；点号表示嵌套，例如 header.online。", "type": "json"},
        {"id": "icons", "title": "图标映射", "file": "icons.json",
         "desc": "逻辑图标名 → 内置图标名（menu/send/settings/…）。", "type": "json"},
        {"id": "nav", "title": "抽屉入口", "file": "nav.json",
         "desc": "{\"items\":[{\"id\",\"label\",\"window\",\"command\",\"icon\"}]}。", "type": "json"},
        {"id": "skins", "title": "皮肤", "file": "skins.json",
         "desc": "{\"default\":\"id\",\"skins\":[{\"id\",\"name\",\"theme\":{…}}]}。", "type": "json"},
        {"id": "dataSources", "title": "数据源", "file": "layout/chat.json",
         "path": ["dataSources"], "desc": "RPC 数据源：名称 → {rpc, params, refresh}。", "type": "json"},
        {"id": "bindings", "title": "绑定", "file": "layout/chat.json",
         "path": ["bindings"], "desc": "槽位 → JSON 路径或 {path, format, fallback}。", "type": "json"},
        {"id": "actions", "title": "动作", "file": "layout/chat.json",
         "path": ["actions"], "desc": "命名动作 → {rpc, params, confirm}。", "type": "json"},
    ]

    # Stable order: curated first, then anything the parser found later.
    preferred = ["theme", "typography", "components", "chat", "composer", "bubble",
                 "toolCard", "thinking", "markdown", "chrome", "drawer", "header",
                 "sidebar", "skinSwitcher", "packManager", "settings", "manifest"]
    ordered = [groups[g] for g in preferred if g in groups]
    ordered += [groups[g] for g in order if g not in preferred]

    return {"version": 1, "generatedBy": "tools/gen_uipack_schema.py",
            "groups": ordered, "raw": raw}


def main() -> int:
    data = build()
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    if "--check" in sys.argv:
        current = OUT.read_text() if OUT.is_file() else ""
        if current != text:
            print(f"{OUT} is stale; run tools/gen_uipack_schema.py", file=sys.stderr)
            return 1
        print("schema is up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    fields = sum(len(g["fields"]) for g in data["groups"])
    print(f"wrote {OUT} ({len(data['groups'])} groups, {fields} fields, "
          f"{len(data['raw'])} raw sections)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
