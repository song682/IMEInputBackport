# IME Theme Customization Guide

So you want the in-game composition window to look like *yours*? Good news —
the whole look of the IME overlay is driven by a single JSON file that any
resource pack can override. No config edits, no code, just a text file and
(optionally) one texture.

## How it works

The mod ships a default theme descriptor at:

```
assets/imeinput/ime_theme.json
```

Drop a file at the **same path** inside your resource pack and it wins —
that's it. The theme is wired into Minecraft's resource reload cycle, so
switching packs or pressing `F3 + T` applies your changes instantly, mid-game.

If your file is missing or broken (bad JSON, typo'd color), the mod quietly
falls back to its built-in defaults and logs a warning — the overlay never
breaks, it just ignores what it can't read.

> **Scope note:** the theme controls *appearance only*. Behavior switches like
> `candidateNativeBar` (whether the OS input method draws its own candidate
> window) live in `config/imeinput.cfg`, not here. When the native candidate
> bar is active, that window belongs to the IME itself — no theme can restyle
> it; the theme applies to everything the mod draws.

## The full descriptor

Here's the bundled default, which doubles as a template — copy it, tweak it,
ship it:

```json
{
  "style": "FLAT",
  "mergeSections": true,
  "window": {
    "texture": "imeinput:textures/gui/ime_window.png",
    "textureSize": 32,
    "cornerSize": 4,
    "borderColor": "#FF34343C",
    "backgroundColor": "#F0141418"
  },
  "colors": {
    "separator": "#FF3C3C46",
    "text": "#FFE0E0E0",
    "focusedText": "#FFFFFFFF",
    "underline": "#FFFFFFFF",
    "caret": "#FFD0D0D0",
    "selectedBackground": "#EBB2DAE0",
    "selectedText": "#FF202020"
  }
}
```

Every field is optional — leave one out and the built-in default fills in.

## Top-level fields

| Field | Type | Default | What it does |
|---|---|---|---|
| `style` | string | `"FLAT"` | Window chrome style: `FLAT` or `TEXTURE` (case-insensitive). |
| `mergeSections` | boolean | `true` | Whether the PreEdit (composition) section and the self-drawn candidate section share one window. |
| `preeditWindow` | object | *absent* | Optional separate skin for the standalone PreEdit window — see [its own section](#the-preeditwindow-object) below. |

### `style`

- **`FLAT`** — pure self-drawn look: a 1px border rectangle wrapped around a
  solid interior fill. Zero texture dependency; colored entirely by
  `window.borderColor` and `window.backgroundColor`.
- **`TEXTURE`** — the window chrome is a nine-slice stretch of the texture
  named in `window.texture`. Border and background colors are ignored in this
  mode; the texture *is* the chrome.

### `mergeSections`

Only matters when the mod draws candidates itself (`candidateNativeBar=false`
in `config/imeinput.cfg`):

- **`true`** — one window holds both: composition text on top, candidates
  below, split by a thin inset separator line (the `colors.separator` line,
  indented 2px from each side).
- **`false`** — two standalone windows stacked vertically with a 2px gap,
  each one shrink-wrapped to its own content width.

With the native candidate bar on (the default), there's only ever the PreEdit
window on the mod's side, so this flag changes nothing there.

## The `window` object

| Field | Type | Default | What it does |
|---|---|---|---|
| `texture` | string | `imeinput:textures/gui/ime_window.png` | Resource location of the nine-slice window texture (`TEXTURE` style only). |
| `textureSize` | int | `32` | Edge length of that texture, in pixels. |
| `cornerSize` | int | `4` | Size of the fixed nine-slice corners, in pixels. |
| `borderColor` | color | `#FF34343C` | 1px window border (`FLAT` style only). |
| `backgroundColor` | color | `#F0141418` | Window interior fill (`FLAT` style only). |

### Nine-slice texture spec

For the `TEXTURE` style, the window is cut into a 3×3 grid:

```
┌───┬────────┬───┐
│ ↖ │   ↕    │ ↗ │   corners (cornerSize × cornerSize): drawn as-is
├───┼────────┼───┤
│ ↔ │  ↔ ↕   │ ↔ │   edges: stretched along one axis
├───┼────────┼───┤
│ ↙ │   ↕    │ ↘ │   center: stretched both ways
└───┴────────┴───┘
```

The bundled texture is 32×32 with 4px corners. You can supply any square
texture — just keep `textureSize` and `cornerSize` in sync with the actual
image, and point `texture` at your own file (e.g.
`mypack:textures/gui/fancy_window.png`, which lives at
`assets/mypack/textures/gui/fancy_window.png` inside your pack). When the
window gets smaller than two corners, the corners are clamped proportionally
so they never overlap — tiny compositions still render cleanly.

Semi-transparency works: the texture's alpha channel is respected, so frosted
or glassy chromes are fair game.

## The `preeditWindow` object

Sometimes the composition box and the candidate box shouldn't dress alike —
say, a slim borderless strip for the text you're typing and a chunky framed
panel for the candidates. That's what this optional block is for: it reskins
the **standalone PreEdit window**, which shows up in two situations:

- **split mode** (`mergeSections: false`) — the top window of the pair;
- **native candidate bar mode** (`candidateNativeBar=true` in the config) —
  the mod only ever draws a PreEdit window there, and this is it.

The merged single window and the split-mode candidate window always wear the
base `window` skin — `preeditWindow` never touches those.

| Field | Falls back to | Notes |
|---|---|---|
| `style` | top-level `style` | Yes, the two windows may mix styles — FLAT preedit over a TEXTURE candidate box is perfectly legal. |
| `texture` | `window.texture` | Its own nine-slice texture, if `style` resolves to `TEXTURE`. |
| `textureSize` | `window.textureSize` | |
| `cornerSize` | `window.cornerSize` | |
| `borderColor` | `window.borderColor` | `FLAT` style only. |
| `backgroundColor` | `window.backgroundColor` | `FLAT` style only. |

Inheritance is per-field: set just `backgroundColor` and everything else
keeps tracking the base window. Leave the whole block out and the PreEdit
window is pixel-identical to the base one — which is exactly the shipped
default.

One deliberate omission: there's no separate `colors` set for the PreEdit
window. Text, underline and caret colors come from the shared `colors`
palette regardless of which window they land in — the block skins the chrome,
not the content.

> In merged mode (`mergeSections: true` with self-drawn candidates), the
> single window keeps the base skin even during the brief moments when no
> candidates are visible — otherwise the chrome would flicker between two
> looks as the candidate list pops in and out.

## The `colors` object

These apply to both styles — they color the *content*, not the chrome.

| Field | Default | Paints |
|---|---|---|
| `separator` | `#FF3C3C46` | The 1px inset line between the two sections (merged single-window mode only). |
| `text` | `#FFE0E0E0` | Regular composition text and unselected candidates. |
| `focusedText` | `#FFFFFFFF` | The clause currently being converted (the "focused block"). |
| `underline` | `#FFFFFFFF` | Underline beneath each composition clause (2px under the focused one, 1px elsewhere). |
| `caret` | `#FFD0D0D0` | The vertical caret bar inside the composition string. |
| `selectedBackground` | `#EBB2DAE0` | Highlight pill behind the selected candidate. |
| `selectedText` | `#FF202020` | Text color of the selected candidate. |

## Color format

Three shapes are accepted, hash optional on all of them:

| Written as | Meaning |
|---|---|
| `#AARRGGBB` | Full ARGB — alpha included. |
| `AARRGGBB` | Same, just without the `#`. |
| `#RRGGBB` | RGB only — treated as fully opaque. |

An unparseable color logs a warning naming the offending key and falls back
to that field's default, leaving the rest of your theme intact.

## Recipes

**A light theme, flat style:**

```json
{
  "style": "FLAT",
  "window": {
    "borderColor": "#FFB0B0B8",
    "backgroundColor": "#F0FAFAFC"
  },
  "colors": {
    "separator": "#FFD8D8DE",
    "text": "#FF303038",
    "focusedText": "#FF000000",
    "underline": "#FF404048",
    "caret": "#FF505058",
    "selectedBackground": "#EB3A6EA5",
    "selectedText": "#FFFFFFFF"
  }
}
```

**Your own textured window, split into two boxes, preedit dressed differently:**

```json
{
  "style": "TEXTURE",
  "mergeSections": false,
  "window": {
    "texture": "mypack:textures/gui/ime_frame.png",
    "textureSize": 48,
    "cornerSize": 6
  },
  "preeditWindow": {
    "style": "FLAT",
    "borderColor": "#00000000",
    "backgroundColor": "#A0101014"
  }
}
```

Here the candidate box gets the fancy 48px nine-slice frame while the preedit
strip above it goes minimal — a borderless translucent bar. Colors are
omitted, so text rendering keeps the default dark-theme palette — mix and
match however you like.

## Troubleshooting

- **Nothing changed after editing?** Hit `F3 + T` to force a resource reload,
  and check the log for a `[IME] Theme loaded: ...` line — it reports the
  style, `mergeSections` value and whether a custom `preeditWindow` block was
  picked up (`custom` vs `inherit`).
- **Everything reverted to the dark default?** Your JSON probably failed to
  parse; look for `[IME] Theme descriptor unavailable` in the log. A stray
  trailing comma is the usual suspect.
- **Candidates still in a separate OS-styled window?** That's the native
  candidate bar (`candidateNativeBar=true` in `config/imeinput.cfg`). The
  theme can't reach into the input method's own window — flip that config
  off if you want the mod-drawn, theme-styled candidate box.
