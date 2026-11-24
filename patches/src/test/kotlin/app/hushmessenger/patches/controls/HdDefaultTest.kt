package app.hushmessenger.patches.controls

import app.hushmessenger.patches.CompatMirror
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val GATE_CLASS = "LX/5mO;"
private const val HD_GATE = "$GATE_CLASS->A00($FB_USER_SESSION)Z"
private const val KEYS = "LX/1D1;"
private const val HOLDER = "LX/Af9;"
private const val HD_READ = "$HOLDER->A01()Z"
private const val PREFERENCES = "Lcom/facebook/prefs/shared/FbSharedPreferences;"
private const val CONFIG = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
private val STATIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
private val CLASS_INIT = AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value

/** The gate as 587 writes it: a null check, the config, the flag, and the config's answer returned. */
private fun gateBody(flag: String = "0x10100bd00001191L", answer: String = "move-result v0\nreturn v0") = """
    const/4 v0, 0x0
    invoke-static {p1, v0}, LX/1cLd;->A0i(Ljava/lang/Object;I)V
    invoke-static {}, LX/2qu;->A06()LX/4qO;
    move-result-object v2
    const-wide v0, $flag
    check-cast v2, $CONFIG
    invoke-interface {v2, v0, v1}, $CONFIG->Ah8(J)Z
    $answer
""".trimIndent()

/** The read as 587 writes it: the preferences, the key copied into the holder, a false default and the answer returned. */
private fun readBody(field: String = "$HOLDER->A03:LX/1BK;", default: String = "0x0") = """
    sget-object v0, $HOLDER->A00:LX/2zo;
    iget-object v0, v0, LX/2zo;->A00:LX/0LH;
    invoke-interface {v0}, LX/0LH;->get()Ljava/lang/Object;
    move-result-object v2
    check-cast v2, $PREFERENCES
    sget-object v1, $field
    const/4 v0, $default
    invoke-interface {v2, v1, v0}, $PREFERENCE_GETTER
    move-result v0
    return v0
""".trimIndent()

/** The class initializer that makes the two HD keys, as 587's preference key table does. */
private val KEYS_INIT = """
    sget-object v3, $KEYS->A00:LX/5hG;
    const-string v0, "hd_button_clicked"
    invoke-virtual {v3, v0}, LX/5hG;->A0B(Ljava/lang/String;)LX/5hF;
    move-result-object v0
    check-cast v0, LX/1BK;
    sput-object v0, $KEYS->A1l:LX/1BK;
    const-string v0, "$HD_TOGGLE_KEY"
    invoke-virtual {v3, v0}, LX/5hG;->A0B(Ljava/lang/String;)LX/5hF;
    move-result-object v0
    check-cast v0, LX/1BK;
    sput-object v0, $KEYS->A1m:LX/1BK;
    return-void
""".trimIndent()

private val HOLDER_INIT = """
    sget-object v0, $KEYS->A1l:LX/1BK;
    sput-object v0, $HOLDER->A02:LX/1BK;
    sget-object v0, $KEYS->A1m:LX/1BK;
    sput-object v0, $HOLDER->A03:LX/1BK;
    return-void
""".trimIndent()

private fun hdFixture(
    gate: String = gateBody(),
    read: String = readBody(),
    keys: String = KEYS_INIT,
    extra: List<MutableClass> = emptyList(),
): List<MutableClass> = listOf(
    fixtureClass(GATE_CLASS, listOf(fixtureMethod(HD_GATE, gate, registers = 5))),
    fixtureClass(KEYS, listOf(fixtureMethod("$KEYS-><clinit>()V", keys, registers = 4, flags = CLASS_INIT))),
    fixtureClass(HOLDER, listOf(
        fixtureMethod("$HOLDER-><clinit>()V", HOLDER_INIT, registers = 1, flags = CLASS_INIT),
        fixtureMethod(HD_READ, read, registers = 3, flags = STATIC),
        // The clicked key is read the same way, and must not be taken for the remembered answer.
        fixtureMethod("$HOLDER->A02()Z", readBody(field = "$HOLDER->A02:LX/1BK;"), registers = 3, flags = STATIC),
    )),
) + extra

private fun List<MutableClass>.method(id: String): MutableMethod =
    single { it.type == id.substringBefore("->") }.methods.single { it.hookId() == id }

private fun MutableMethod.code(): List<Instruction> = implementation!!.instructions.toList()

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

class HdDefaultTest {
    @AfterTest fun reset() {
        hdDefaultSearch = null
    }

    @Test fun findsTheGateAndTheReadOfTheRememberedAnswer() {
        val classes = hdFixture()
        val sites = findHdDefault(classes)
        assertEquals(HD_GATE, sites.gate.hookId())
        assertEquals(HD_READ, sites.read.hookId())
        // findControls finds the same pair from its shared walk, and CompatReport agrees.
        findControls(classes)
        val found = hdDefaultSearch!!.getOrThrow()
        assertEquals(HD_GATE to HD_READ, found.gate.hookId() to found.read.hookId())
        assertEquals(emptyList<String>() to listOf("gate $HD_GATE", "read $HD_READ"), CompatMirror.hdDefault(classes))
    }

