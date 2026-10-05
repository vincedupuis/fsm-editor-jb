# FSM Editor — UML State Machines for JetBrains IDEs

[![JetBrains Marketplace version](https://img.shields.io/jetbrains/plugin/v/34709)](https://plugins.jetbrains.com/plugin/34709-fsm-editor--uml-state-machines)

A visual editor for UML 2.5.1 state machines in IntelliJ IDEA and the other JetBrains IDEs (2024.3 and later). Open any `*.fsm` file to get a diagram with a toolbox, a properties panel, live validation, SVG export and code generation from templates. Files are standard XMI 2.5.1: the UML model plus its diagram layout in UML DI, in the same file.

This is the JetBrains edition of [FSM Editor for VS Code](https://github.com/vincedupuis/fsm-editor-vscode) and [FSM Editor for Visual Studio](https://github.com/vincedupuis/fsm-editor-vs). The three editors use the same file format, rules and code generator, so a team can edit the same `.fsm` files in any of them, and copy and paste diagram elements between them.

## UML support

The editor covers UML 2.5.1 state machines: composite, orthogonal and submachine states, all pseudostates, connection point references, entry/exit/do behaviors, deferrable events, the external, local and internal transition kinds, time triggers, and protocol state machines. A validator checks the well-formedness rules as you edit.

Behaviors and conditions are argument-less function calls (`rewind(); showTime()`, `hasDisc() && !isJammed()`), and triggers are event names or `after(2s)`. Text that breaks the rules is refused as you type, and the validator flags problems on the diagram, in its status bar, and in the Text tab and the Problems tool window.

Submachine states reuse another state machine file. They're entered and left through connection point references bound to that machine's entry and exit points.

See **[docs/UML-CONFORMANCE.md](docs/UML-CONFORMANCE.md)** for the supported features, the text syntax, submachines, the file format, the deviations from UML 2.5.1 and every validation rule.

## Using the editor

A `.fsm` file opens with two tabs at the bottom of the editor: **Diagram** and **Text** (the XMI). They edit the same document, so saving, undo and redo are the IDE's own and work from either tab.

- **Add elements**: click a toolbox item and then the canvas, or drag it onto the canvas. Drop inside a region to nest it. Double-click empty canvas to add a state.
- **Transitions**: select an element and drag its ⊕ handle to the target, or use the Transition tool (T). Drawing from a comment attaches the comment instead.
- **Labels**: double-click a name or transition label to edit it in place. The transition syntax is `event, after(2s) [isReady() && !isBusy()] / doIt(); log()`.
- **Routing**: double-click a transition to add a bend point, double-click a bend point to remove it, and drag labels to move them. Alt while dragging turns off the grid.
- **Regions**: use the Add Region tool (R) or the properties panel.
- **View**: right-drag, middle-drag or Space+drag pans, the mouse wheel zooms (Shift+wheel pans sideways), F fits the diagram.
- **Editing**: Delete removes the selection. Copy, Cut and Paste (⌘C/⌘X/⌘V, Ctrl on Windows and Linux) work between diagrams and with the other editors. ⌘D / Ctrl+D duplicates. Arrow keys nudge (Shift for 10px). Undo and redo are the IDE's.
- **Shortcuts**: S state, X final, I initial, H history, C choice, J junction, N comment, T transition, R region, V/Esc select. Shift+click a tool to keep it active.
- **Submachines**: Alt+double-click a submachine state, or use the button in its properties, to open the referenced machine.

Actions (**Tools › FSM Editor**, and the context menus of `.fsm` files in the Project view and of the editor tab): *New State Machine...*, *Generate Code...*, *Export as SVG...*, *Open as XMI Text*, *Open in FSM Editor*. *File › New › State Machine* creates a machine in the selected folder.

## Code generation

*Generate Code...* turns a machine into source code with a [Handlebars](https://handlebarsjs.com/) template, running the `fsm` command-line generator of FSM Editor, which the plugin bundles. One template can write several files per machine. A TypeScript template is bundled, and any `*.hbs` template of your project can be used. The same generator runs from a terminal or a build:

```sh
fsm "models/**/*.fsm" --template ts --out src/generated
```

See **[docs/CODEGEN.md](docs/CODEGEN.md)** for the dialog, builds, and where templates and the code model are documented.

## Development

Requirements: JDK 17 or later to run Gradle (a JDK 21 toolchain is downloaded when missing).

```sh
./gradlew :core:test         # model, file format, validation and editing tests (parity with VS Code)
./gradlew :plugin:test       # the plugin in a headless IDE; paints the editor into plugin/build/screenshots
./gradlew :plugin:runIde     # starts IntelliJ IDEA with the plugin: open examples/MediaPlayer.fsm, or Order.fsm (uses Payment.fsm)
scripts/fetch-cli.sh         # copies the fsm generator of this machine's platform from ../fsm-editor-vscode/dist into plugin/cli
                             # (--all: every platform found there; --build: builds it there first with npm run build:bin)
./gradlew :plugin:buildPlugin      # plugin/build/distributions/fsm-editor-<version>.zip, with plugin/cli when present
./gradlew :plugin:verifyPlugin     # JetBrains Plugin Verifier against 2024.3 (-PverifyIde=<path to an IDE> adds another)
```

Install the ZIP with Settings › Plugins › ⚙ › *Install Plugin from Disk...*.

### Layout

- `core/` (Kotlin, no IntelliJ dependency): the model (`Model.kt`), the text grammar (`Expressions.kt`), the XMI/UML DI file format (`Xmi.kt`, `XmlTree.kt`), the validator (`Validation.kt`), diagram geometry (`Geometry.kt`, `Labels.kt`), every editing operation of the diagram (`DiagramSession.kt`, `Tools.kt`), SVG export, the clipboard format and submachine resolution (`Workspace.kt`)
- `plugin/`: the IntelliJ Platform plugin
  - `FsmDocuments.kt`: the file type, opening files in the diagram or as text, SVG saving
  - `editor/FsmEditorProvider.kt`, `editor/FsmFileEditor.kt`: the Diagram tab; syncs the document and the diagram
  - `editor/EditorPanel.kt`, `editor/DiagramCanvas.kt`, `editor/PropertiesPanel.kt`: the Swing diagram editor
  - `services/`: the project's machines, validation in the Text tab, the XMI namespaces
  - `codegen/`: running the `fsm` generator, its dialog and tool window
  - `Actions.kt`, `settings/`: actions and Settings › Tools › FSM Editor
- `core/src/test/`: tests, including parity tests against reference results of the VS Code extension (`resources/fixtures/`)
- `scripts/fetch-cli.sh`: copies the code generator into the plugin
- `docs/`: UML conformance and code generation
