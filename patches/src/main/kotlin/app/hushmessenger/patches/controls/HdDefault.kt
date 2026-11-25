package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * HD photos by default. Messenger can remember the HD button between photos: the pickers start HD as the server gate
 * and the remembered answer together, and sending saves the choice again. The remembered answer is a preference that
 * reads false until it's set. With the switch on, the gate answers yes and the unset preference reads true, so HD starts
 * on, and a photo you send with HD off stores false, which then holds until you turn HD on again.
 *
 * It finds each method by what it does instead of by name, so no profile records them, the way the Material You theme
 * finds its targets. scripts/compat/Media.java's hdDefaultProblems looks for the same three methods.
 */
internal const val HD_DEFAULT = "hd_default"

/** The server gate for remembering the HD button, as 587 numbers it. Meta renumbers its flags with each release. */
internal const val HD_DEFAULT_FLAG = 0x10100bd00001191L

/** The preference key the remembered HD answer is stored under. */
internal const val HD_TOGGLE_KEY = "hd_button_toggle_state"

/** The attachment preview's insert event, the one method that takes it first. */
internal const val HD_INSERT_PREVIEW = "Lcom/facebook/xapp/messaging/composer/attachmentpreview/events/InsertAttachmentPreviewEvent;"

/** The composer's own view, and the flag its send click reads. */
internal const val HD_COMPOSER = "Lcom/facebook/messaging/composer/OneLineComposerView;"

/** The composer's send click logs this before it rebuilds the attachments with the composer's HD answer. */
internal const val HD_COMPOSER_SEND = "ComposeFragment.onSendClicked"

/** The reactive props object the HD control's click handler takes first. */
internal const val HD_REACTIVE_PROPS = "LX/4wl;"

/**
 * The methods the switch changes: the server gate, the static read of the remembered answer, the handler that files a
 * picked photo in the composer's attachment preview, the composer's send click and the preview's HD toggle.
 */
internal class HdDefaultSites(val gate: Method, val read: Method, val write: Method, val insert: Method,
    val send: Method, val toggle: Method)

/** The insert handler's store of the composer's HD answer: the setter call's index, its answer register, a spare local. */
internal class HdDefaultInsert(val at: Int, val answer: Int, val temp: Int)

/** The send click's view field, the view it casts to, and the HD flag it reads. */
internal class HdDefaultSend(val viewField: FieldReference, val hd: FieldReference)

/** The preview toggle's store of the flag: its index and the register holding the new answer. */
internal class HdDefaultToggle(val at: Int, val value: Int)

/** What findControls found for the switch, or why it found nothing. Null until it runs, and again after the patch run. */
internal var hdDefaultSearch: Result<HdDefaultSites>? = null

private fun hdDefaultChanged(what: String): Nothing =
    throw PatchException("HD photos by default: $what. Use an unmodified supported Messenger APK.")

private fun Method.hdCode(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.hdRegister() = (this as? OneRegisterInstruction)?.registerA

private fun Instruction.hdRef() = (this as? ReferenceInstruction)?.reference

private fun Instruction.isHdDefaultFlag() =
    opcode == Opcode.CONST_WIDE && (this as? WideLiteralInstruction)?.wideLiteral == HD_DEFAULT_FLAG

/** The registers a call passes, in order, for the plain and the range form alike. */
private fun Instruction.hdArguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    else -> emptyList()
}

/** The preference key type the build's boolean preference read takes, from the profile's getter. */
private fun hdKeyType() = activeProfile.preferenceGetter.substringAfter('(').substringBefore(')').removeSuffix("Z")

/** Every `(FbUserSession)Z` method that loads the gate's flag. findControls answers it from its shared walk. */
internal val hdDefaultFlagReaders = methodCheck { _, method ->
    method.returnType == "Z" && method.parameterTypes.size == 1 && method.parameterTypes[0].toString() == FB_USER_SESSION &&
        method.implementation?.instructions?.any { it.isHdDefaultFlag() } == true
}

/** Every method that loads the preference key's name, of which the class initializer that makes the key is one. */
internal val hdDefaultKeyMakers = MethodCheck(loadsString(HD_TOGGLE_KEY))

/** Every static ()Z method that reads a boolean preference with the build's getter. */
internal val hdDefaultPreferenceReads = MethodCheck {
    val getter = MemberMatch(activeProfile.preferenceGetter)
    val test: MethodTest = { _, method ->
        method.returnType == "Z" && method.parameterTypes.isEmpty() && AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any { getter.matches(it.hdRef()) } == true
    }
    test
}

