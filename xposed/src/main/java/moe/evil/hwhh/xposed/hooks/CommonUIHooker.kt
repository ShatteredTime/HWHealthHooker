package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.View
import com.huawei.ui.commonui.dialog.CustomProgressDialog
import com.huawei.ui.commonui.dialog.CustomTextAlertDialog
import com.huawei.ui.commonui.dialog.CustomViewDialog
import com.huawei.ui.commonui.dialog.NoTitleCustomAlertDialog
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.R
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HookApi
import moe.evil.hwhh.xposed.utils.moduleString
import moe.evil.hwhh.xposed.utils.toast
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.HostConstructor
import moe.evil.hwhh.xposed.utils.wrapper.HostFieldData
import moe.evil.hwhh.xposed.utils.wrapper.HostMethod
import moe.evil.hwhh.xposed.utils.wrapper.HostMethodData
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.createOrNull
import moe.evil.hwhh.xposed.utils.wrapper.invokeOrNull
import moe.evil.hwhh.xposed.utils.wrapper.method
import moe.evil.hwhh.xposed.utils.wrapper.optionally
import moe.evil.hwhh.xposed.utils.wrapper.orFail
import moe.evil.hwhh.xposed.utils.wrapper.requireConstructor
import moe.evil.hwhh.xposed.utils.wrapper.requireMethod
import moe.evil.hwhh.xposed.utils.wrapper.requireNotEmpty

internal data class DialogButton(
    val text: String,
    val listener: View.OnClickListener = View.OnClickListener {},
)

internal class PendingDialog<T : Dialog> internal constructor(
    private val activity: Activity,
    private val build: () -> T?,
) {
    fun gracefulShow(
        onUnavailable: Activity.() -> Unit = {
            toast(moduleString(R.string.hwhh_dialog_unavailable))
        },
    ) = activity.runOnUiThread { build()?.also(Dialog::show) ?: activity.onUnavailable() }
}

internal class ProgressDialogHandle internal constructor(
    private val activity: Activity,
    private val build: () -> Session?,
) {
    internal class Session internal constructor(
        val dialog: CustomProgressDialog,
        val builder: CustomProgressDialog.Builder,
        val progressMethod: HostMethod<CustomProgressDialog.Builder>,
        val descMethod: HostMethod<CustomProgressDialog.Builder>,
    )

    private var session: Session? = null

    fun gracefulShow(
        onUnavailable: Activity.() -> Unit = {
            toast(moduleString(R.string.hwhh_dialog_unavailable))
        },
    ) = apply {
        activity.runOnUiThread {
            session = build()?.also { it.dialog.show() }
                ?: null.also { activity.onUnavailable() }
        }
    }

    fun dismiss() = activity.runOnUiThread { session?.dialog?.dismiss() }

    fun setProgress(percent: Int) = activity.runOnUiThread {
        session?.run { progressMethod.invokeOrNull(builder, percent) }
    }

    fun setMessage(text: String) = activity.runOnUiThread {
        session?.run { descMethod.invokeOrNull(builder, text) }
    }
}

internal interface CommonUiApi : HookApi {
    fun createCustomTextAlertDialog(
        activity: Activity,
        title: String,
        message: String,
        positive: DialogButton? = null,
        negative: DialogButton? = null,
    ): PendingDialog<CustomTextAlertDialog>

    fun createNoTitleCustomAlertDialog(
        activity: Activity,
        message: String,
        positive: DialogButton? = null,
        negative: DialogButton? = null,
    ): PendingDialog<NoTitleCustomAlertDialog>

    fun createProgressDialog(
        activity: Activity,
        message: String,
        onCancel: View.OnClickListener? = null,
    ): ProgressDialogHandle

    fun createCustomViewDialog(
        activity: Activity,
        title: String,
        contentView: View,
        positive: DialogButton,
        negative: DialogButton,
    ): PendingDialog<CustomViewDialog>
}

internal object CommonUIHooker : DexKitHooker<CommonUiApi>() {
    private class CustomMembers(
        val constructor: HostConstructor<CustomTextAlertDialog.Builder>,
        val context: HostMethod<CustomTextAlertDialog.Builder>,
        val title: HostMethod<CustomTextAlertDialog.Builder>,
        val message: HostMethod<CustomTextAlertDialog.Builder>,
        val positive: HostMethod<CustomTextAlertDialog.Builder>,
        val negative: HostMethod<CustomTextAlertDialog.Builder>,
        val build: HostMethod<CustomTextAlertDialog>,
    )

