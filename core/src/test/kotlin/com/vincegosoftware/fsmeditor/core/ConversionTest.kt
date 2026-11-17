package com.vincegosoftware.fsmeditor.core

import com.vincegosoftware.fsmeditor.core.TestFiles.example
import com.vincegosoftware.fsmeditor.core.TestFiles.old
import com.vincegosoftware.fsmeditor.core.TestFiles.read
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Old files (XMI): read, and converted (the VS Code edition's test/unit/xmi.test.ts; ConversionTests.cs). */
class ConversionTest {
    /** A small old file: [body] inside the machine's region. */
    private fun xmi(body: String, name: String = "M", id: String = "sm") = """<?xml version="1.0" encoding="UTF-8"?>
<xmi:XMI xmi:version="20131001" xmlns:xmi="http://www.omg.org/spec/XMI/20131001" xmlns:uml="http://www.omg.org/spec/UML/20161101">
  <uml:Model xmi:id="m" name="$name">
    <packagedElement xmi:type="uml:StateMachine" xmi:id="$id" name="$name">
      <region xmi:type="uml:Region" xmi:id="r">
$body
      </region>
    </packagedElement>
  </uml:Model>
</xmi:XMI>
"""

    @Test
    fun areRecognizedAndReadAsTheyAre() {
        assertTrue(Format.isOldFormat(old("MediaPlayer")))
        assertFalse(Format.isOldFormat(example("MediaPlayer.fsm")))
        val m = Xmi.read(old("MediaPlayer"))
        assertEquals("MediaPlayer", m.context)
        assertEquals("updateVolume()", m.model.transitions.first { it.id == "t22" }.action)
    }

    @Test
    fun convertGivesNamesAndReportsEveryRenameAndLoss() {
        val r = Conversion.convertGroup(
            listOf(
                ConvertInput(
                    "/w/M.fsm",
                    xmi(
                        """        <subvertex xmi:type="uml:Pseudostate" xmi:id="i" kind="initial" name="start"/>
        <subvertex xmi:type="uml:State" xmi:id="a" name="Idle"/>
        <subvertex xmi:type="uml:State" xmi:id="b" name="Idle"/>
        <subvertex xmi:type="uml:State" xmi:id="c" name="a.b*"/>
        <subvertex xmi:type="uml:State" xmi:id="w" name=" Waiting  for payment"/>
        <subvertex xmi:type="uml:State" xmi:id="d"/>
        <subvertex xmi:type="uml:Pseudostate" xmi:id="j" kind="junction"/>
        <transition xmi:type="uml:Transition" xmi:id="t0" source="i" target="a"/>
        <transition xmi:type="uml:Transition" xmi:id="t1" source="a" target="i"/>
        <transition xmi:type="uml:Transition" xmi:id="t2" source="a" target="b"><effect xmi:type="uml:OpaqueBehavior" xmi:id="e"><body>go()</body></effect></transition>""",
                    ),
                ),
            ),
        )[0]
        assertEquals(listOf("the state 'a.b*' is now 'a_b_'", "the state ' Waiting  for payment' is now 'Waiting_for_payment'", "the state 'Idle' is now 'Idle2' (its name was used twice)"), r.renames)
        assertEquals(2, r.generated)
        assertEquals(listOf("the name of the initial pseudostate 'start'", "the transition from state 'Idle' to an initial pseudostate"), r.losses)
        assertEquals(listOf("", "Idle", "Idle2", "a_b_", "Waiting_for_payment", "state1*", "junction1*"), read(r.modelText, r.layoutText).model!!.vertices.map { it.name })
        assertContains(r.modelText, "{ \"type\": \"external\", \"action\": \"go()\", \"target\": \"Idle2\" }")
    }

    @Test
    fun convertsLinkedFilesTogether() {
        val results = Conversion.convertGroup(listOf("Order", "Payment").map { ConvertInput("/x/$it.fsm", old(it)) })
        val order = read(results[0].modelText, results[0].layoutText).model!!
        assertEquals(
            listOf("retry" to PointKind.Entry, "failed" to PointKind.Exit, "cancelled" to PointKind.Exit),
            order.vertices.filter { it.type == VertexType.ConnectionPointRef }.map { it.ref to it.pointKind },
        )
        // Alone, Order can't know Payment's points: the ids stay, and the loss is said.
        val alone = Conversion.convertGroup(listOf(ConvertInput("/x/Order.fsm", old("Order"))))[0]
        assertEquals(3, alone.losses.size)
        assertContains(alone.losses[0], "point 'p_retry' of Payment.fsm, which was not found")
    }

    @Test
    fun aFileThatIsNotXmiCanNotBeConverted() {
        assertContains(Conversion.convertGroup(listOf(ConvertInput("/x/A.fsm", "<a/>")))[0].error!!, "Not an XMI document")
    }

    @Test
    fun findsTheOldFilesLinkedBothWays() {
        val files = listOf("Order", "Payment").map { ConvertInput("/x/$it.fsm", old(it)) }
        val others = files + ConvertInput("/x/Other.fsm", xmi("", "Other")) + ConvertInput("/x/New.fsm", example("MediaPlayer.fsm"))
        assertEquals(listOf("/x/Payment.fsm", "/x/Order.fsm"), MachineFiles.linkedOldFiles("/x/Payment.fsm", old("Payment"), others) { null }.map { it.path })
        assertEquals(
            listOf("/x/Order.fsm", "/x/Payment.fsm"),
            MachineFiles.linkedOldFiles("/x/Order.fsm", old("Order"), emptyList()) { if (it == "/x/Payment.fsm") old("Payment") else null }.map { it.path },
        )
    }

    @Test
    fun hrefsOfOldFilesDecodeAsDecodeUriDoes() {
        assertEquals("My Machine.fsm", Hrefs.decodeUri("My%20Machine.fsm"))
        assertEquals("café.fsm", Hrefs.decodeUri("caf%C3%A9.fsm"))
        assertEquals("a#b%2Fc?", Hrefs.decodeUri("a%23b%2Fc%3F"))
        assertEquals("café%zz", Hrefs.decodeUri("caf%C3%A9%zz"))
        assertEquals("a%E0.fsm", Hrefs.decodeUri("a%E0.fsm"))
        assertEquals("a%E0A.fsm", Hrefs.decodeUri("a%E0%41.fsm"))
        assertEquals("a%C0%80%", Hrefs.decodeUri("a%C0%80%"))
    }
}
