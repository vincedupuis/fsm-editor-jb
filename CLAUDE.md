# FSM Editor for JetBrains IDEs

IntelliJ Platform plugin (2024.3+, all JetBrains IDEs with XML support): a visual editor for UML 2.5.1 state machines. `*.fsm` files are XMI 2.5.1 documents holding the UML model plus a UML DI diagram with the layout. It is the JetBrains edition of FSM Editor for VS Code (`../fsm-editor-vscode`) and FSM Editor for Visual Studio (`../fsm-editor-vs`): same concepts, file format, rules and messages, reimplemented in Kotlin (a port of the Visual Studio edition's C# Core; no JavaScript is reused). Code generation runs the VS Code project's `fsm` CLI.

## Commands

```sh
./gradlew :core:test          # core tests, including parity with the VS Code reference results
./gradlew :plugin:test        # headless IDE tests; also paints the editor into plugin/build/screenshots/*.png
./gradlew :plugin:runIde      # sandbox IDE with the plugin; try examples/MediaPlayer.fsm and examples/Order.fsm (uses Payment.fsm)
./gradlew :plugin:buildPlugin :plugin:verifyPlugin [-PverifyIde=<local IDE path>]
scripts/fetch-cli.sh [--all]  # copy the fsm CLI + templates into plugin/cli/<platform> (gitignored)
```

## Architecture

- `core` (Kotlin/JVM, no IntelliJ references; the IDE supplies the Kotlin stdlib at run time) holds all the logic:
  - `Model.kt`: `FsmModel`, a flat vertex list where `parent` is a region id, or a state id for border vertices (entry/exit points of a state, connection point references). Entry/exit points whose parent is a top-level region are the machine's own connection points. `ModelIndex` gives tree navigation. Vertices and transitions are compared by identity.
  - `Xmi.kt` + `XmlTree.kt`: the file format with a lenient XML reader and a writer. `toXmi(fromXmi(x))` must round-trip, and the output must stay byte-identical to the VS Code extension's (`Num.format` prints numbers like JavaScript).
  - `Validation.kt`, `Expressions.kt` (text grammar), `Labels.kt` (transition label syntax), `Geometry.kt` (compartments, regions, routes, labels; also used for the UML DI region shapes), `DiagramSession.kt` (every editing operation, calling the `onCommitted` listeners), `Tools.kt`, `SvgExport.kt`, `ClipboardJson.kt` (same clipboard JSON as the other editors, and a small JSON reader), `Workspace.kt` (hrefs, machine summaries, submachine resolution).
- `plugin` (IntelliJ Platform Gradle Plugin 2.x, compiled against IC 2024.3):
  - `FsmEditorProvider` (`PLACE_BEFORE_DEFAULT_EDITOR`) adds the Diagram tab before the XML Text tab. `FsmFileEditor` works on the IDE's `Document`, so save, dirty state, undo/redo and the Text tab share one document; it implements `DocumentReferenceProvider` so undo works from the diagram. Diagram edits are written by `FsmFileEditor.write` as one minimal `replaceString` in a `WriteCommandAction`; document changes are debounced, parsed and handed to `EditorPanel.update` (`null` model when the text is the editor's own echo). Validation runs in a non-blocking read action against the machines found by `FsmWorkspace` (project content roots, unsaved documents preferred).
  - `editor/`: Swing built in code. `DiagramCanvas` paints with Java2D and hit-tests geometrically; `PropertiesPanel` rebuilds itself per selection; `Theme` maps IDE/editor-scheme colors; `ToolIcons` draws the toolbox icons.
  - Copy/Cut/Paste/Delete come from the IDE actions through `DiagramCanvas.uiDataSnapshot` (only while the canvas has the focus); other keys are handled by the canvas.
  - `services/FsmAnnotator` reports validation on the `xmi:id` of each element in the Text tab and the Problems view; `FsmResources` makes the XML support ignore the XMI/UML namespaces.
  - `codegen/FsmCli.kt` runs `fsm <file> --template <hbs> --out <dir>`, parses `wrote …` lines and `file:line: severity: message` problems. `CodeGeneration.generate` is the whole flow; output goes to the FSM Code Generation tool window.

## Project rules

- Keep parity with the VS Code and Visual Studio editions: text grammar, validation rules and messages, XMI output, clipboard JSON. `core/src/test/resources/fixtures` holds reference results from VS Code; regenerate them (see fixtures/README.md) when the reference changes, and add a parity case for any format or rule change.
- **Text grammar:** behaviors are argument-less calls separated by `;`; conditions combine calls with `!`, `&&`, `||` and parentheses; `else` only on choice/junction branches; triggers are event names or `after(<number><ms|s|m|h>)`. The editor refuses invalid text, and the validator reports it.
- **Out of scope:** state machine extension/inheritance and the transition-oriented notation.
- Don't reimplement code generation: templates and the code model belong to the `fsm` CLI.
- Core must not reference IntelliJ classes. Keep the plugin free of internal, override-only and experimental API: `verifyPlugin` must report "Compatible" with no such usages (Kotlin compiles with `-jvm-default=no-compatibility` so implementations of platform interfaces don't re-declare their default methods). Kotlin API level stays at 2.0 (the stdlib of 2024.3).
- User-facing docs: `README.md`, `docs/UML-CONFORMANCE.md` (update when behavior or rules change), `docs/CODEGEN.md`.
