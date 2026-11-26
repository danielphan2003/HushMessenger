package app.hushmessenger.patches.controls

import app.hushmessenger.patches.CompatMirror
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
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
private const val INSERT_CLASS = "LX/6Iy;"
private const val INSERT_EVENT = "Lcom/facebook/xapp/messaging/composer/attachmentpreview/events/InsertAttachmentPreviewEvent;"
private const val HD_BOX = "LX/5K7;"
private const val INSERT = "$INSERT_CLASS->A0B($INSERT_EVENT$HD_BOX" + "Ljava/util/List;Z)V"
private const val HD_SET = "$HD_BOX->A01($HD_BOX" + "Z)V"
private const val HD_WRITE = "$HOLDER->A00(Z)V"
private const val HD_SEND = "ComposeFragment.onSendClicked"
private const val COMPOSER = "Lcom/facebook/messaging/composer/OneLineComposerView;"
private const val SEND_CLASS = "LX/4Dl;"
private const val SEND_FIELD = "$SEND_CLASS->A06:LX/6f3;"
private const val SEND = "$SEND_CLASS->A0K($SEND_CLASS)V"
private const val TOGGLE_CLASS = "LX/VC9;"
private const val UKL = "LX/UKl;"
private const val PROPS = "LX/4wl;"
private const val TOGGLE = "$TOGGLE_CLASS->AeD($PROPS" + "Ljava/lang/Object;)Ljava/lang/Object;"
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

/** The insert handler as 587 writes it: the items, the default false, and the box stored from whether any is HD. */
private fun insertBody(answer: String = "v3") = """
    instance-of v0, p0, Ljava/util/Collection;
    const/4 v3, 0x0
    if-eqz v0, :cond_1d
    invoke-interface {p0}, Ljava/util/Collection;->isEmpty()Z
    move-result v0
    if-eqz v0, :cond_1d
    :cond_19
    :goto_19
    invoke-static {p1, $answer}, $HD_SET
    return-void
    :cond_1d
    invoke-interface {p0}, Ljava/util/List;->iterator()Ljava/util/Iterator;
    move-result-object v2
    :cond_21
    invoke-interface {v2}, Ljava/util/Iterator;->hasNext()Z
    move-result v0
    if-eqz v0, :cond_19
    invoke-static {v2}, LX/4DG;->A0X(Ljava/util/Iterator;)Lcom/facebook/ui/media/attachments/model/MediaResource;
    move-result-object v0
    iget-object v1, v0, Lcom/facebook/ui/media/attachments/model/MediaResource;->A0Q:LX/A0i;
    sget-object v0, LX/A0i;->A04:LX/A0i;
    if-ne v1, v0, :cond_21
    const/4 v3, 0x1
    goto :goto_19
""".trimIndent()

/** The writer as 587 stores it: the preferences editor takes the remembered key and the answer. */
private val WRITE_BODY = """
    sget-object v0, $HOLDER->A00:LX/2zo;
    invoke-static {v0}, LX/2zo;->A0A(LX/2zo;)LX/5kF;
    move-result-object v1
    sget-object v0, $HOLDER->A03:LX/1BK;
    invoke-static {v1, v0, p0}, LX/18B;->A1K(LX/5kF;LX/1BK;Z)V
    return-void
""".trimIndent()

/** The composer's send click as 587 writes it: the log line, the chat's view, and the HD flag it reads. */
private fun sendBody() = """
    const-string v1, "$HD_SEND"
    const v0, -0xeca455
    invoke-static {v1, v0}, LX/0DQ;->A05(Ljava/lang/String;I)V
    iget-object v0, p0, $SEND_FIELD
    if-eqz v0, :cond_9
    check-cast v0, $COMPOSER
    iget-boolean v4, v0, $COMPOSER->A18:Z
    sget-object v1, LX/A0i;->A04:LX/A0i;
    :cond_9
    return-void
""".trimIndent()

