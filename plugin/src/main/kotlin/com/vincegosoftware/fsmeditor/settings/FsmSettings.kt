package com.vincegosoftware.fsmeditor.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/** Settings › Tools › FSM Editor. */
@Service(Service.Level.APP)
@State(name = "FsmEditorSettings", storages = [Storage("fsmEditor.xml")])
class FsmSettings : PersistentStateComponent<FsmSettings.Options> {
    class Options {
        /** Path of the fsm code generator; empty to use the bundled one, or else one on the PATH. */
        var cliPath: String = ""
        /** Path of fsm-generator, which runs Jinja templates; empty to use the bundled one, or else one on the PATH. */
        var generatorPath: String = ""
        /** The generator reads the files on disk: save open state machines first. */
        var saveBeforeGenerating: Boolean = true
        /** Formats of the logs the Trace Player reads, besides its built-in format: a JSON array (see [com.vincegosoftware.fsmeditor.core.TraceFormats]). */
        var traceFormats: String = ""
        /** Last template used to generate code. */
        var lastTemplate: String = ""
        /** View mode ([com.vincegosoftware.fsmeditor.editor.PaneMode] name) and visibility of the diagram editors' toolbox and properties panel. */
        var toolboxMode: String = "DockPinned"
        var toolboxShown: Boolean = true
        var propertiesMode: String = "DockPinned"
        var propertiesShown: Boolean = true
    }

    private var options = Options()

    override fun getState() = options

    override fun loadState(state: Options) {
        options = state
    }

    companion object {
        fun getInstance(): FsmSettings = service()
    }
}

class FsmConfigurable : Configurable {
    private var cliPath: TextFieldWithBrowseButton? = null
    private var generatorPath: TextFieldWithBrowseButton? = null
    private var saveFirst: JBCheckBox? = null
    private var traceFormats: JBTextArea? = null

    override fun getDisplayName() = "FSM Editor"

    override fun createComponent(): JComponent {
        val path = TextFieldWithBrowseButton()
        path.addActionListener {
            FileChooser.chooseFile(FileChooserDescriptor(true, false, false, false, false, false), null, null) { path.text = it.path }
        }
        val generator = TextFieldWithBrowseButton()
        generator.addActionListener {
            FileChooser.chooseFile(FileChooserDescriptor(true, false, false, false, false, false), null, null) { generator.text = it.path }
        }
        val save = JBCheckBox("Save .fsm documents before generating code")
        val formats = JBTextArea(8, 60).apply { font = com.intellij.util.ui.JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)) }
        cliPath = path
        generatorPath = generator
        saveFirst = save
        traceFormats = formats
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("fsm executable:", path)
            .addComponentToRightColumn(
                com.intellij.ui.components.JBLabel(
                    "<html>Path of the fsm code generator. Leave empty to use the one bundled with the plugin, or else an fsm found on the PATH.</html>",
                ).apply { foreground = UIUtil.getContextHelpForeground() },
            )
            .addLabeledComponent("fsm-generator executable:", generator)
            .addComponentToRightColumn(
                com.intellij.ui.components.JBLabel(
                    "<html>Path of fsm-generator, the code generator that runs Jinja templates (.jinja). Leave empty to use the one bundled with the plugin, or else an fsm-generator found on the PATH.</html>",
                ).apply { foreground = UIUtil.getContextHelpForeground() },
            )
            .addComponent(save)
            .addComponentToRightColumn(
                com.intellij.ui.components.JBLabel("<html>The generator reads the files on disk: unsaved state machines are saved first.</html>")
                    .apply { foreground = UIUtil.getContextHelpForeground() },
            )
            .addLabeledComponent("Trace formats:", JBScrollPane(formats), true)
            .addComponentToRightColumn(
                com.intellij.ui.components.JBLabel(
                    "<html>Formats of the logs the Trace Player reads, besides its built-in format (<code>… fsm &lt;machine&gt; &lt;kind&gt; &lt;id&gt; &lt;name&gt;</code>, what an onTrace hook of the generated code can write): " +
                        "a JSON array of <code>{ \"name\", \"rules\": [{ \"kind\", \"match\" }], \"time\", \"machine\" }</code>. Each rule is a regular expression whose named groups give the record " +
                        "(time, machine, id, name, and for transitions source and target) and its kind (event, enter, exit or transition, or a kind group). States and transitions are found by xmi:id, else by name; " +
                        "lines without a machine are about the format's machine, else a machine the Trace Player asks for.</html>",
                ).apply { foreground = UIUtil.getContextHelpForeground() },
            )
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val o = FsmSettings.getInstance().state
        // As stored by apply: trimmed.
        return (cliPath?.text?.trim() ?: "") != o.cliPath || (generatorPath?.text?.trim() ?: "") != o.generatorPath || saveFirst?.isSelected != o.saveBeforeGenerating ||
            traceFormats?.text != o.traceFormats
    }

    override fun apply() {
        val o = FsmSettings.getInstance().state
        o.cliPath = cliPath?.text?.trim() ?: ""
        o.generatorPath = generatorPath?.text?.trim() ?: ""
        o.saveBeforeGenerating = saveFirst?.isSelected ?: true
        val formatsChanged = o.traceFormats != (traceFormats?.text ?: "")
        o.traceFormats = traceFormats?.text ?: ""
        if (formatsChanged) {
            for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
                project.getServiceIfCreated(com.vincegosoftware.fsmeditor.trace.TracePlayerService::class.java)?.formatsChanged()
            }
        }
    }

    override fun reset() {
        val o = FsmSettings.getInstance().state
        cliPath?.text = o.cliPath
        generatorPath?.text = o.generatorPath
        saveFirst?.isSelected = o.saveBeforeGenerating
        traceFormats?.text = o.traceFormats
    }

    override fun disposeUIResources() {
        cliPath = null
        generatorPath = null
        saveFirst = null
        traceFormats = null
    }
}