/**
 * The gate: `(FbUserSession)Z`, whose one load of [HD_DEFAULT_FLAG] goes straight into the build's server flag read,
 * and whose answer it returns. Throws when the method is anything else.
 */
internal fun Method.validateHdDefaultGate() {
    val code = hdCode()
    if (returnType != "Z" || parameterTypes.map(CharSequence::toString) != listOf(FB_USER_SESSION)) hdDefaultChanged("the HD gate's signature changed (${hookId()})")
    val flags = code.indices.filter { code[it].isHdDefaultFlag() }
    val flag = flags.singleOrNull() ?: hdDefaultChanged("the HD gate loads its flag ${flags.size} times (${hookId()})")
    val wide = code[flag].hdRegister()!!
    val reads = setOf(activeProfile.bubbleRolloutGetter, activeProfile.peopleFlagCheck)
    val call = (flag + 1 until code.size).firstOrNull { code[it].hdRef()?.toString() in reads }
        ?: hdDefaultChanged("the HD gate no longer asks the server for its flag (${hookId()})")
    val writesFlag = (flag + 1 until call).any { i ->
        val written = code[i].hdRegister()
        code[i].opcode.setsRegister() && written != null && (written == wide || written == wide + 1 ||
            (code[i].opcode.setsWideRegister() && written + 1 == wide))
    }
    val result = code.getOrNull(call + 1)
    val exit = code.getOrNull(call + 2)
    if (writesFlag || code[call].hdArguments().takeLast(2) != listOf(wide, wide + 1) ||
        result?.opcode != Opcode.MOVE_RESULT || exit?.opcode != Opcode.RETURN || result?.hdRegister() != exit?.hdRegister()) {
        hdDefaultChanged("the HD gate no longer returns the server's answer (${hookId()})")
    }
    val parameterWords = if (AccessFlags.STATIC.isSet(accessFlags)) 1 else 2
    if ((implementation?.registerCount ?: 0) <= parameterWords) hdDefaultChanged("the HD gate has no free register (${hookId()})")
}

/**
 * The read: static `()Z`, one call to the build's preference getter with a key read from a static field and a literal
 * false default set just before it, and the answer returned straight away. Returns the default's index and the key field.
 */
internal fun Method.hdDefaultRead(): Pair<Int, String> {
    val code = hdCode()
    if (returnType != "Z" || parameterTypes.isNotEmpty() || !AccessFlags.STATIC.isSet(accessFlags)) hdDefaultChanged("the HD read's signature changed (${hookId()})")
    val calls = code.indices.filter { code[it].hdRef()?.toString() == activeProfile.preferenceGetter }
    val call = calls.singleOrNull() ?: hdDefaultChanged("the HD read asks for a preference ${calls.size} times (${hookId()})")
    val arguments = code[call].hdArguments()
    if (code[call].opcode != Opcode.INVOKE_INTERFACE || arguments.size != 3) hdDefaultChanged("the HD read's preference call changed (${hookId()})")
    val (key, default) = arguments[1] to arguments[2]
    val literal = code.getOrNull(call - 1)
    if (literal?.opcode != Opcode.CONST_4 || literal?.hdRegister() != default || (literal as? NarrowLiteralInstruction)?.narrowLiteral != 0) {
        hdDefaultChanged("the HD read no longer defaults to false just before it asks (${hookId()})")
    }
    // The switch's answer goes in between the default and the call, so nothing may jump to the call past it.
    if (default >= 16 || call in jumpTargets()) hdDefaultChanged("the HD read's default can't take the switch (${hookId()})")
    val load = (call - 2 downTo 0).firstOrNull { code[it].opcode.setsRegister() && code[it].hdRegister() == key }
        ?.takeIf { code[it].opcode == Opcode.SGET_OBJECT } ?: hdDefaultChanged("the HD read's key isn't a static field (${hookId()})")
    val result = code.getOrNull(call + 1)
    val exit = code.getOrNull(call + 2)
    if (result?.opcode != Opcode.MOVE_RESULT || exit?.opcode != Opcode.RETURN || result?.hdRegister() != exit?.hdRegister()) {
        hdDefaultChanged("the HD read no longer returns the preference (${hookId()})")
    }
    return (call - 1) to code[load].hdRef().toString()
}

