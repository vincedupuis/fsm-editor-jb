package com.vincegosoftware.fsmeditor.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.vincegosoftware.fsmeditor.FsmDocuments

/** Opens `*.fsm` files in the diagram, in front of the XMI text editor (the Text tab). */
class FsmEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = FsmDocuments.isFsm(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = FsmFileEditor(project, file)

    override fun getEditorTypeId() = FsmDocuments.DIAGRAM_EDITOR_ID

    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}
