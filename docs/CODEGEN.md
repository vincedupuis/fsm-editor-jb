# Code Generation

FSM Editor for JetBrains IDEs generates source code with [Jinja](https://jinja.palletsprojects.com/) templates, running **`fsm`**, the same command-line generator as FSM Editor for VS Code and for Visual Studio. `fsm` is [fsm-cli](https://github.com/vincedupuis/fsm-cli), in Rust, with templates rendered by [MiniJinja](https://github.com/mitsuhiko/minijinja). The plugin doesn't reimplement code generation: it runs `fsm write`, which it bundles, and shows the results in the IDE. The same templates therefore produce the same code from IntelliJ IDEA, VS Code, Visual Studio, a terminal or a CI build.

Handlebars templates (`.hbs`), which earlier versions used, are no longer supported: `fsm` refuses them and says so. [Coming from the Handlebars templates](https://github.com/vincedupuis/fsm-cli#coming-from-the-handlebars-templates) shows how to convert one to Jinja.

- [From the IDE](#from-the-ide)
- [From the command line and builds](#from-the-command-line-and-builds)
- [Which fsm is used](#which-fsm-is-used)
- [Templates and the code model](#templates-and-the-code-model)

## From the IDE

Run **Generate Code...** from the diagram toolbar (**Code**), the context menu of a `.fsm` file in the Project view, the context menu of the editor tab, or **Tools › FSM Editor**. A dialog asks for:

1. **The template**: the templates bundled with `fsm` (`ts` and `sml`, as *bundled template*), any `*.jinja` file of the project, or **Browse...**.
2. **The output folder.** Generated files are overwritten every time.

The last template, and the last output folder of each machine, are remembered.

The generator reads the files on disk, so open `.fsm` documents with unsaved changes are saved first (Settings › Tools › FSM Editor › *Save .fsm documents before generating code*). Machines used by submachine states are read and validated too, but only the chosen machine is generated.

Results:

- The **FSM Code Generation** tool window shows the command that ran, every file written and the problems. Click a problem to open the file at its line (a state machine opens in the diagram).
- Generation stops when the machine has validation errors. Nothing is written then, and a dialog says why.
- A notification and the status bar report how many files were written, and the new files show up in the Project view.

To generate on a key, assign one to *Generate Code...* in Settings › Keymap (search for "Generate Code").

## From the command line and builds

The bundled program is a single executable without dependencies, in the `cli/<platform>` folder of the plugin's install directory, with the bundled templates in `cli/<platform>/templates`. It's also published for Windows, macOS and Linux on the [fsm-cli releases](https://github.com/vincedupuis/fsm-cli/releases):

```sh
fsm write "models/**/*.fsm" --template ts --out src/generated
```

Exit codes: `0` success, `1` errors in the machines or the template (with `--check`, also files missing or out of date), `2` bad usage. Problems are printed as `file:line: severity: message`, which build logs and CI understand. With `--check`, `fsm write` writes nothing and reports the generated files that are missing, or out of date because their `.fsm` file's model hash has changed since ([details](https://github.com/vincedupuis/fsm-cli#fsm-write)). To generate code on every Gradle build:

```kotlin
val generateStateMachines by tasks.registering(Exec::class) {
    inputs.files(fileTree("models") { include("*.fsm") })
    outputs.dir(layout.buildDirectory.dir("generated/fsm"))
    commandLine("fsm", "write", "models/*.fsm", "-t", "ts", "-o", layout.buildDirectory.dir("generated/fsm").get().asFile.path)
}
```

## Which fsm is used

In this order:

1. The path set in Settings › Tools › FSM Editor › *fsm executable*, when set.
2. The executable bundled with the plugin (`cli/<platform>/fsm`, or `fsm.exe` on Windows), with its templates in `cli/<platform>/templates`. `<platform>` is `darwin-arm64`, `darwin-x64`, `linux-arm64`, `linux-x64` or `windows-x64`.
3. An `fsm` (`fsm.exe` on Windows) on the `PATH`.

Set the option to use a newer generator than the bundled one, your own build, or when the plugin was built without the generator for your platform.

## Templates and the code model

A template is a Jinja file, with optional front matter. One template writes any number of files per machine through `{% filter file("path") %}` blocks. Templates can't run code: every filter and function is built into the generator.

The template language is documented in [fsm-cli's README](https://github.com/vincedupuis/fsm-cli#writing-a-template). The bundled templates, TypeScript (`ts`) and C++17 for Boost.SML (`sml`), the files they write, the [code model](https://github.com/vincedupuis/fsm-cli/blob/main/docs/CODEGEN.md#code-model-reference) the templates receive, and the [semantics of the generated code](https://github.com/vincedupuis/fsm-cli/blob/main/docs/CODEGEN.md#semantics-of-the-generated-code) are documented in [fsm-cli's docs/CODEGEN.md](https://github.com/vincedupuis/fsm-cli/blob/main/docs/CODEGEN.md). Templates in your project are listed in the Generate Code dialog, so you can keep them next to your models.
