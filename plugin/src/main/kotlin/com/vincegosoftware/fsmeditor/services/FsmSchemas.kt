package com.vincegosoftware.fsmeditor.services

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.vincegosoftware.fsmeditor.FsmDocuments
import java.io.File

/**
 * The JSON Schemas of the Text tab, bundled in the plugin's `cli/schemas`: fsm-cli's for the model file (`.fsm`), and
 * fsm-editor-common's for the layout file (`.fsm.layout`).
 */
class FsmSchemas : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(ModelSchema, LayoutSchema)

    private object ModelSchema : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile) = FsmDocuments.isFsm(file)
        override fun getName() = "FSM state machine (.fsm)"
        override fun getSchemaType() = SchemaType.embeddedSchema
        override fun getSchemaFile(): VirtualFile? = bundled("fsm.schema.json")
    }


    private object LayoutSchema : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile) = FsmDocuments.isLayout(file)
        override fun getName() = "FSM state machine layout (.fsm.layout)"
        override fun getSchemaType() = SchemaType.embeddedSchema
        override fun getSchemaFile(): VirtualFile? = bundled("fsm-layout.schema.json")
    }
}

/** A schema of the plugin's `cli/schemas` (the plugin's jar is in <plugin>/lib). */
private fun bundled(name: String): VirtualFile? {
    val jar = PathManager.getJarPathForClass(FsmSchemas::class.java) ?: return null
    return LocalFileSystem.getInstance().findFileByIoFile(File(File(jar).parentFile?.parentFile, "cli/schemas/$name"))
}
