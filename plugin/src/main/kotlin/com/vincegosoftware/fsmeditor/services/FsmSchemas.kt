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
 * The JSON Schemas of the Text tab: fsm-cli's for the model file (`.fsm`, bundled in the plugin's `cli/schemas`), and
 * the layout file's (`.fsm.layout`, a resource of the plugin, from the VS Code edition).
 */
class FsmSchemas : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(ModelSchema, LayoutSchema)

    private object ModelSchema : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile) = FsmDocuments.isFsm(file)
        override fun getName() = "FSM state machine (.fsm)"
        override fun getSchemaType() = SchemaType.embeddedSchema
        override fun getSchemaFile(): VirtualFile? {
            // The plugin's jar is in <plugin>/lib.
            val jar = PathManager.getJarPathForClass(FsmSchemas::class.java) ?: return null
            val schema = File(File(jar).parentFile?.parentFile, "cli/schemas/fsm.schema.json")
            return LocalFileSystem.getInstance().findFileByIoFile(schema)
        }
    }

    private object LayoutSchema : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile) = FsmDocuments.isLayout(file)
        override fun getName() = "FSM state machine layout (.fsm.layout)"
        override fun getSchemaType() = SchemaType.embeddedSchema
        override fun getSchemaFile(): VirtualFile? = JsonSchemaProviderFactory.getResourceFile(FsmSchemas::class.java, "/schemas/fsm-layout.schema.json")
    }
}
