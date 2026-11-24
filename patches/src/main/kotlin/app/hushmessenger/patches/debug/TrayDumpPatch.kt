package app.hushmessenger.patches.debug

import app.hushmessenger.patches.MessengerTarget
import app.hushmessenger.patches.controls.AUTO_REPLY_MEDIA
import app.hushmessenger.patches.controls.INSERT_PREVIEW
import app.hushmessenger.patches.controls.settingsExtension
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

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
    }
}
