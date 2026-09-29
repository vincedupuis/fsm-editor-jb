package com.vincegosoftware.fsmeditor.codegen

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** A template the user can pick. */
class TemplateChoice(val path: String, val name: String, val description: String)

/** Asks for the template and output folder of a code generation. */
class GenerateCodeDialog(
    private val project: Project,
    machineName: String,
    templates: List<TemplateChoice>,
    lastTemplate: String,
    defaultOut: String,
) : DialogWrapper(project) {
    private val model = CollectionListModel(templates)
    private val list = JBList(model)
    private val output = TextFieldWithBrowseButton()

    init {
        title = "Generate Code: $machineName"
        setOKButtonText("Generate")
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<TemplateChoice>() {
            override fun customizeCellRenderer(list: JList<out TemplateChoice>, value: TemplateChoice, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.name)
                append("    ${value.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        list.emptyText.text = "No template found: use Browse..."
        val last = templates.indexOfFirst { File(it.path) == File(lastTemplate) }
        list.selectedIndex = if (last >= 0) last else 0
        output.text = defaultOut
        output.addActionListener {
            val start = LocalFileSystem.getInstance().findFileByPath(output.text.trim().replace('\\', '/'))
            FileChooser.chooseFile(FileChooserDescriptor(false, true, false, false, false, false), project, start) { output.text = it.path }
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val browse = JButton("Browse...")
        browse.addActionListener {
            FileChooser.chooseFile(
                FileChooserDescriptor(true, false, false, false, false, false).withFileFilter { it.extension == "hbs" },
                project, null,
            ) { file ->
                val choice = TemplateChoice(file.path, file.nameWithoutExtension, file.path)
                model.add(choice)
                list.setSelectedValue(choice, true)
            }
        }
        val templates = JPanel(BorderLayout(0, JBUI.scale(6)))
        templates.add(JBScrollPane(list).apply { preferredSize = Dimension(JBUI.scale(480), JBUI.scale(150)) }, BorderLayout.CENTER)
        templates.add(JPanel(BorderLayout()).apply { add(browse, BorderLayout.WEST) }, BorderLayout.SOUTH)
        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Template (bundled templates, *.hbs files of the project, or Browse):"))
            .addComponent(templates)
            .addVerticalGap(JBUI.scale(8))
            .addComponent(JBLabel("Output folder (generated files are overwritten):"))
            .addComponent(output)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    override fun doValidate(): ValidationInfo? {
        if (list.selectedValue == null) return ValidationInfo("Pick a template.", list)
        if (output.text.isBlank()) return ValidationInfo("Pick an output folder.", output.textField)
        return null
    }

    /** The template file and output folder chosen. */
    val result: Pair<String, String> get() = list.selectedValue.path to output.text.trim()
}
