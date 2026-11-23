package com.vincegosoftware.fsmeditor.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.vincegosoftware.fsmeditor.core.Common

/**
 * Starts fsm-editor-common's sidecar in the background when a project opens, so that opening a diagram doesn't wait
 * for it (on the EDT): starting a process can take seconds on Windows. A port of the Visual Studio edition's start in
 * `FsmEditorPackage.InitializeAsync`.
 */
class CommonStarter : ProjectActivity {
    override suspend fun execute(project: Project) = Common.ensureStarted()
}
