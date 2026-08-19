package moe.evil.hwhh.analyzer

import jadx.api.plugins.pass.impl.SimpleJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.attributes.AType
import jadx.core.dex.info.FieldInfo
import jadx.core.dex.instructions.ConstStringNode
import jadx.core.dex.instructions.IndexInsnNode
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.instructions.InvokeNode
import jadx.core.dex.instructions.args.ArgType
import jadx.core.dex.instructions.args.InsnArg
import jadx.core.dex.instructions.args.InsnWrapArg
import jadx.core.dex.instructions.args.LiteralArg
import jadx.core.dex.instructions.args.RegisterArg
import jadx.core.dex.instructions.args.SSAVar
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.InsnNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode

internal const val OPTION_CLASS = "com.huawei.hihealth.HiDataReadOption"
internal const val SPORT_OPTION_CLASS = "com.huawei.hihealth.HiSportStatDataAggregateOption"
internal const val SET_TYPE = "setType([I)V"
internal const val SET_KEYS = "setConstantsKey([Ljava/lang/String;)V"
internal const val SET_BOTH = "setType([I[Ljava/lang/String;I)V"
internal const val SET_ALIGN = "setAlignType(I)V"
internal const val STAT_PACKAGE = "com.huawei.hihealthservice."
internal const val STAT_BASE = "com.huawei.hihealthservice.store.stat.HiStatCommon"
private const val FAMILY_SUFFIX = "_type_datas"
private const val WALK_DEPTH = 4

private val ArgType.isListType get() = isObject && `object` == "java.util.List"

internal val FieldInfo.fieldKey get() = "${declClass.fullName}.$name"

private const val MIN_ID = 900L
private val LOG_TAG = Regex("^((?:save|stat)[A-Za-z\\d]*)\\(\\)(?:\\s+([a-z][A-Za-z]*) change)?")

private val InsnArg.wrapped get() = (this as? InsnWrapArg)?.wrapInsn

private val InsnArg.defined get() = wrapped ?: (this as? RegisterArg)?.assignInsn

private val InsnArg.idLiteral
    get() = (this as? LiteralArg)?.literal?.takeIf { it in MIN_ID..Int.MAX_VALUE.toLong() }?.toInt()

private val InsnArg.anyLiteral
    get() = (this as? LiteralArg)?.literal?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()

private val InsnArg.tag: String?
    get() {
        val inner = defined as? InvokeNode ?: return null
        var found: String? = null
        inner.walkDeep(WALK_DEPTH) { insn ->
            if (found == null) found = when (insn) {
                is ConstStringNode -> insn.string
                is InvokeNode if insn.callMth.name.startsWith("fetch") -> insn.callMth.name
                else -> null
            }
        }
        return found
    }

private fun InsnNode.walk(action: (InsnNode) -> Unit) {
    action(this)
    argList.forEach { it.wrapped?.walk(action) }
}

private fun InsnNode.walkDeep(depth: Int, action: (InsnNode) -> Unit) {
    action(this)
    if (depth > 0) argList.forEach { it.defined?.walkDeep(depth - 1, action) }
}

private fun InsnArg.constArray(root: RootNode): List<InsnArg>? {
    val insn = defined ?: return null
    return when (insn.type) {
        InsnType.FILLED_NEW_ARRAY -> insn.argList
        InsnType.SGET -> ((insn as IndexInsnNode).index as? FieldInfo)
            ?.let(root::resolveField)
            ?.get(AType.FIELD_INIT_INSN)?.insn
            ?.takeIf { it.type == InsnType.FILLED_NEW_ARRAY }?.argList

        else -> null
    }
}

private val InsnArg.intValue
    get() = (this as? LiteralArg)?.literal?.toInt()
        ?: (defined as? InvokeNode)?.takeIf { it.callMth.name == "valueOf" }
            ?.argList?.firstOrNull()?.let { (it as? LiteralArg)?.literal?.toInt() }

private fun InsnArg.constInts(root: RootNode) = constArray(root)?.map { it.intValue }

private fun InsnArg.constStrings(root: RootNode) =
    constArray(root)?.map { (it.defined as? ConstStringNode)?.string }

internal sealed interface Fact {
    data class SourceType(val owner: String, val id: Int) : Fact
    data class LogTag(val method: String, val tag: String) : Fact
    data class Consumer(val id: Int, val method: String) : Fact
    data class Slot(val index: Int, val unit: Int) : Fact
    data class Family(val label: String, val field: String) : Fact
}