/** The static fields a class initializer stores the key made from [HD_TOGGLE_KEY] in: the first store after its name. */
private fun Method.hdKeyFields(keyType: String): Set<String> {
    if (name != "<clinit>") return emptySet()
    val code = hdCode()
    return code.indices.filter { (code[it].hdRef() as? StringReference)?.string == HD_TOGGLE_KEY }.mapNotNull { at ->
        val store = (at + 1 until code.size).firstOrNull { code[it].opcode == Opcode.SPUT_OBJECT || code[it].opcode == Opcode.CONST_STRING }
            ?: return@mapNotNull null
        val field = code[store].hdRef() as? FieldReference
        if (code[store].opcode == Opcode.SPUT_OBJECT && field?.type == keyType) field.toString() else null
    }.toSet()
}

/** Whether [cls]'s initializer copies one of [keys] into [field], as a holder of its own preference keys does. */
private fun hdCopiesKey(cls: ClassDef, field: String, keys: Set<String>): Boolean {
    val code = cls.methods.singleOrNull { it.name == "<clinit>" }?.hdCode() ?: return false
    return code.indices.any { i ->
        val next = code.getOrNull(i + 1)
        code[i].opcode == Opcode.SGET_OBJECT && code[i].hdRef().toString() in keys &&
            next?.opcode == Opcode.SPUT_OBJECT && next.hdRegister() == code[i].hdRegister() && next.hdRef().toString() == field
    }
}

/** Every static void method whose first parameter is the attachment preview's insert event. */
internal val hdDefaultInserts = methodCheck { _, method ->
    method.returnType == "V" && AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.firstOrNull()?.toString() == HD_INSERT_PREVIEW
}

/**
 * The insert handler's store of the composer's HD answer: one call to a static `(box, Z)V` setter, the box among the
 * handler's parameters and a local register as the answer, which starts false and is set true on the HD branch. The
 * hook needs one more local it may clobber, so this refuses a handler that has none.
 */
internal fun Method.hdDefaultInsert(): HdDefaultInsert {
    val code = hdCode()
    val params = parameterTypes.map(CharSequence::toString)
    val firstParameter = parameterRegister(0)
    val calls = code.indices.filter { i ->
        val call = code[i].hdRef() as? MethodReference ?: return@filter false
        val types = call.parameterTypes.map(CharSequence::toString)
        code[i].opcode == Opcode.INVOKE_STATIC && call.returnType == "V" && types.size == 2 && types[1] == "Z" &&
            types[0] in params
    }
    val at = calls.singleOrNull() ?: hdDefaultChanged("expected one store of the composer's HD answer, found ${calls.size}")
    val arguments = code[at].hdArguments()
    if (arguments.size != 2) hdDefaultChanged("the composer's HD answer store changed")
    val answer = arguments[1]
    if (answer >= firstParameter) hdDefaultChanged("the composer's HD answer is a parameter now")
    val literal = (0 until at).any { code[it].opcode == Opcode.CONST_4 && code[it].hdRegister() == answer &&
        (code[it] as? NarrowLiteralInstruction)?.narrowLiteral == 0 }
    if (!literal) hdDefaultChanged("the composer's HD answer no longer starts false")
    if (code.getOrNull(at + 1)?.opcode != Opcode.RETURN_VOID) hdDefaultChanged("the composer's HD answer isn't stored last")
    val temp = (0 until firstParameter).firstOrNull { it != answer && it !in arguments }
        ?: hdDefaultChanged("the attachment preview's handler has no free register")
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    if (implementation!!.tryBlocks.any { addresses[at] - it.startCodeAddress in 0 until it.codeUnitCount }) {
        hdDefaultChanged("the composer's HD answer store sits in a try block")
    }
    return HdDefaultInsert(at, answer, temp)
}

/** When the switch is on, a picked photo with no stored answer counts as HD here too, so the composer's send agrees. */
internal fun MutableMethod.injectHdDefaultInsert(site: HdDefaultInsert, read: Method) {
    addInstructionsWithLabels(site.at, """
        if-eqz v${site.answer}, :store
        const-string v${site.temp}, "$HD_DEFAULT"
        invoke-static {v${site.temp}}, $SETTINGS->enabled(Ljava/lang/String;)Z
        move-result v${site.temp}
        if-eqz v${site.temp}, :store
        invoke-static {}, ${read.definingClass}->${read.name}()Z
        move-result v${site.answer}
    """.trimIndent(), ExternalLabel("store", getInstruction(site.at)))
}

