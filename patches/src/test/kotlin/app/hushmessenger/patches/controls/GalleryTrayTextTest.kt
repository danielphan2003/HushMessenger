package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val SENDER_CLASS = "LX/TraySender;"
private const val CTX_TYPE = "LX/ChatCtx;"
private val STATIC_FLAGS = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

private fun trailingTarget(owner: String = SENDER_CLASS, name: String = "sendText") = ImmutableMethod(
    owner,
    name,
    listOf(CTX_TYPE, "Ljava/lang/CharSequence;", "Ljava/util/List;", "Ljava/util/Map;").map { ImmutableMethodParameter(it, null, null) },
    "V",
    STATIC_FLAGS,
    null,
    null,
    ImmutableMethodImplementation(4, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
)

private fun targetRef(owner: String = SENDER_CLASS, name: String = "sendText") = ImmutableMethodReference(
    owner,
    name,
    listOf(CTX_TYPE, "Ljava/lang/CharSequence;", "Ljava/util/List;", "Ljava/util/Map;"),
    "V",
)

private fun senderClass(
    callerInstructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>,
    registerCount: Int = 10,
    owner: String = SENDER_CLASS,
    extraTargets: List<ImmutableMethod> = emptyList(),
): ClassDef {
    val target = trailingTarget(owner)
    val caller = ImmutableMethod(
        owner,
        "sendMedia",
        listOf(CTX_TYPE, "I", "Z", "Z", "Z", "Z").map { ImmutableMethodParameter(it, null, null) },
        "V",
        STATIC_FLAGS,
        null,
        null,
        ImmutableMethodImplementation(registerCount, callerInstructions, null, null),
    )
    return ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(target, caller) + extraTargets)
}

class GalleryTrayTextTest {
    @Test fun discoversAndRewritesFiveRegisterNullCallLeavingNonNullUntouched() {
        val ref = targetRef()
        val cls = senderClass(
            listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, 0, 1, 2, 3, 0, ref),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, 0, 4, 2, 3, 0, ref),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val site = findGalleryTrayText(listOf(cls))
        assertEquals("$SENDER_CLASS->sendMedia(${CTX_TYPE}IZZZZ)V", site.caller.hookId())
        assertEquals("$SENDER_CLASS->sendText(${CTX_TYPE}Ljava/lang/CharSequence;Ljava/util/List;Ljava/util/Map;)V", site.target.hookId())

        val mutable = MutableMethod(site.caller)
        mutable.injectGalleryTrayText(site)
        val after = mutable.implementation!!.instructions.toList()
        assertEquals(6, after.size)
        assertEquals(Opcode.CONST_CLASS, after[1].opcode)
        assertEquals(1, (after[1] as OneRegisterInstruction).registerA)
        assertEquals(SENDER_CLASS, (after[1] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.INVOKE_STATIC, after[2].opcode)
        assertEquals(GALLERY_TRAY_TEXT_CALL, (after[2] as ReferenceInstruction).reference.toString())
        assertEquals(1, (after[2] as FiveRegisterInstruction).registerD)
        assertEquals(Opcode.CONST_4, after[3].opcode)
        assertEquals(1, (after[3] as OneRegisterInstruction).registerA)
        assertEquals(0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.INVOKE_STATIC, after[4].opcode)
        assertEquals(ref.toString(), (after[4] as ReferenceInstruction).reference.toString())
    }

    @Test fun rewritesRangeCallWithHighRegisterUsingConst16() {
        val ref = targetRef()
        val cls = senderClass(
            listOf(
                ImmutableInstruction21s(Opcode.CONST_16, 17, 0),
                ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 16, 4, ref),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
            registerCount = 26,
        )
        val site = findGalleryTrayText(listOf(cls))
        val mutable = MutableMethod(site.caller)
        mutable.injectGalleryTrayText(site)
        val after = mutable.implementation!!.instructions.toList()
        assertEquals(Opcode.CONST_CLASS, after[1].opcode)
        assertEquals(17, (after[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[2].opcode)
        assertEquals(16, (after[2] as RegisterRangeInstruction).startRegister)
        assertEquals(4, (after[2] as RegisterRangeInstruction).registerCount)
        assertEquals(GALLERY_TRAY_TEXT_CALL, (after[2] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.CONST_16, after[3].opcode)
        assertEquals(17, (after[3] as OneRegisterInstruction).registerA)
        assertEquals(0, (after[3] as NarrowLiteralInstruction).narrowLiteral)
    }

    @Test fun refusesWhenMissingOrAmbiguousOrNoNullCalls() {
        assertFailsWith<PatchException> { findGalleryTrayText(emptyList()) }

        val ref = targetRef()
        val one = senderClass(listOf(ImmutableInstruction11n(Opcode.CONST_4, 1, 0), ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, 0, 1, 2, 3, 0, ref), ImmutableInstruction10x(Opcode.RETURN_VOID)))
        val two = senderClass(listOf(ImmutableInstruction11n(Opcode.CONST_4, 1, 0), ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, 0, 1, 2, 3, 0, targetRef("LX/Other;")), ImmutableInstruction10x(Opcode.RETURN_VOID)), owner = "LX/Other;")
        assertFailsWith<PatchException> { findGalleryTrayText(listOf(one, two)) }

        val noNull = senderClass(listOf(ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, 0, 4, 2, 3, 0, ref), ImmutableInstruction10x(Opcode.RETURN_VOID)))
        val site = findGalleryTrayText(listOf(noNull))
        assertFailsWith<PatchException> { MutableMethod(site.caller).injectGalleryTrayText(site) }
    }
}