internal class IdNamePass(
    private val emit: (IdName) -> Unit,
    private val onUnresolved: (String) -> Unit,
    private val onFact: (Fact) -> Unit,
) : JadxDecompilePass {

    private class Slot {
        var ids: List<Int?>? = null
        var names: List<String?>? = null
        var keyed = false
    }

    // Log evidence gathered over one lifetime. Two instances live in Scanner, a statement scoped
    // one cleared per top-level instruction and a body scoped one it folds into; keeping the three
    // sets together means a fourth collector is cleared and merged without touching call sites.
    private class LogBucket {
        val tags = LinkedHashSet<String>()
        val details = LinkedHashSet<String>()
        val ids = LinkedHashSet<Int>()

        // Bare tag, the value Fact.LogTag joins on. pair() adds the discriminator, LogTag must not.
        val tag get() = tags.singleOrNull()

        operator fun plusAssign(other: LogBucket) {
            tags += other.tags
            details += other.details
            ids += other.ids
        }

        fun clear() {
            tags.clear()
            details.clear()
            ids.clear()
        }

        inline fun pair(action: (Int, String) -> Unit) {
            val id = ids.singleOrNull() ?: return
            val only = tags.singleOrNull() ?: return
            action(id, details.singleOrNull()?.let { "${only}_$it" } ?: only)
        }
    }

    private inner class Scanner(mth: MethodNode) {
        val root = mth.root()
        val owner = mth.parentClass.classInfo.fullName
        val origin = "$owner#${mth.name}"
        val statScope = owner.startsWith(STAT_PACKAGE)
        val statSubclass = mth.parentClass.superClass
            ?.let { it.isObject && it.`object` == STAT_BASE } == true
        val slots = HashMap<SSAVar, Slot>()
        val stmt = LogBucket()
        val body = LogBucket()
        var alignID: Int? = null

        // Four independent detectors over one call. statSubclass narrows to the 14 direct
        // HiStatCommon subclasses; ADJACENT_TAG stays outside that guard on purpose, otherwise
        // SportStatSwitch (not a subclass) loses 901-907.
        fun statInvoke(call: InvokeNode, args: List<InsnArg>) {
            val lits = args.mapNotNull { it.idLiteral }
            stmt.ids += lits
            if (statSubclass) {
                // <se> L64 List<HiHealthData> listM128985c = this.f33073d.m128985c(hiDataReadOption, 7, list, (ksw) null);
                // Then, emit -> SourceType(owner=com.huawei.hihealthservice.store.stat.HiExerciseIntensityStat, id=7)
                // HealthIdAnalyzer keeps an owner only when its ids collapse to one, so a call
                // carrying two literals poisons the whole owner. The aggregate readers take a
                // groupUnitType next to the type, e.g. HiNewStressStat L92
                //   this.f33083c.m129121a(list, j, j2, 3, 2034, new String[]{..}, new int[]{..}, 0)
                // which used to contribute both 3 and 2034; singleOrNull drops that call outright
                // and leaves L88 m129131c(hiDataReadOption, 2034, list, null) as the only voice.
                if (call.callMth.returnType.isListType) {
                    args.mapNotNull { it.anyLiteral }.singleOrNull()
                        ?.let { onFact(Fact.SourceType(owner, it)) }
                }
                trackSlot(args)
                trackFamily(args)
            }
            // <sh> HiNewStressStat L76 m39329d(ktwVar, hiHealthData2.getDouble("stress_score_max"), 44305);
            // Then, emit -> IdName(id=44305, name=stress_score_max, source=ADJACENT_TAG)
            // tag also matches a fetch* callee name, which is how SportStatSwitch#m39559a yields
            // 901=fetchSteps .. 907=fetchPushesVal. Strongest binding we have: id and label sit in
            // the same argument list, so unlike METHOD_LOG no tag is ever shared across ids.
            val tags = args.mapNotNull { it.tag }
            if (lits.size == 1 && tags.size == 1) {
                emit(IdName(lits[0], tags[0], IdNameSource.ADJACENT_TAG, origin))
            }
        }

        // <sf> HiTrackStat L408 m39373a(this.f33108aa, numArr[0].intValue(), syncStatus, this.f33120am, 1);
        // Then, emit -> Slot(index=0, unit=1)
        // index is peeled off the receiver chain until an AGET shows up (Integer.intValue wraps
        // it); unit is the one bare literal. L375 m39373a(.., 42310, syncStatus, totalSteps, 1)
        // carries two bare literals, so singleOrNull bails and 42310 is not mistaken for a unit.
        private fun trackSlot(args: List<InsnArg>) {
            val index = args.mapNotNull { arg ->
                generateSequence(arg.defined) { node ->
                    (node as? InvokeNode)?.argList?.firstOrNull()?.defined
                }.take(WALK_DEPTH)
                    .firstOrNull { it.type == InsnType.AGET }
                    ?.argList?.getOrNull(1)?.intValue
            }.singleOrNull() ?: return
            val unit = args.mapNotNull { (it as? LiteralArg)?.literal?.toInt() }
                .singleOrNull() ?: return
            onFact(Fact.Slot(index, unit))
        }

        // <sg> HiTrackStat L263 m39372a(mapM39379c.get("walk_type_datas"), hiHealthData, f33107j, arrayList);
        // Then, emit -> Family(label=walk, field=com.huawei.hihealthservice.store.stat.HiTrackStat.j)
        // The field key is the real dex name j, not the f33107j jadx renders; the xposed side
        // reflects on declaringClass.name + "." + field.name at runtime and sees j too.
        private fun trackFamily(args: List<InsnArg>) {
            var label: String? = null
            args.forEach { arg ->
                arg.defined?.walkDeep(WALK_DEPTH) { inner ->
                    if (label == null) label = (inner as? ConstStringNode)
                        ?.string?.takeIf { it.endsWith(FAMILY_SUFFIX) }
                }
            }
            val family = label ?: return
            val field = args.mapNotNull {
                ((it.defined as? IndexInsnNode)
                    ?.takeIf { n -> n.type == InsnType.SGET }
                    ?.index as? FieldInfo)?.fieldKey
            }.singleOrNull() ?: return
            onFact(Fact.Family(family.removeSuffix(FAMILY_SUFFIX), field))
        }

    }

    private fun HashMap<SSAVar, Slot>.slot(args: List<InsnArg>) =
        (args.firstOrNull() as? RegisterArg)?.sVar?.let { getOrPut(it) { Slot() } }

    override fun getInfo() = SimpleJadxPassInfo("HwHealthIdName")

    override fun init(root: RootNode) = Unit

    override fun visit(cls: ClassNode) = true

    override fun visit(mth: MethodNode) {
        val blocks = mth.basicBlocks ?: return
        with(Scanner(mth)) {
            blocks.forEach { block ->
                alignID = null
                block.instructions.forEach { top ->
                    stmt.clear()
                    // #region emit ID2Method, will pair with LogTag
                    (top as? InvokeNode)?.let { call ->
                        when {
                            // <sa> L123 hiAggregateOption.setAlignType(20003);
                            call.callMth.shortId == SET_ALIGN ->
                                alignID = call.argList.getOrNull(1)?.anyLiteral

                            call.callMth.declClass.fullName == owner -> {
                                val callee = "$owner#${call.callMth.shortId}"
                                // self-connect within self-provided AlignID
                                // <sb> L107 m39320b(this.f33077d.m129022b(hiDataReadOption, 2105, listM129380c, (ksw) null), ktwVar);
                                // Then, emit -> Consumer(id=2105, method=com.huawei.hihealthservice.store.stat.HiHeartRateAndRestHeartRateStat#b(Ljava/util/List;Lktw;)Z)
                                call.argList
                                    .mapNotNull { arg ->
                                        (arg.defined as? InvokeNode)
                                            ?.takeIf { it.callMth.returnType.isListType }
                                            ?.argList?.mapNotNull { it.anyLiteral }
                                            ?.singleOrNull()
                                    }
                                    .singleOrNull()
                                    ?.let { onFact(Fact.Consumer(it, callee)) }
                                // <sa> connect with previous alignID which is saved in `call.callMth.shortId == SET_ALIGN`
                                // e.g. L124 m39361e(this.f33095e.m129202c(list, hiAggregateOption), j);
                                // Then, emit -> Consumer(id=20003, method=com.huawei.hihealthservice.store.stat.HiSportStat#e(Ljava/util/List;J)Z)
                                alignID?.let { onFact(Fact.Consumer(it, callee)) }
                                alignID = null
                            }
                        }
                    }
                    // #region fill stmt bucket & (setType + setConstantsKey) slots
                    top.walk { insn ->
                        // group1 = caller method name, always present but shared by many IDs
                        // (helpers log their caller's name); group2 = the per-ID discriminator,
                        // only trusted when followed by " change", which is an anchor and is
                        // not part of the name.
                        // <sb> L167 LogUtil.m50476j("Debug_HiHeartRateAndRestHeartRateStat", "saveLastRestHeartRateStat() statLastDatasRest is null");
                        // Then, collect -> tag=saveLastRestHeartRateStat, no detail
                        // <sc> L256 LogUtil.m50468e("Debug_HiSportStat", "saveRunSessionStat()  distance change = ", Boolean.valueOf(m39359e(j, 40032, this.f33096g, hiHealthData.getDouble(strArr[1]), 2)));
                        // Then, collect -> tag=saveRunSessionStat, detail=distance
                        if (insn.type == InsnType.CONST_STR) {
                            LOG_TAG.find((insn as ConstStringNode).string)?.let {
                                stmt.tags += it.groupValues[1]
                                it.groupValues[2].takeIf(String::isNotEmpty)?.let(stmt.details::add)
                            }
                            return@walk
                        }
                        val call = insn as? InvokeNode ?: return@walk
                        if (call.callMth.declClass.fullName == SPORT_OPTION_CLASS) return@walk
                        val args = call.argList
                        when (call.callMth.shortId) {
                            // simple emit
                            SET_TYPE ->
                                slots.slot(args)?.ids = args.getOrNull(1)?.constInts(root)

                            SET_KEYS -> slots.slot(args)?.apply {
                                keyed = true
                                names = args.getOrNull(1)?.constStrings(root)
                            }

                            SET_BOTH -> slots.slot(args)?.apply {
                                keyed = true
                                ids = args.getOrNull(1)?.constInts(root)
                                names = args.getOrNull(2)?.constStrings(root)
                            }
                            // complex emit: everything that is not an option setter. The three
                            // branches above run app-wide because setType/setConstantsKey call
                            // sites are everywhere; the guesswork below is confined to the stat
                            // package, otherwise the whole app drowns it in noise.
                            else -> if (statScope) statInvoke(call, args)
                        }
                    }
                    // Statement-scoped pairing: the log string and the ID sit in the same
                    // wrapped tree, so one top-level instruction can name one ID even when the
                    // enclosing method touches several (method-scoped rule below would bail).
                    // <sc> L256 LogUtil.m50468e("Debug_HiSportStat", "saveRunSessionStat()  distance change = ", Boolean.valueOf(m39359e(j, 40032, this.f33096g, hiHealthData.getDouble(strArr[1]), 2)));
                    // Then, emit -> IdName(id=40032, name=saveRunSessionStat_distance, source=METHOD_LOG)
                    // 40032 had no other candidate at all; L255/L257 name 40012/40022 the same way.
                    if (statScope) {
                        stmt.pair { id, name ->
                            emit(IdName(id, name, IdNameSource.METHOD_LOG, origin))
                        }
                    }
                    body += stmt
                }
            }
            // flushSlots
            slots.values.forEach { slot ->
                val ids = slot.ids
                val names = slot.names
                if (ids == null || names == null || ids.size != names.size) {
                    if (slot.keyed) onUnresolved(origin)
                    return@forEach
                }
                ids.forEachIndexed { index, id ->
                    val name = names[index]
                    if (id != null && !name.isNullOrEmpty()) {
                        emit(IdName(id, name, IdNameSource.CONSTANTS_KEY, origin))
                    }
                }
            }
            // flushLogTag
            body.tag?.let { tag ->
                // The tag is contributed by the callee itself, so the key must byte-match the
                // Consumer side "$owner#${call.callMth.shortId}"; origin (owner#mth.name) would
                // collide, HiSportStat alone has five methods named e. A mismatch makes
                // HealthIdAnalyzer's tags[method] miss and the whole join silently yield nothing.
                // Not gated by statScope, so it also fires outside the stat package.
                // <sb> L167 LogUtil.m50476j("Debug_HiHeartRateAndRestHeartRateStat", "saveLastRestHeartRateStat() statLastDatasRest is null");
                // Then, emit -> LogTag(method=com.huawei.hihealthservice.store.stat.HiHeartRateAndRestHeartRateStat#b(Ljava/util/List;Lktw;)Z, tag=saveLastRestHeartRateStat)
                // (will) joined with <sb> Consumer(id=2105, same method) -> IdName(2105, saveLastRestHeartRateStat, METHOD_LOG) (in HealthIdAnalyzer)
                onFact(Fact.LogTag("$owner#${mth.methodInfo.shortId}", tag))
                if (statScope) {
                    // Method-scoped fallback: needs exactly one tag and exactly one ID in the
                    // whole body, so it bails on <sc> L255-L257 (three IDs) but still covers
                    // helpers that handle a single metric.
                    // <sd> L214 LogUtil.m50462c("Debug_HiSportStat", "saveAllSessionStat()  pushes change = ", Boolean.valueOf(m39359e(j, 40008, lfr.m130384e(lfr.m130378b(this.mContext, this.f33097j, i, 40008), d, this.f33096g), d, 16)));
                    // Then, emit -> IdName(id=40008, name=saveAllSessionStat_pushes, source=METHOD_LOG)
                    // 40008 appears twice in that statement, the Set collapses it to one.
                    body.pair { id, name ->
                        emit(IdName(id, name, IdNameSource.METHOD_LOG, origin))
                    }
                }
            }
        }
    }
}