/** The preview's HD toggle as 587 writes it: the stored flag inverted and stored back. */
private fun toggleBody() = """
    iget-object v3, p0, $TOGGLE_CLASS->A00:LX/Txk;
    iget-boolean v0, p0, $TOGGLE_CLASS->A01:Z
    xor-int/lit8 v2, v0, 0x1
    iget-object v0, v3, LX/Txk;->A05:LX/VH2;
    iget-object v1, v0, LX/VH2;->A0M:$UKL
    iget-boolean v0, v1, $UKL->A01:Z
    xor-int/lit8 v0, v0, 0x1
    iput-boolean v0, v1, $UKL->A01:Z
    const/4 v0, 0x0
    return-object v0
""".trimIndent()

private fun field(owner: String, name: String, type: String) =
    ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)

private fun hdFixture(
    gate: String = gateBody(),
    read: String = readBody(),
    keys: String = KEYS_INIT,
    insert: String = insertBody(),
    extra: List<MutableClass> = emptyList(),
): List<MutableClass> = listOf(
    fixtureClass(GATE_CLASS, listOf(fixtureMethod(HD_GATE, gate, registers = 5))),
    fixtureClass(KEYS, listOf(fixtureMethod("$KEYS-><clinit>()V", keys, registers = 4, flags = CLASS_INIT))),
    fixtureClass(HOLDER, listOf(
        fixtureMethod("$HOLDER-><clinit>()V", HOLDER_INIT, registers = 1, flags = CLASS_INIT),
        fixtureMethod(HD_READ, read, registers = 3, flags = STATIC),
        fixtureMethod(HD_WRITE, WRITE_BODY, registers = 3, flags = STATIC),
        // The clicked key is read the same way, and must not be taken for the remembered answer.
        fixtureMethod("$HOLDER->A02()Z", readBody(field = "$HOLDER->A02:LX/1BK;"), registers = 3, flags = STATIC),
    )),
    fixtureClass(INSERT_CLASS, listOf(fixtureMethod(INSERT, insert, registers = 10, flags = STATIC))),
    fixtureClass(SEND_CLASS, listOf(fixtureMethod(SEND, sendBody(), registers = 15, flags = STATIC)),
        extraFields = listOf(field(SEND_CLASS, "A06", "LX/6f3;"))),
    fixtureClass(COMPOSER, extraFields = listOf(field(COMPOSER, "A18", "Z"))),
    fixtureClass(UKL, extraFields = listOf(field(UKL, "A01", "Z"), field(UKL, "A02", "Z"))),
    fixtureClass(TOGGLE_CLASS, listOf(fixtureMethod(TOGGLE, toggleBody(), registers = 7))),
) + extra

private fun List<MutableClass>.method(id: String): MutableMethod =
    single { it.type == id.substringBefore("->") }.methods.single { it.hookId() == id }

private fun MutableMethod.code(): List<Instruction> = implementation!!.instructions.toList()

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

class HdDefaultTest {
    @AfterTest fun reset() {
        hdDefaultSearch = null
    }

    @Test fun findsEveryMethodTheSwitchChanges() {
        val classes = hdFixture()
        val sites = findHdDefault(classes)
        assertEquals(HD_GATE, sites.gate.hookId())
        assertEquals(HD_READ, sites.read.hookId())
        assertEquals(HD_WRITE, sites.write.hookId())
        assertEquals(INSERT, sites.insert.hookId())
        assertEquals(SEND, sites.send.hookId())
        assertEquals(TOGGLE, sites.toggle.hookId())
        // findControls finds the same six from its shared walk, and CompatReport agrees.
        findControls(classes)
        val found = hdDefaultSearch!!.getOrThrow()
        assertEquals(listOf(HD_GATE, HD_READ, HD_WRITE, INSERT, SEND, TOGGLE),
            listOf(found.gate.hookId(), found.read.hookId(), found.write.hookId(), found.insert.hookId(), found.send.hookId(), found.toggle.hookId()))
        assertEquals(emptyList<String>() to listOf("gate $HD_GATE", "read $HD_READ", "write $HD_WRITE", "insert $INSERT", "send $SEND", "toggle $TOGGLE"),
            CompatMirror.hdDefault(classes))
    }

