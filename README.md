# FSM Editor — UML State Machines & Code Generation for JetBrains IDEs

[![JetBrains Marketplace version](https://img.shields.io/jetbrains/plugin/v/34709)](https://plugins.jetbrains.com/plugin/34709-fsm-editor--uml-state-machines)

A visual editor and code generator for UML 2.5.1 state machines in IntelliJ IDEA and the other JetBrains IDEs (2024.3 and later). Draw a machine in any `*.fsm` file, with a toolbox, a properties panel, live validation and SVG and PNG export, then generate its source code from templates, in the IDE or with the bundled `fsm` command-line generator. A machine is two JSON files: `Name.fsm`, the model, readable and editable by hand (elements are identified by their names), and `Name.fsm.layout` beside it, the drawing. Changes to a machine can be reviewed and merged as diagrams, side by side.

This is the JetBrains edition of [FSM Editor for VS Code](https://github.com/vincedupuis/fsm-editor-vscode) and [FSM Editor for Visual Studio](https://github.com/vincedupuis/fsm-editor-vs). The three editors use the same file format, rules and code generator, so a team can edit the same `.fsm` files in any of them, and copy and paste diagram elements between them.

## Code generation

*Generate Code...* turns a machine into source code with a [Jinja](https://jinja.palletsprojects.com/) template, running `fsm`, the command-line generator of FSM Editor ([fsm-cli](https://github.com/vincedupuis/fsm-cli)), which the plugin bundles. One template can write several files per machine. Two templates are bundled, TypeScript (`ts`) and C++17 for [Boost.SML](https://github.com/boost-ext/sml) (`sml`), and any `*.jinja` template of your project can be used. The same generator runs from a terminal or a build:

```sh
fsm write "models/**/*.fsm" --template ts --out src/generated
```

See **[docs/CODEGEN.md](docs/CODEGEN.md)** for the dialog, builds, and where templates and the code model are documented.

## Trace Player

**Tools › FSM Editor › Open Trace...** (or the **Trace** button of the diagram's toolbar, which shows the **Trace Player** tool window) replays logs of your running state machines on their diagrams: the active states, the transitions taken by the current step, and the branches chosen at choices and junctions. Open several logs at once for concurrent machines: the Trace Player merges them on one timeline by their timestamps. Step through the records, drag the timeline, play them back at a chosen speed, or turn on **Live** to follow the files as your application writes them (playing to the end turns it on).

A submachine state shows as active in its parent's diagram, and the states inside it light up in the submachine's own diagram when that file is open. When a machine runs several times at once (several logs, or a submachine used by several states), each instance has its own color in the diagram, shown in the status bar and in the tool window's records.

The generated code reports what it does through an `onTrace` hook (see [Tracing](https://github.com/vincedupuis/fsm-cli/blob/main/docs/CODEGEN.md#tracing)); have it write lines in the built-in format, after any prefix your logger adds:

```
2026-11-11T10:02:03.120Z INFO fsm Order event checkout checkout
2026-11-11T10:02:03.121Z INFO fsm Order transition Cart#1 Cart -> Payment (checkout)
2026-11-11T10:02:03.121Z INFO fsm Order.Payment enter Authorizing Authorizing
```

That is `fsm <machine> <kind> <id> <name>`, with `kind` one of `event`, `enter`, `exit`, `transition`, and an optional ISO 8601 time (or time of day) earlier on the line. `examples/demo.log` replays the three examples. Other logs are read with the formats of **Settings › Tools › FSM Editor › Trace formats**, a JSON array of formats whose rules are regular expressions with named groups giving the record, for example:

```json
[
  {
    "name": "My service",
    "time": "^(?<time>\\S+)",
    "rules": [
      { "kind": "event", "match": "received (?<name>\\w+)" },
      { "kind": "enter", "match": "entering (?<name>[\\w:]+)" },
      { "kind": "transition", "match": "(?<source>\\w+) -> (?<target>\\w+)" }
    ]
  }
]
```

Groups: `time`, `machine` (the machine's name, then the submachine states leading to a submachine: `Order.Payment`), `id` (the element's id, derived from the names: `Off`, `Paused#2`, `On.fault`; see the file format), `name` (a name or a path of names such as `On::Playing`), `source` and `target` (a transition by its ends, through any pseudostates between them), and `kind` when the rule doesn't give one. When the lines of a log name no machine (and its format gives none), the Trace Player asks which machine they are about; click the machine next to the log in the tool window to change it. A log with transitions but no `enter` lines still shows the active states, from the transitions' targets.

## UML support

The editor covers UML 2.5.1 state machines: composite, orthogonal and submachine states, all pseudostates, connection point references, entry/exit/do behaviors, deferrable events, the external, local and internal transition kinds, time triggers, and protocol state machines. A validator checks the well-formedness rules as you edit.

Behaviors and conditions are argument-less function calls (`rewind(); showTime()`, `hasDisc() && !isJammed()`), and triggers are event names or `after(2s)`. Text that breaks the rules is refused as you type, and the validator flags problems on the diagram, in its status bar, and in the Text tab and the Problems tool window.

Submachine states reuse another state machine file. They're entered and left through connection point references bound to that machine's entry and exit points.

See **[docs/UML-CONFORMANCE.md](docs/UML-CONFORMANCE.md)** for the supported features, the text syntax, submachines, the file format, the deviations from UML 2.5.1 and every validation rule.

## Using the editor

A `.fsm` file opens with two tabs at the bottom of the editor: **Diagram** and **Text** (the model's JSON). They edit the same document, and the diagram also edits the layout file beside it, so saving, undo and redo are the IDE's own, over both files, and work from either tab.

- **Add elements**: click a toolbox item and then the canvas, or drag it onto the canvas. Drop an element entirely inside a region to nest it. A composite state moves by its title bar: dragging inside its regions draws a selection band. Double-click empty canvas to add a state.
- **Nesting**: an element inside a composite state stays in its region. Dragging or resizing it past the region's border grows the region, its state and the states around it, and moving back during the same drag shrinks them again. Right-click it and choose *Leave Parent* to move it up one level, to the region holding its state. Overlapping elements get a warning on the diagram.
- **Transitions**: select an element and drag its ⊕ handle to the target, or use the Transition tool (T). Drawing from a comment attaches the comment instead. Drag an end of a selected transition onto another element to move its source or target there.
- **Labels**: double-click a name or transition label to edit it in place. The transition syntax is `event, after(2s) [isReady() && !isBusy()] / doIt(); log()`.
- **Routing**: double-click a transition to add a bend point, double-click a bend point to remove it, and drag labels to move them. Alt while dragging turns off the grid.
- **Regions**: use the Add Region tool (R) or the properties panel. Each region has its own size: drag the line between two regions to resize the one before it. Resizing the state on the side of its first or last region resizes that region.
- **Groups**: select elements in the same region, right-click one of them and choose *Group*, or press G. Selecting a member of a group shows a dotted rectangle around the group, and dragging it moves the whole group, into a composite state's region as well. *Ungroup*, or G again, dissolves the groups of the selected elements. A click selects a single member, for its properties, Delete or Copy. Groups are flat: grouping elements of different groups merges them into one.
- **Extract to Submachine**: select elements of one region, or one composite state, right-click one of them and choose *Extract to Submachine…*. They move to a new state machine file next to this one, and a submachine state referencing it takes their place, with connection points for the transitions that crossed the boundary. The menu item is greyed out, with the reason in its tooltip, when the selection can't be extracted. See the [conformance doc](docs/UML-CONFORMANCE.md#submachines-and-connection-point-references).
- **Align**: select elements, right-click one of them and choose *Align Horizontally* (on its horizontal center line) or *Align Vertically* (on its vertical one). Points on a state's border don't align. The elements keep their regions: a composite state grows when its region no longer holds them. An element that is the only selected member of its group brings the whole group along; select several members of a group, or the anchor's group, to align them within it.
- **Colors**: in the properties panel, *Color* colors the selected element, and the swatch beside an entry, exit, do, deferrable events, invariant, trigger, guard or action field colors just that text. A composite state has a *Title color* (its outline, text and header) and a *Background color* (its regions) instead. *Default* removes the color.
- **View**: right-drag, middle-drag or Space+drag pans, the mouse wheel zooms (Shift+wheel pans sideways), two fingers on a trackpad pan and pinching zooms, F fits the diagram.
- **Properties window**: right-click an element, or empty canvas for the state machine, and choose *Properties…* to edit its properties in a window next to the pointer, without the properties panel. It closes with Esc or a click outside it. *Delete* in the same menu deletes the selection.
- **Panels**: the toolbox and the properties panel work like tool windows. The ⋮ Options menu sets the View Mode (Dock Pinned, Dock Unpinned, Undock, Float), Hide closes a panel, and the Toolbox and Properties buttons at the right of the toolbar show and hide them. Unpinned and undocked panels hide when you click the diagram. The layout applies to every diagram.
- **Editing**: Delete removes the selection. Copy, Cut and Paste (⌘C/⌘X/⌘V, Ctrl on Windows and Linux) work between diagrams and with the other editors. ⌘D / Ctrl+D duplicates. *Cut*, *Copy* and *Paste* are also in the context menu, which pastes where you right-clicked. Undo and redo are the IDE's.
- **Shortcuts**: S state, X final, I initial, H history, C choice, J junction, N comment, T transition, R region, G group/ungroup, V/Esc select. Shift+click a tool to keep it active.
- **Submachines**: Alt+double-click a submachine state, or use the button in its properties, to open the referenced machine.

Actions (**Tools › FSM Editor**, and the context menus of `.fsm` files in the Project view and of the editor tab): *New State Machine...*, *Generate Code...*, *Export as SVG...*, *Export as PNG...* (once the diagram is open), *Open as Text*, *Open in FSM Editor*, *Compare as Diagrams*. *File › New › State Machine* creates a machine (both files) in the selected folder.

## Files

A machine `Order` is `Order.fsm`, the model, and `Order.fsm.layout` beside it, the drawing. Both are JSON, and the diagram edits them together: moving an element changes only the layout file, and undo and saving cover both. Renaming or moving `Order.fsm` in the IDE takes `Order.fsm.layout` with it. The model file is meant to be read, reviewed and edited by hand, in the Text tab, which checks it against the format's JSON Schema (the layout file's text editor uses the layout file's schema):

```json
{
  "format": 1,
  "name": "Order",
  "type": "behavioral",
  "initial": { "target": "Cart" },
  "elements": [
    {
      "name": "Cart",
      "type": "state",
      "transitions": [
        { "type": "external", "trigger": "checkout", "target": "Payment" }
      ]
    },
    …
```

Elements are identified by their names, which are unique in the machine and have no spaces. Elements you don't name (choices, final states, comments…) get a generated name ending with `*`, which the diagram doesn't show. A file that isn't valid JSON, or has the wrong shape, shows its problems with their lines instead of the diagram, and in the Text tab. A model and a layout file that disagree (a hand edit, a merge, a lost layout file) are reconciled: elements without a place are placed automatically, and nothing in the model is ever dropped. The format is described in [fsm-cli's README](https://github.com/vincedupuis/fsm-cli#the-fsm-file-format) and in [docs/UML-CONFORMANCE.md](docs/UML-CONFORMANCE.md#file-format).

Files of earlier versions (XMI 2.5.1 with UML DI) still open, unchanged, their Text tab showing the XML: the first change of the diagram writes the new format, and saving it converts the files linked to it by submachine states at the same time, and says what was renamed or left out.

## Diagram diff and merge

**Diff.** *Compare as Diagrams* (the context menu of a `.fsm` file in the Project view or of its editor tab, or **Tools › FSM Editor**) opens the file's version in Git and the file as diagrams side by side, in the IDE's diff view: the older one on the left, read-only, the file on the right, editable. The older version is the one in the index when the file has changes not staged yet, else the last commit's. Added elements are marked on the right, removed ones on the left, changed ones on both; select a changed element to see what changed in it (guard, action, target…). ◀ ▶ step through the changes, and the two diagrams scroll and zoom together. Changes of the drawing alone (positions, routes, colors) aren't marked unless you tick *Show layout changes*. Elements are matched by name, transitions by source, trigger and target: a renamed state shows as one removed and one added. An older version without its layout file is drawn where the newer one places the same elements. An old-format file compares with its converted version.

**Merge.** A `.fsm` file with merge conflicts (after a merge, rebase or cherry-pick) opens as three diagrams in its Diagram tab: the result (the file, starting as the current side, and changed only once you choose), and the **Incoming** and **Current** sides beside it, read-only. The conflicts, the differences between the two sides, are listed and marked: take each from incoming or keep it current, or all at once, then **Complete Merge**, which saves both files without markers and marks them resolved in Git. When Git gives the common version, the changes each side made, which Git merged by itself, are marked lightly. When a side of the conflicts isn't a valid machine file, the diagram shows why, with a button to the IDE's conflict resolution. A conflict in the layout file alone opens the diagram with the current positions; the first change writes a clean layout file.

## Development

Requirements: JDK 17 or later to run Gradle (a JDK 21 toolchain is downloaded when missing); [Rust](https://rustup.rs/) to build `fsm` from a clone of fsm-cli, or else archives from the [fsm-cli releases](https://github.com/vincedupuis/fsm-cli/releases).

```sh
./gradlew :core:test         # model, file format (against fsm-cli's test cases), validation, editing, diff and merge tests
./gradlew :plugin:test       # the plugin in a headless IDE; paints the editor into plugin/build/screenshots
./gradlew :plugin:runIde     # starts IntelliJ IDEA with the plugin: open examples/MediaPlayer.fsm, or Order.fsm (uses Payment.fsm)
scripts/fetch-cli.sh         # copies the fsm generator of this machine's platform from a clone of fsm-cli (../fsm-cli) into plugin/cli
                             # (--build: builds it there first with cargo; given a folder of unpacked fsm-cli release archives
                             # instead, --all takes every platform found there)
./gradlew :plugin:buildPlugin      # plugin/build/distributions/fsm-editor-<version>.zip, with plugin/cli when present
./gradlew :plugin:verifyPlugin     # JetBrains Plugin Verifier against 2024.3 (-PverifyIde=<path to an IDE> adds another)
```

Install the ZIP with Settings › Plugins › ⚙ › *Install Plugin from Disk...*.

### Layout

- `core/` (Kotlin, no IntelliJ dependency): the model (`Model.kt`), the text grammar (`Expressions.kt`), the file format (`Json.kt`, `Format.kt`, `MachineFiles.kt`), reading and converting files of earlier versions (`Xmi.kt`, `XmlTree.kt`, `Conversion.kt`, `Load.kt`), comparing and merging versions (`Compare.kt`, `Merge.kt`, `Sessions.kt`), the validator (`Validation.kt`), diagram geometry (`Geometry.kt`, `Labels.kt`), every editing operation of the diagram (`DiagramSession.kt`, `Tools.kt`), SVG export, the clipboard format, submachine resolution (`Workspace.kt`) and the Trace Player's logic (`Trace.kt`)
- `plugin/`: the IntelliJ Platform plugin
  - `FsmDocuments.kt`: the file types (`.fsm` as JSON, old files as XML), opening files in the diagram or as text, SVG and PNG saving
  - `editor/FsmEditorProvider.kt`, `editor/FsmFileEditor.kt`: the Diagram tab, and the merge of a file with conflicts
  - `editor/DiagramSource.kt`, `editor/DiagramPane.kt`: a machine's two documents (or a version's texts) and the diagram over them
  - `editor/DiagramDiff.kt`: Compare as Diagrams, a diff tool of the IDE's diff view
  - `editor/EditorPanel.kt`, `editor/DiagramCanvas.kt`, `editor/PropertiesPanel.kt`: the Swing diagram editor
  - `services/`: the project's machines (and layout files renamed with their models), validation in the Text tab, the JSON Schemas, Git, the XMI namespaces
  - `codegen/`: running the `fsm` generator, its dialog and tool window
  - `trace/`: the Trace Player tool window and the service following the logs
  - `Actions.kt`, `settings/`: actions and Settings › Tools › FSM Editor
- `core/src/test/`: tests: fsm-cli's test cases (`plugin/cli/cases`, copied by `scripts/fetch-cli.sh`), the layout file's test files and parity results of the VS Code extension (`resources/fixtures/`)
- `scripts/fetch-cli.sh`: copies the code generator, the format's schema and test cases into `plugin/cli`
- `docs/`: UML conformance and code generation