    private class NoTitleMembers(
        val constructor: HostConstructor<NoTitleCustomAlertDialog.Builder>,
        val context: HostMethod<NoTitleCustomAlertDialog.Builder>,
        val message: HostMethod<NoTitleCustomAlertDialog.Builder>,
        val positive: HostMethod<NoTitleCustomAlertDialog.Builder>,
        val negative: HostMethod<NoTitleCustomAlertDialog.Builder>,
        val build: HostMethod<NoTitleCustomAlertDialog>,
    )

    private class ProgressMembers(
        val constructor: HostConstructor<CustomProgressDialog.Builder>,
        val desc: HostMethod<CustomProgressDialog.Builder>,
        val cancelListeners: List<HostMethod<CustomProgressDialog.Builder>>,
        val progress: HostMethod<CustomProgressDialog.Builder>,
        val build: HostMethod<CustomProgressDialog>,
    )

    private class CustomViewMembers(
        val constructor: HostConstructor<CustomViewDialog.Builder>,
        val title: HostMethod<CustomViewDialog.Builder>,
        val contentView: HostMethod<CustomViewDialog.Builder>,
        val positive: HostMethod<CustomViewDialog.Builder>,
        val negative: HostMethod<CustomViewDialog.Builder>,
        val build: HostMethod<CustomViewDialog>,
    )

    private val log = HLog.of<CommonUIHooker>()

    @Volatile
    private var customMembers: CustomMembers? = null

    @Volatile
    private var noTitleMembers: NoTitleMembers? = null

    @Volatile
    private var progressMembers: ProgressMembers? = null

    @Volatile
    private var customViewMembers: CustomViewMembers? = null

