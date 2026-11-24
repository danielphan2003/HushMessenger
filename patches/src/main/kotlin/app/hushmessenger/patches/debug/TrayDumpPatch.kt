package app.hushmessenger.patches.debug

import app.hushmessenger.patches.MessengerTarget
import app.hushmessenger.patches.controls.AUTO_REPLY_MEDIA
import app.hushmessenger.patches.controls.INSERT_PREVIEW
import app.hushmessenger.patches.controls.settingsExtension
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val TRAY_DUMP = "Lapp/hushmessenger/extension/TrayDump;"

/**
 * Diagnostics only, never in a release: logs every attachment that enters a chat's tray, with each field of its
 * MediaResource, under the log tag HushTrayDump. Used to compare a gallery pick with Messenger's own attachments.
 */
@Suppress("unused")
val trayDumpPatch = bytecodePatch(
    name = "Debug: log chat tray attachments",
    description = "Diagnostics for test builds: logs the fields of every attachment that enters a chat's tray.",
    default = true,   // only on the debug branch, which never ships a release
) {
    compatibleWith(MessengerTarget.COMPATIBILITY)
    dependsOn(settingsExtension)
    execute {
        val event = mutableClassDefBy(INSERT_PREVIEW)
        val init = event.methods.singleOrNull { method ->
            method.name == "<init>" && method.parameterTypes.map { it.toString() } == listOf(AUTO_REPLY_MEDIA, "Ljava/util/List;")
        } ?: throw PatchException("Debug tray dump: the tray insert constructor moved")
        // p0 is the event, p1 a single attachment or null, p2 the list.
        init.addInstructions(0, "invoke-static {p1, p2}, $TRAY_DUMP->dump(Ljava/lang/Object;Ljava/util/List;)V")
        // Debug experiment: remember the chat's session and key where a pick comes back (gallery_to_chat's handler).
        val handler = mutableClassDefBy("LX/6C7;").methods.singleOrNull { m ->
            m.name == "A00" && m.parameterTypes.map { it.toString() } == listOf(
                "Landroidx/fragment/app/Fragment;", "Lcom/facebook/auth/usersession/FbUserSession;",
                "Lcom/facebook/messaging/model/threadkey/ThreadKey;",
                "Lcom/facebook/xapp/messaging/composer/activity/event/ActivityResultEvent;", "LX/5Re;")
        } ?: throw PatchException("Debug tray dump: the pick handler moved")
        val static = AccessFlags.STATIC.isSet(handler.accessFlags)
        val session = if (static) "p1" else "p2"
        val key = if (static) "p2" else "p3"
        handler.addInstructions(0, "invoke-static {$session, $key}, $TRAY_DUMP->remember(Ljava/lang/Object;Ljava/lang/Object;)V")
        // Fix experiment: where the tray is forced on, no sequenced sends (LX/Aeo;->A0N:Z is enableSequencedSends).
        val aeo = mutableClassDefBy("LX/Aeo;")
        val ctor = aeo.methods.singleOrNull { m ->
            m.name == "<init>" && m.implementation?.instructions?.any { i ->
                i.opcode == Opcode.IPUT_BOOLEAN && (i as ReferenceInstruction).reference.toString() == "LX/Aeo;->A0N:Z"
            } == true
        } ?: throw PatchException("Debug tray dump: Aeo's sequenced sends field moved")
        val code = ctor.implementation!!.instructions.toList()
        val at = code.indexOfFirst { i -> i.opcode == Opcode.IPUT_BOOLEAN && (i as ReferenceInstruction).reference.toString() == "LX/Aeo;->A0N:Z" }
        val value = (code[at] as TwoRegisterInstruction).registerA
        ctor.addInstructions(at, """
            invoke-static/range {v$value .. v$value}, Lapp/hushmessenger/extension/Settings;->traySequencedSends(Z)Z
            move-result v$value
        """)
        // Fix experiment 2: the composer configuration (new LX/584;) gets Messenger's own tray answer, not the forced one.
        var wrapped = 0
        classDefForEach { cls ->
            cls.methods.filter { m ->
                m.implementation?.instructions?.any { i -> i.opcode == Opcode.NEW_INSTANCE && ((i as ReferenceInstruction).reference as TypeReference).type == "LX/584;" } == true
            }.forEach { m ->
                val mutable = mutableClassDefBy(cls.type).methods.first { it.name == m.name && it.parameterTypes == m.parameterTypes && it.returnType == m.returnType }
                val insns = mutable.implementation!!.instructions.toList()
                // After each A1O()Z call's move-result, in reverse so earlier indexes stay valid.
                insns.indices.reversed().filter { idx ->
                    val ref = (insns[idx] as? ReferenceInstruction)?.reference as? MethodReference
                    ref != null && ref.name == "A1O" && ref.returnType == "Z" && ref.parameterTypes.isEmpty() &&
                        idx + 1 < insns.size && insns[idx + 1].opcode == Opcode.MOVE_RESULT
                }.forEach { idx ->
                    val reg = (insns[idx + 1] as OneRegisterInstruction).registerA
                    mutable.addInstructions(idx + 2, """
                        invoke-static/range {v$reg .. v$reg}, Lapp/hushmessenger/extension/Settings;->trayConfig(Z)Z
                        move-result v$reg
                    """)
                    wrapped++
                }
            }
        }
        if (wrapped == 0) throw PatchException("Debug tray dump: no A1O read where LX/584; is built")
    }
}