    @Test fun theComposerSendReadsTheRememberedAnswer() {
        val classes = hdFixture()
        val send = classes.method(SEND)
        send.injectHdDefaultSend(send.hdDefaultSend(), classes.method(HD_READ))
        val code = send.code()
        assertEquals("$SEND_CLASS->A06:LX/6f3;", code[0].reference())
        assertEquals(Opcode.IF_EQZ, code[1].opcode)
        assertEquals(Opcode.CHECK_CAST, code[2].opcode)
        assertEquals(HD_DEFAULT, ((code[3] as ReferenceInstruction).reference as StringReference).string)
        assertEquals("$SETTINGS->enabled(Ljava/lang/String;)Z", code[4].reference())
        assertEquals(Opcode.IF_EQZ, code[6].opcode)
        assertEquals("$HOLDER->A01()Z", code[7].reference())
        assertEquals(Opcode.IPUT_BOOLEAN, code[9].opcode)
        assertEquals("$COMPOSER->A18:Z", code[9].reference())
        // The stock send follows untouched, where both off branches land.
        assertEquals(HD_SEND, ((code[10] as ReferenceInstruction).reference as StringReference).string)
    }

    @Test fun thePreviewToggleStoresTheRememberedAnswer() {
        val classes = hdFixture()
        val toggle = classes.method(TOGGLE)
        toggle.injectHdDefaultToggle(toggle.hdDefaultToggle(), classes.method(HD_WRITE))
        val code = toggle.code()
        val store = code.indexOfFirst { it.opcode == Opcode.IPUT_BOOLEAN && it.reference() == "$UKL->A01:Z" }
        assertTrue(store >= 0)
        assertEquals(HD_WRITE, code[store + 1].reference())
        assertEquals(store, 7)
    }

    @Test fun theComposerStartsFromTheRememberedAnswerToo() {
        val classes = hdFixture()
        val insert = classes.method(INSERT)
        val stock = insert.code().indexOfFirst { it.reference() == HD_SET }
        insert.injectHdDefaultInsert(insert.hdDefaultInsert(), classes.method(HD_READ))
        val code = insert.code()
        val stores = code.indices.filter { code[it].reference() == HD_SET }
        assertEquals(2, stores.size)
        val (store, again) = stores
        // The stock store is untouched, and every branch the handler takes still lands on it, so each path then falls
        // through to the hook: the empty tray and the loop's exits alike.
        assertEquals(stock, store)
        val hook = code.subList(store + 1, again + 1)
        assertEquals(listOf(Opcode.IF_NEZ, Opcode.CONST_STRING, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
            Opcode.IF_EQZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC), hook.map { it.opcode })
        // An item already HD keeps the box on; otherwise the switch, then the remembered answer, stored into the same box.
        assertEquals(3, (hook[0] as OneRegisterInstruction).registerA)
        assertEquals(HD_DEFAULT, ((hook[1] as ReferenceInstruction).reference as StringReference).string)
        assertEquals("$SETTINGS->enabled(Ljava/lang/String;)Z", hook[2].reference())
        assertEquals("$HOLDER->A01()Z", hook[5].reference())
        assertEquals(3, (hook[6] as OneRegisterInstruction).registerA)
        val first = code[store] as FiveRegisterInstruction
        val last = code[again] as FiveRegisterInstruction
        assertEquals(listOf(first.registerC, first.registerD), listOf(last.registerC, last.registerD))
        // Both of the hook's skips go to the return right after it.
        assertEquals(Opcode.RETURN_VOID, code[again + 1].opcode)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        for (skip in listOf(store + 1, store + 5)) {
            val target = addresses[skip] + (code[skip] as OffsetInstruction).codeOffset
            assertEquals(addresses[again + 1], target)
        }
    }

    @Test fun anInsertHandlerThatTakesItsAnswerAsAParameterIsRefused() {
        val classes = hdFixture(insert = insertBody(answer = "p3"))
        assertContains(assertFailsWith<PatchException> { findHdDefault(classes) }.message!!, "is a parameter now")
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
