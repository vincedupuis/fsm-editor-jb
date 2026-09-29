package com.vincegosoftware.fsmeditor.services

import com.intellij.javaee.ResourceRegistrar
import com.intellij.javaee.StandardResourceProvider
import com.vincegosoftware.fsmeditor.core.Xmi

/** The XMI, UML and diagram namespaces of .fsm files have no schema to fetch: the XML support ignores them. */
class FsmResources : StandardResourceProvider {
    override fun registerResources(registrar: ResourceRegistrar) {
        for (uri in listOf(Xmi.NS_XMI, Xmi.NS_UML, Xmi.NS_UMLDI, Xmi.NS_DC, Xmi.NS_DI, Xmi.PROFILE_NS)) registrar.addIgnoredResource(uri)
    }
}
