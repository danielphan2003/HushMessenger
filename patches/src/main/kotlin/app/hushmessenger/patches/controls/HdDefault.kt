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
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * HD photos by default. Messenger can remember the HD button between photos: the pickers start HD as the server gate
 * and the remembered answer together, and sending saves the choice again. The remembered answer is a preference that
 * reads false until it's set. With the switch on, the gate answers yes and the unset preference reads true, so HD starts
 * on, and a photo you send with HD off stores false, which then holds until you turn HD on again.
 *
 * It finds both methods by what they do instead of by name, so no profile records them, the way the Material You theme
 * finds its targets. scripts/compat/Media.java's hdDefaultProblems looks for the same two methods.
 */
internal const val HD_DEFAULT = "hd_default"

/** The server gate for remembering the HD button, as 587 numbers it. Meta renumbers its flags with each release. */
internal const val HD_DEFAULT_FLAG = 0x10100bd00001191L

/** The preference key the remembered HD answer is stored under. */
internal const val HD_TOGGLE_KEY = "hd_button_toggle_state"

/** The two methods the switch changes: the server gate, and the static read of the remembered answer. */
internal class HdDefaultSites(val gate: Method, val read: Method)

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

/** Both methods, each found exactly once, or a PatchException saying which one isn't. */
internal fun findHdDefault(classes: Iterable<ClassDef>): HdDefaultSites {
    val gates = hdDefaultFlagReaders.methods(classes)
    val gate = gates.singleOrNull() ?: hdDefaultChanged("expected one method reading the HD gate's flag, found ${gates.size}")
    gate.validateHdDefaultGate()
    val keyType = hdKeyType()
    val keys = hdDefaultKeyMakers.methods(classes).flatMap { it.hdKeyFields(keyType) }.toSet()
    if (keys.isEmpty()) hdDefaultChanged("the $HD_TOGGLE_KEY preference key isn't made where it was")
    val reads = hdDefaultPreferenceReads.sites(classes).filter { (cls, method) ->
        val field = runCatching { method.hdDefaultRead().second }.getOrNull() ?: return@filter false
        field in keys || (field.substringBefore("->") == cls.type && hdCopiesKey(cls, field, keys))
    }.map { it.second }
    val read = reads.singleOrNull() ?: hdDefaultChanged("expected one read of the $HD_TOGGLE_KEY preference, found ${reads.size}")
    return HdDefaultSites(gate, read)
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