/** Every method that logs the composer's send click. findControls answers it from its shared walk. */
internal val hdDefaultComposerSends = MethodCheck { loadsString(HD_COMPOSER_SEND)() }

/** Every reactive click handler with the preview HD toggle's signature. */
internal val hdDefaultToggles = methodCheck { _, method ->
    method.returnType == "Ljava/lang/Object;" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(HD_REACTIVE_PROPS, "Ljava/lang/Object;") &&
        runCatching { method.hdDefaultToggle() }.isSuccess
}

/**
 * The composer's send click: a static void method that logs [HD_COMPOSER_SEND], loads the composer view from its chat
 * argument and reads the view's HD flag. The hook needs two spare locals at the top, so this refuses a method without.
 */
internal fun Method.hdDefaultSend(): HdDefaultSend {
    val code = hdCode()
    if (returnType != "V" || !AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.size != 1) {
        hdDefaultChanged("the composer send's signature changed (${hookId()})")
    }
    val reads = code.indices.filter { i ->
        code[i].opcode == Opcode.IGET_BOOLEAN &&
            (code[i].hdRef() as? FieldReference)?.let { it.type == "Z" && it.definingClass == HD_COMPOSER } == true
    }
    val read = reads.singleOrNull() ?: hdDefaultChanged("expected one read of the composer's HD flag, found ${reads.size}")
    val field = code[read].hdRef() as FieldReference
    val viewRegister = (code[read] as? TwoRegisterInstruction)?.registerB
        ?: hdDefaultChanged("the composer's HD flag read changed")
    val cast = (read - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.CHECK_CAST && code[it].hdRef().toString() == HD_COMPOSER && code[it].hdRegister() == viewRegister
    } ?: hdDefaultChanged("the composer send no longer casts its view")
    val view = (cast - 1 downTo 0).firstOrNull { i ->
        code[i].opcode == Opcode.IGET_OBJECT && code[i].hdRegister() == viewRegister &&
            (code[i].hdRef() as? FieldReference)?.definingClass == parameterTypes[0].toString()
    } ?: hdDefaultChanged("the composer send no longer loads its view from the chat")
    if ((code[view] as? TwoRegisterInstruction)?.registerB != parameterRegister(0)) {
        hdDefaultChanged("the composer send's view field moved")
    }
    if (parameterRegister(0) < 2) hdDefaultChanged("the composer send has no free register")
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    if (implementation!!.tryBlocks.any { 0 - it.startCodeAddress in 0 until it.codeUnitCount }) {
        hdDefaultChanged("the composer send starts in a try block")
    }
    return HdDefaultSend(code[view].hdRef() as FieldReference, field)
}

/**
 * The preview's HD toggle: one boolean field read into a register, inverted there, and stored back to the same field.
 * The store's register is the new answer, which the hook hands to the remembered preference.
 */
internal fun Method.hdDefaultToggle(): HdDefaultToggle {
    if (returnType != "Ljava/lang/Object;" ||
        parameterTypes.map(CharSequence::toString) != listOf(HD_REACTIVE_PROPS, "Ljava/lang/Object;")) {
        hdDefaultChanged("the preview's HD toggle signature changed (${hookId()})")
    }
    val code = hdCode()
    val writes = code.indices.filter { i ->
        val field = code[i].hdRef() as? FieldReference ?: return@filter false
        if (code[i].opcode != Opcode.IPUT_BOOLEAN || field.type != "Z") return@filter false
        val value = code[i].hdRegister() ?: return@filter false
        val target = (code[i] as? TwoRegisterInstruction)?.registerB ?: return@filter false
        (0 until i).any { j ->
            code[j].opcode == Opcode.IGET_BOOLEAN && code[j].hdRegister() == value &&
                code[j].hdRef().toString() == field.toString() && (code[j] as? TwoRegisterInstruction)?.registerB == target &&
                (j + 1 until i).any { code[it].opcode == Opcode.XOR_INT_LIT8 && code[it].hdRegister() == value }
        }
    }
    val at = writes.singleOrNull() ?: hdDefaultChanged("expected one toggle of the composer's HD flag, found ${writes.size}")
    val value = code[at].hdRegister()!!
    if (value >= parameterRegister(0)) hdDefaultChanged("the preview's HD toggle answers from a parameter")
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    if (implementation!!.tryBlocks.any { addresses[at] - it.startCodeAddress in 0 until it.codeUnitCount }) {
        hdDefaultChanged("the preview's HD toggle sits in a try block")
    }
    return HdDefaultToggle(at, value)
}

