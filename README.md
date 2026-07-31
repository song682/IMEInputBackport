# IMEInputBackport

[English](#english) | [中文](#中文)

Backports modern (1.19.2-style) CJK input method support to **Minecraft 1.7.10 Forge** —
inline pre-edit text, a candidate window that follows the caret, and sane key
handling while composing. Type Chinese/Japanese/Korean in chat like it's 2022.

---

## English

### What it does

Vanilla 1.7.10 predates proper IME integration: composing CJK text in chat
means typing blind into a system window floating in a corner (or nothing at
all in fullscreen). This mod hooks the OS input method directly into the game:

- **Inline composition** — the pre-edit string renders right at the text
  caret inside chat, sign, book and anvil text fields.
- **Candidate window** — either the OS/IME native one positioned next to the
  caret, or a mod-drawn overlay that is fully
  [themeable via resource packs](docs/THEME.md).
- **Correct key semantics while composing** — letter keys don't leak into the
  text field twice, `Escape` cancels the composition instead of closing the
  screen, and everything passes through untouched when you're not composing.

### Platform support

| Platform | Backend | Notes |
|---|---|---|
| Windows x64 / x86 / arm64 | **TSF**, falls back to **IMM32** | works with MS-Pinyin, Sogou, Rime (weasel), Microsoft IME, … |
| Linux x64 | **IBus** (D-Bus, X11 / XWayland) | requires a running `ibus-daemon`; tested with Rime (ibus-rime) |
| Linux arm64 | — | not supported |
| macOS | — | not supported |

The native libraries are bundled inside the jar and extracted at runtime — no
manual setup. The Linux backend binds `libdbus-1.so.3` at runtime via
`dlopen`, so there is no extra package to install beyond a working IBus setup.

### Requirements



Qoder
### Installation

Drop the IMEInputBackport jar and CatFrame into your `mods/` folder. Done.

### Configuration (`config/imeinput.cfg`)

| Key | Default | Meaning |
|---|---|---|
| `general.backend` | `AUTO` | `AUTO` matches the platform (Windows: TSF → IMM32 fallback; Linux: IBus). `TSF` / `IMM32` force a Windows backend, `IBUS` forces IBus on Linux. Values for the other platform degrade to `AUTO`. |
| `general.candidateNativeBar` | `true` | `true` = the OS/IME draws its own candidate window; `false` = hide it and let the mod self-draw the candidate box (stacked under the pre-edit box, themeable). Needs a game restart. |

The look of everything the mod draws is controlled by
`assets/imeinput/ime_theme.json`, overridable by any resource pack —
see **[docs/THEME.md](docs/THEME.md)**.

### Building

```bash
# Java side (ForgeGradle 1.2 needs JDK 8 to run the toolchain)
JAVA_HOME=/path/to/jdk8 ./gradlew build
```

All native libraries (`JNILibIMEInput-{x64,x86,arm64}.dll` and
`libJNILibIMEInput-linux-x64.so`) are prebuilt and checked into
`src/main/resources/`; their C/C++ sources are maintained separately.

### How it works (short version)

```
GuiScreen keyboard event ─(CatFrame KeyboardInputEvent.Pre)─▶ IMEHandler
        │                                                        │
        │   Windows: OS routes keys to the IME automatically     ▼
        │   Linux:   IMEHandler forwards X11 keysyms         IMEBridge (JNI)
        │            to ibus-daemon via D-Bus ProcessKey     ┌──────┴──────┐
        ▼                                                    ▼             ▼
  consumed while composing?  ◀── callbacks (composition/  TSF / IMM32    IBus
  cancel event : pass through     candidates / commit)    (Windows)     (Linux)
```

One `IMEBridge` facade dispatches to the active backend; `IMEHandler` consumes
callback snapshots on the client tick and feeds the overlay. The event is only
ever cancelled when the IME actually consumed the key — everything else is
passed through, always.

### License

[MIT](LICENSE)

> Note: JNI implement see [here](https://github.com/song682/potential-C-JNI-IME-solutions)

---

## 中文

把现代版本（1.19.2 风格）的 CJK 输入法支持移植回 **Minecraft 1.7.10 Forge**：
内联组字、跟随光标的候选窗、组字期间正确的按键语义。在聊天框里正常打中文。

### 功能

- **内联组字**：拼音等 pre-edit 文本直接渲染在聊天框、告示牌、成书、铁砧
  输入框的光标处；
- **候选窗**：可用系统/输入法原生候选窗（自动定位到光标旁），也可切换为
  模组自绘候选框，外观可通过资源包完全[自定义主题](docs/THEME.md)；
- **组字期间按键语义正确**：字母键不会二次落入输入框，组字中按 `Escape`
  只取消组字不关界面；未组字时一切按键原样放行。

### 平台支持

| 平台 | 后端 | 说明 |
|---|---|---|
| Windows x64 / x86 / arm64 | **TSF**，失败降级 **IMM32** | 微软拼音、搜狗、小狼毫（Rime）等均可 |
| Linux x64 | **IBus**（D-Bus，X11 / XWayland） | 需要 `ibus-daemon` 在运行；已用 Rime（ibus-rime）实测 |
| Linux arm64 / macOS | — | 不支持 |

原生库打包在 jar 内、运行时自动解压加载，无需手动安装；Linux 后端通过
`dlopen` 在运行时绑定 `libdbus-1.so.3`，除正常的 IBus 环境外无额外依赖。

### 前置与安装

- Minecraft **1.7.10** + Forge 10.13.4，Java 8
- **[CatFrame](https://github.com/song682/CatFrame) ≥ 0.5.0**（硬前置，
  提供 GUI 键盘事件桥与 Overlay 管理器）

把本模组和 CatFrame 一起丢进 `mods/` 即可。

### 配置（`config/imeinput.cfg`）

| 配置项 | 默认 | 含义 |
|---|---|---|
| `general.backend` | `AUTO` | `AUTO` 按平台自动匹配（Windows 先试 TSF 失败降级 IMM32，Linux 走 IBus）；`TSF` / `IMM32` 强制 Windows 后端；`IBUS` 强制 Linux IBus。跨平台取值等同 `AUTO`。 |
| `general.candidateNativeBar` | `true` | `true` = 系统/输入法自绘候选窗；`false` = 隐藏原生候选窗，由模组自绘候选框（堆叠在组字框下方，支持主题）。修改后需重启游戏。 |

模组自绘部分的外观由 `assets/imeinput/ime_theme.json` 驱动，任何资源包
均可覆盖，详见 **[docs/THEME.md](docs/THEME.md)**。

### 构建

```bash
# Java 侧（ForgeGradle 1.2 工具链需要用 JDK 8 运行）
JAVA_HOME=/path/to/jdk8 ./gradlew build
```

全部原生库（`JNILibIMEInput-{x64,x86,arm64}.dll` 与
`libJNILibIMEInput-linux-x64.so`）均为预编译产物，随仓库入库于
`src/main/resources/`，其 C/C++ 源码单独维护。

### 许可

[MIT](LICENSE)

> JNI 的实现[见此](https://github.com/song682/potential-C-JNI-IME-solutions)