    override val providedApi = object : CommonUiApi {
        override fun createCustomTextAlertDialog(
            activity: Activity,
            title: String,
            message: String,
            positive: DialogButton?,
            negative: DialogButton?,
        ) = PendingDialog(activity) {
            val members = customMembers ?: return@PendingDialog null
            if (!activity.isDialogHostReady()) return@PendingDialog null
            val builder = members.constructor.createOrNull(activity) ?: return@PendingDialog null
            members.context.invokeOrNull(builder, activity) ?: return@PendingDialog null
            members.title.invokeOrNull(builder, title) ?: return@PendingDialog null
            members.message.invokeOrNull(builder, message) ?: return@PendingDialog null
            positive?.let {
                members.positive.invokeOrNull(builder, it.text, it.listener)
                    ?: return@PendingDialog null
            }
            negative?.let {
                members.negative.invokeOrNull(builder, it.text, it.listener)
                    ?: return@PendingDialog null
            }
            members.build.invokeOrNull(builder)
        }

        override fun createNoTitleCustomAlertDialog(
            activity: Activity,
            message: String,
            positive: DialogButton?,
            negative: DialogButton?,
        ) = PendingDialog(activity) {
            val members = noTitleMembers ?: return@PendingDialog null
            if (!activity.isDialogHostReady()) return@PendingDialog null
            val builder = members.constructor.createOrNull(activity) ?: return@PendingDialog null
            members.context.invokeOrNull(builder, activity) ?: return@PendingDialog null
            members.message.invokeOrNull(builder, message) ?: return@PendingDialog null
            positive?.let {
                members.positive.invokeOrNull(builder, it.text, it.listener)
                    ?: return@PendingDialog null
            }
            negative?.let {
                members.negative.invokeOrNull(builder, it.text, it.listener)
                    ?: return@PendingDialog null
            }
            members.build.invokeOrNull(builder)
        }

        override fun createProgressDialog(
            activity: Activity,
            message: String,
            onCancel: View.OnClickListener?,
        ) = ProgressDialogHandle(activity) session@{
            val members = progressMembers ?: return@session null
            if (!activity.isDialogHostReady()) return@session null
            val builder = members.constructor.createOrNull(activity) ?: return@session null
            members.desc.invokeOrNull(builder, message) ?: return@session null
            onCancel?.let { listener ->
                members.cancelListeners.forEach { method ->
                    method.invokeOrNull(builder, listener) ?: return@session null
                }
            }
            val dialog = members.build.invokeOrNull(builder) ?: return@session null
            ProgressDialogHandle.Session(dialog, builder, members.progress, members.desc)
        }

        override fun createCustomViewDialog(
            activity: Activity,
            title: String,
            contentView: View,
            positive: DialogButton,
            negative: DialogButton,
        ) = PendingDialog(activity) {
            val members = customViewMembers ?: return@PendingDialog null
            if (!activity.isDialogHostReady()) return@PendingDialog null
            val builder = members.constructor.createOrNull(activity) ?: return@PendingDialog null
            members.title.invokeOrNull(builder, title) ?: return@PendingDialog null
            members.contentView.invokeOrNull(builder, contentView) ?: return@PendingDialog null
            members.positive.invokeOrNull(builder, positive.text, positive.listener)
                ?: return@PendingDialog null
            members.negative.invokeOrNull(builder, negative.text, negative.listener)
                ?: return@PendingDialog null
            members.build.invokeOrNull(builder)
        }
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        customMembers = optionally("CustomTextAlertDialog") {
            val builder = classOf<CustomTextAlertDialog.Builder>()
            val constructor = builder.requireConstructor { parameters(classOf<Context>()) }
            val title = bridge.requireMethod<CustomTextAlertDialog.Builder>(
                label = "CustomTextAlertDialog.Builder#title",
                pick = {
                    singleOrNull { it.isPublic && it.usingStrings.any { s -> "setTitle" in s } }
                },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>())
            }
            val message = bridge.requireMethod<CustomTextAlertDialog.Builder>(
                label = "CustomTextAlertDialog.Builder#message",
                pick = { singleOrNull { it.isPublic && it.name != title.name } },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>())
            }
            val positiveFields = bridge.positiveButtonFields(builder)
                .orFail { "CustomTextAlertDialog.Builder#positiveFields" }
            val positive = bridge.requireMethod<CustomTextAlertDialog.Builder>(
                label = "CustomTextAlertDialog.Builder#positive",
                pick = { pickButton(positiveFields) },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
            }
            val negative = bridge.requireMethod<CustomTextAlertDialog.Builder>(
                label = "CustomTextAlertDialog.Builder#negative",
                pick = { singleOrNull { it.isPublic && it.name != positive.name } },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
            }
            val context = bridge.requireMethod<CustomTextAlertDialog.Builder>(
                label = "CustomTextAlertDialog.Builder#context",
            ) {
                declaredClass(builder)
                paramTypes(classOf<Context>())
            }
            val build = bridge.requireMethod<CustomTextAlertDialog>(
                label = "CustomTextAlertDialog.Builder#build",
            ) {
                declaredClass(builder)
                paramTypes()
            }
            CustomMembers(constructor, context, title, message, positive, negative, build)
        }

        noTitleMembers = optionally("NoTitleCustomAlertDialog") {
            val builder = classOf<NoTitleCustomAlertDialog.Builder>()
            val constructor = builder.requireConstructor { parameters(classOf<Context>()) }
            val message = bridge.requireMethod<NoTitleCustomAlertDialog.Builder>(
                label = "NoTitleCustomAlertDialog.Builder#message",
                pick = { singleOrNull { it.isPublic && it.usingFieldCount > 0 } },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>())
            }
            val positiveFields = bridge.positiveButtonFields(builder)
                .orFail { "NoTitleCustomAlertDialog.Builder#positiveFields" }
            val positive = bridge.requireMethod<NoTitleCustomAlertDialog.Builder>(
                label = "NoTitleCustomAlertDialog.Builder#positive",
                pick = { pickButton(positiveFields) },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
            }
            val negative = bridge.requireMethod<NoTitleCustomAlertDialog.Builder>(
                label = "NoTitleCustomAlertDialog.Builder#negative",
                pick = { singleOrNull { it.isPublic && it.name != positive.name } },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
            }
            val context = bridge.requireMethod<NoTitleCustomAlertDialog.Builder>(
                label = "NoTitleCustomAlertDialog.Builder#context",
            ) {
                declaredClass(builder)
                paramTypes(classOf<Context>())
            }
            val build = bridge.requireMethod<NoTitleCustomAlertDialog>(
                label = "NoTitleCustomAlertDialog.Builder#build",
            ) {
                declaredClass(builder)
                paramTypes()
            }
            NoTitleMembers(constructor, context, message, positive, negative, build)
        }

        progressMembers = optionally("CustomProgressDialog") {
            val builder = classOf<CustomProgressDialog.Builder>()
            val constructor = builder.requireConstructor { parameters(classOf<Context>()) }
            val desc = bridge.requireMethod<CustomProgressDialog.Builder>(
                label = "CustomProgressDialog.Builder#desc",
                pick = { singleOrNull { it.isPublic && it.usingFieldCount >= 2 } },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>())
            }
            val cancelListeners = bridge.findMethod {
                matcher {
                    declaredClass(builder)
                    returnType(builder)
                    paramTypes(classOf<View.OnClickListener>())
                }
            }.filter { it.isPublic }.mapNotNull {
                bridge.method<CustomProgressDialog.Builder>(
                    "CustomProgressDialog.Builder#cancel",
                    it,
                )
            }.requireNotEmpty("CustomProgressDialog.Builder#cancel")
            val progress = bridge.requireMethod<CustomProgressDialog.Builder>(
                label = "CustomProgressDialog.Builder#progress",
                pick = { singleOrNull(HostMethodData::isPublic) },
            ) {
                declaredClass(builder)
                paramTypes(classOf<Int>())
                addInvoke { name = "setProgress" }
            }
            val build = bridge.requireMethod<CustomProgressDialog>(
                label = "CustomProgressDialog.Builder#build",
            ) {
                declaredClass(builder)
                paramTypes()
            }
            ProgressMembers(constructor, desc, cancelListeners, progress, build)
        }

        customViewMembers = optionally("CustomViewDialog") {
            val builder = classOf<CustomViewDialog.Builder>()
            val constructor = builder.requireConstructor { parameters(classOf<Context>()) }
            val title = bridge.requireMethod<CustomViewDialog.Builder>(
                label = "CustomViewDialog.Builder#title",
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>())
            }
            val contentView = bridge.requireMethod<CustomViewDialog.Builder>(
                label = "CustomViewDialog.Builder#contentView",
            ) {
                declaredClass(builder)
                paramTypes(classOf<View>())
            }
            val positive = bridge.requireMethod<CustomViewDialog.Builder>(
                label = "CustomViewDialog.Builder#positive",
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
                usingStrings = listOf("setPositiveButton called")
            }
            val negative = bridge.requireMethod<CustomViewDialog.Builder>(
                label = "CustomViewDialog.Builder#negative",
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
                usingStrings = listOf("setNegativeButton called")
            }
            val build = bridge.requireMethod<CustomViewDialog>(
                label = "CustomViewDialog.Builder#build",
            ) {
                declaredClass(builder)
                paramTypes()
            }
            CustomViewMembers(constructor, title, contentView, positive, negative, build)
        }

        log.debug {
            "Ready, custom=${customMembers != null}, noTitle=${noTitleMembers != null}, " +
                    "progress=${progressMembers != null}, " +
                    "customView=${customViewMembers != null}"
        }
    }

    private fun Activity.isDialogHostReady() = !isFinishing && !isDestroyed

    private fun List<HostMethodData>.pickButton(anchors: Set<HostFieldData>) =
        filter { it.isPublic }
            .groupBy { it.usedFields.count(anchors::contains) }
            .filterKeys { it > 0 }
            .maxByOrNull { it.key }
            ?.value
            ?.singleOrNull()

    private fun HostBridge.positiveButtonFields(builder: Class<*>): Set<HostFieldData>? {
        fun of(vararg params: Class<*>) = findMethod {
            matcher {
                declaredClass(builder)
                returnType(builder)
                paramTypes(*params)
            }
        }.filter { it.isPublic }

        of(classOf<String>(), classOf<Int>(), classOf<View.OnClickListener>())
            .singleOrNull()?.usedFields?.takeIf { it.isNotEmpty() }?.let { return it }

        val coloured = of(classOf<Int>(), classOf<Int>(), classOf<View.OnClickListener>())
            .singleOrNull() ?: return null.also {
            log.warn { "No positive-button field anchor for ${builder.name}" }
        }
        val delegated = coloured.invokes.filter {
            it.className == builder.name &&
                    it.takes(classOf<Int>(), classOf<View.OnClickListener>())
        }
        return buildSet {
            addAll(coloured.usedFields)
            delegated.forEach { addAll(it.usedFields) }
        }.takeIf { it.isNotEmpty() }
    }
}
