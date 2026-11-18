package com.vincegosoftware.fsmeditor.services

import com.intellij.javaee.ResourceRegistrar
import com.intellij.javaee.StandardResourceProvider

/** The XMI, UML and diagram namespaces of .fsm files have no schema to fetch: the XML support ignores them. */
class FsmResources : StandardResourceProvider {
    override fun registerResources(registrar: ResourceRegistrar) {
        for (uri in NAMESPACES) registrar.addIgnoredResource(uri)
    }

    private companion object {
        val NAMESPACES = listOf(
            "http://www.omg.org/spec/XMI/20131001",
            "http://www.omg.org/spec/UML/20161101",
            "http://www.omg.org/spec/UML/20161101/UMLDI",
            "http://www.omg.org/spec/DD/20100524/DC",
            "http://www.omg.org/spec/DD/20100524/DI",
            "https://github.com/vincedupuis/fsm-editor/profiles/FsmStereotypes",
        )
    }
}
