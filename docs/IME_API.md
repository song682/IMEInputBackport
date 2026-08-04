# IME Input Backport — Mod Integration API

Got a text control that vanilla's IME pipeline can't reach? This is the door.

The mod ships a small, dependency-free integration surface — two classes in
`decok.dfcdvadstf.imeinput.api` — that lets **any** mod's search box, quantity
field, EditBox, or whatever-you-call-it plug straight into the IME input
pipeline. If your control is a plain `GuiTextField`, you don't need any of
this: the mod's Mixin already tracks its focus, routes committed text into it,
and renders the composition preview. This API exists for everything *else* —
controls that draw themselves, refresh through their own key pipeline, or
simply live outside the `GuiTextField` family.

## The two pieces

### `IMECommitTarget` — your control, made IME-aware

An interface with one mandatory method and a couple of optional ones:

| Method | Kind | What it's for |
|---|---|---|
| `boolean isActive()` | required | Drives the IME switch and commit routing. Return `true` when your control should receive IME input right now — normally that's just "my control has focus". Polled once per client tick. |
| `void commitText(String text)` | required | Receives the committed text **as a whole**. You decide how to get it into your control — char-by-char key injection or a bulk insert — as long as your own refresh logic fires, same as manual input. |
| `int getCaretX()` | optional (default `-1`) | Candidate-window anchor X in GUI coordinates. `-1` means "no opinion", let the mod fall back. |
| `int getCaretY()` | optional (default `-1`) | Candidate-window anchor Y, GUI coordinates, **below** the caret. Same fallback rule. |

That's the whole contract. No base class to extend, no events to wire, no
state to keep in sync — the mod does all the plumbing.

### `IMEInputAPI` — the registry

A static registry, nothing more:

- `IMEInputAPI.register(target)` — add a target. Re-registering the same
  instance is a no-op.
- `IMEInputAPI.unregister(target)` — remove one. You'll almost never need it
  during a client session.
- `IMEInputAPI.getActiveTarget()` — the mod polls this internally; you
  generally don't call it yourself.

## How the pipeline uses your target

Every client tick the mod asks the registry "who's active?" and then:

1. **IME switch** — if any target says `isActive()`, the IME is enabled even
   when no vanilla `GuiTextField` is focused (your control doesn't need the
   Mixin at all).
2. **Commit routing** — committed text goes to the active target's
   `commitText()` instead of the default `GuiTextField.writeText()` path.
3. **Candidate anchor** — the candidate window follows this chain: the
   Mixin-reported caret (vanilla fields) → the active target's `getCaretX/Y()`
   → the focused field's corner → no anchor at all (positioning skipped).

If several targets are active at once, the **first registered** one wins.

## Integration guide

Three steps, no ceremony:

**1. Implement the interface on your control.**

**2. Register it once**, at client init — your `FMLPreInitializationEvent`,
client side only:

```java
IMEInputAPI.register(new MySearchBox());
```

**3. Make `isActive()` honest and `commitText()` reuse your own paste path.**

```java
public class MySearchBox implements IMECommitTarget {

    private final GuiTextField inner = new GuiTextField(/* ... */);
    private boolean focused; // flipped by your mouse/key handlers

    @Override
    public boolean isActive() {
        return focused;
    }

    @Override
    public void commitText(String text) {
        // Same route as Ctrl+V: validation, max length and the search
        // refresh all keep working exactly as if the user typed it.
        inner.writeText(text);
        refreshResults();
    }

    @Override
    public int getCaretX() {
        return inner.xPosition + inner.getCursorPosition() * 6;
    }

    @Override
    public int getCaretY() {
        return inner.yPosition + inner.height;
    }
}
```

A handy model to copy: CatFrame's `AbstractEditBox` is a perfect candidate —
it has its own `focused` flag and a vanilla-style `keyTyped(char, int)`, so
`isActive()` is one field read and `commitText()` can feed characters through
`keyTyped` (or the paste-style insert path) to keep `onTextChanged()` firing.

## Built-in targets

The mod registers one target itself: **`NEICompat`**, for the NEI search and
quantity boxes (official or GTNH fork). It registers at preInit when NEI is
loaded, and its `isActive()` is deliberately strict — it only claims commits
when you're on a container screen, NEI's focused widget is a `TextField`, and
that field's inner `GuiTextField` matches the one the Mixin is tracking. That
last check is what keeps a mod's own text field inside a container (say, an
AE terminal search) from being hijacked by NEI's pipeline. Commits are fed
char-by-char through `GuiContainerManager.keyTyped` so NEI's `onTextChange`
refreshes the search — a plain `writeText` would silently skip that.

## Caveats

- **Main thread only.** Register at init, and keep `isActive()` cheap — it's
  called once per tick.
- **No Mixin, no problem.** For non-`GuiTextField` controls the mod has no
  focus tracking of its own; your `isActive()` is the *only* signal, so make
  sure it's accurate.
- **Leaving targets registered is fine.** The mod is client-lifetime; there's
  no shutdown hook to speak of.
- **Reobf-safe.** The API is part of this mod's own jar and stays untouched
  by name mapping.