    @Test fun aKeyReadStraightFromTheKeyTableCountsToo() {
        val classes = hdFixture(read = readBody(field = "$KEYS->A1m:LX/1BK;"))
        assertEquals(HD_READ, findHdDefault(classes).read.hookId())
        assertEquals(emptyList<String>(), CompatMirror.hdDefault(classes).first)
    }

    @Test fun theSwitchAnswersTheGateBeforeTheServerDoes() {
        val classes = hdFixture()
        val gate = classes.method(HD_GATE)
        val stock = gate.code()
        gate.injectHdDefaultGate()
        val code = gate.code()
        assertEquals(HD_DEFAULT, ((code[0] as ReferenceInstruction).reference as StringReference).string)
        assertEquals("$SETTINGS->enabled(Ljava/lang/String;)Z", code[1].reference())
        assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN), code.subList(2, 6).map { it.opcode })
        assertEquals(1, (code[4] as NarrowLiteralInstruction).narrowLiteral)
        // The stock gate follows untouched, where the switch's off branch lands.
        assertEquals(stock.map { it.opcode }, code.drop(6).map { it.opcode })
    }

    @Test fun theSwitchBecomesTheReadsDefaultSoAStoredAnswerStillWins() {
        val classes = hdFixture()
        val read = classes.method(HD_READ)
        read.injectHdDefaultRead()
        val code = read.code()
        val call = code.indexOfFirst { it.reference() == PREFERENCE_GETTER }
        assertEquals(Opcode.MOVE_RESULT, code[call - 1].opcode)
        assertEquals("$SETTINGS->enabled(Ljava/lang/String;)Z", code[call - 2].reference())
        assertEquals(HD_DEFAULT, ((code[call - 3] as ReferenceInstruction).reference as StringReference).string)
        // The switch's answer lands in the register the getter takes as its default, and the key is still the HD key.
        val default = (code[call - 1] as OneRegisterInstruction).registerA
        assertEquals(default, (code[call - 3] as OneRegisterInstruction).registerA)
        assertEquals("$HOLDER->A03:LX/1BK;", code[call - 4].reference())
        assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.RETURN), code.drop(call + 1).map { it.opcode })
        assertTrue(code.none { it.opcode == Opcode.CONST_4 })
    }

    @Test fun aMissingOrDoubledGateIsRefused() {
        val noGate = hdFixture(gate = gateBody(flag = "0x10100bd00001192L"))
        assertContains(assertFailsWith<PatchException> { findHdDefault(noGate) }.message!!, "found 0")
        assertTrue(CompatMirror.hdDefault(noGate).first.single().contains("found 0"))
        val twice = hdFixture(extra = listOf(fixtureClass("LX/5mP;", listOf(fixtureMethod("LX/5mP;->A00($FB_USER_SESSION)Z", gateBody(), registers = 5)))))
        assertContains(assertFailsWith<PatchException> { findHdDefault(twice) }.message!!, "found 2")
        assertTrue(CompatMirror.hdDefault(twice).first.single().contains("found 2"))
    }

    @Test fun aGateThatChangesTheServersAnswerIsRefused() {
        val negated = hdFixture(gate = gateBody(answer = "move-result v0\nxor-int/lit8 v0, v0, 0x1\nreturn v0"))
        assertFailsWith<PatchException> { findHdDefault(negated) }
        assertTrue(CompatMirror.hdDefault(negated).first.isNotEmpty())
        assertFailsWith<PatchException> { negated.method(HD_GATE).validateHdDefaultGate() }
    }

    @Test fun aReadThatDefaultsToTrueOrReadsAnotherKeyIsRefused() {
        for (read in listOf(readBody(default = "0x1"), readBody(field = "$HOLDER->A02:LX/1BK;"), readBody(field = "$KEYS->A1l:LX/1BK;"))) {
            val classes = hdFixture(read = read)
            assertContains(assertFailsWith<PatchException> { findHdDefault(classes) }.message!!, "found 0")
            assertTrue(CompatMirror.hdDefault(classes).first.single().contains("found 0"))
        }
        assertFailsWith<PatchException> { hdFixture(read = readBody(default = "0x1")).method(HD_READ).injectHdDefaultRead() }
    }

    @Test fun aKeyNoLongerMadeFromItsNameIsRefused() {
        val classes = hdFixture(keys = KEYS_INIT.replace("\"$HD_TOGGLE_KEY\"", "\"hd_button_toggle_state_v2\""))
        assertContains(assertFailsWith<PatchException> { findHdDefault(classes) }.message!!, "isn't made where it was")
        assertTrue(CompatMirror.hdDefault(classes).first.any { "isn't made where it was" in it })
    }

    @Test fun aMissStopsOnlyThisPatchAndSaysWhy() {
        val classes = hdFixture(gate = gateBody(flag = "0x10100bd00001192L"))
        findControls(classes)
        val failure = hdDefaultSearch!!.exceptionOrNull()
        assertTrue(failure is PatchException)
        assertContains(failure.message!!, "HD photos by default")
    }
}
