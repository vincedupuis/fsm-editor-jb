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
        /** The generator reads the files on disk: save open state machines first. */
        var saveBeforeGenerating: Boolean = true
        /** Last template used to generate code. */
        var lastTemplate: String = ""
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
    private var saveFirst: JBCheckBox? = null

    override fun getDisplayName() = "FSM Editor"

    override fun createComponent(): JComponent {
        val path = TextFieldWithBrowseButton()
        path.addActionListener {
            FileChooser.chooseFile(FileChooserDescriptor(true, false, false, false, false, false), null, null) { path.text = it.path }
        }
        val save = JBCheckBox("Save .fsm documents before generating code")
        cliPath = path
        saveFirst = save
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("fsm executable:", path)
            .addComponentToRightColumn(
                com.intellij.ui.components.JBLabel(
                    "<html>Path of the fsm code generator. Leave empty to use the one bundled with the plugin, or else an fsm found on the PATH.</html>",
                ).apply { foreground = UIUtil.getContextHelpForeground() },
            )
            .addComponent(save)
            .addComponentToRightColumn(
                com.intellij.ui.components.JBLabel("<html>The generator reads the files on disk: unsaved state machines are saved first.</html>")
                    .apply { foreground = UIUtil.getContextHelpForeground() },
            )
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val o = FsmSettings.getInstance().state
        return cliPath?.text != o.cliPath || saveFirst?.isSelected != o.saveBeforeGenerating
    }

    override fun apply() {
        val o = FsmSettings.getInstance().state
        o.cliPath = cliPath?.text?.trim() ?: ""
        o.saveBeforeGenerating = saveFirst?.isSelected ?: true
    }

    override fun reset() {
        val o = FsmSettings.getInstance().state
        cliPath?.text = o.cliPath
        saveFirst?.isSelected = o.saveBeforeGenerating
    }

    override fun disposeUIResources() {
        cliPath = null
        saveFirst = null
    }
}