/** While the switch is on, the composer's send reads the remembered answer instead of the picker's stale default. */
internal fun MutableMethod.injectHdDefaultSend(site: HdDefaultSend, read: Method) {
    addInstructionsWithLabels(0, """
        iget-object v0, p0, ${site.viewField}
        if-eqz v0, :stock
        check-cast v0, $HD_COMPOSER
        const-string v1, "$HD_DEFAULT"
        invoke-static {v1}, $SETTINGS->enabled(Ljava/lang/String;)Z
        move-result v1
        if-eqz v1, :stock
        invoke-static {}, ${read.definingClass}->${read.name}()Z
        move-result v1
        iput-boolean v1, v0, ${site.hd}
    """.trimIndent(), ExternalLabel("stock", getInstruction(0)))
}

/** A toggle in the full preview is the remembered answer from then on, even when the photo is sent from the chat. */
internal fun MutableMethod.injectHdDefaultToggle(site: HdDefaultToggle, write: Method) {
    addInstructions(site.at + 1, "invoke-static {v${site.value}}, ${write.definingClass}->${write.name}(Z)V")
}

/** Both methods, each found exactly once, or a PatchException saying which one isn't. */
internal fun findHdDefault(classes: Iterable<ClassDef>): HdDefaultSites {
    val gates = hdDefaultFlagReaders.methods(classes)
    val gate = gates.singleOrNull() ?: hdDefaultChanged("expected one method reading the HD gate's flag, found ${gates.size}")
    gate.validateHdDefaultGate()
    val keyType = hdKeyType()
    val keys = hdDefaultKeyMakers.methods(classes).flatMap { it.hdKeyFields(keyType) }.toSet()
    if (keys.isEmpty()) hdDefaultChanged("the $HD_TOGGLE_KEY preference key isn't made where it was")
    val reads = hdDefaultPreferenceReads.sites(classes).mapNotNull { (cls, method) ->
        val field = runCatching { method.hdDefaultRead().second }.getOrNull() ?: return@mapNotNull null
        if (field in keys || (field.substringBefore("->") == cls.type && hdCopiesKey(cls, field, keys))) method to field else null
    }
    val found = reads.singleOrNull() ?: hdDefaultChanged("expected one read of the $HD_TOGGLE_KEY preference, found ${reads.size}")
    val read = found.first
    val writes = classes.firstOrNull { it.type == read.definingClass }?.methods.orEmpty().filter { method ->
        method.returnType == "V" && AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Z") &&
            method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == found.second } == true
    }
    val write = writes.singleOrNull() ?: hdDefaultChanged("expected one store of the $HD_TOGGLE_KEY preference, found ${writes.size}")
    val inserts = hdDefaultInserts.methods(classes)
    val insert = inserts.singleOrNull() ?: hdDefaultChanged("expected one handler for media inserted into the composer, found ${inserts.size}")
    insert.hdDefaultInsert()
    val sends = hdDefaultComposerSends.methods(classes)
    val send = sends.singleOrNull() ?: hdDefaultChanged("expected one composer send, found ${sends.size}")
    send.hdDefaultSend()
    val toggles = hdDefaultToggles.methods(classes)
    val toggle = toggles.singleOrNull() ?: hdDefaultChanged("expected one preview HD toggle, found ${toggles.size}")
    toggle.hdDefaultToggle()
    return HdDefaultSites(gate, read, write, insert, send, toggle)
}

/** While the switch is on, the gate answers yes before it asks the server. */
internal fun MutableMethod.injectHdDefaultGate() {
    validateHdDefaultGate()
    addInstructionsWithLabels(0, """
        const-string v0, "$HD_DEFAULT"
        invoke-static {v0}, $SETTINGS->enabled(Ljava/lang/String;)Z
        move-result v0
        if-eqz v0, :stock_behavior
        const/4 v0, 0x1
        return v0
    """.trimIndent(), ExternalLabel("stock_behavior", getInstruction(0)))
}

/** The read's false default becomes the switch, so an unset preference reads true while it's on. A stored answer stands. */
internal fun MutableMethod.injectHdDefaultRead() {
    val (at, _) = hdDefaultRead()
    val register = getInstruction(at).hdRegister()!!
    replaceInstruction(at, "const-string v$register, \"$HD_DEFAULT\"")
    addInstructions(at + 1, "invoke-static {v$register}, $SETTINGS->enabled(Ljava/lang/String;)Z\nmove-result v$register")
}
