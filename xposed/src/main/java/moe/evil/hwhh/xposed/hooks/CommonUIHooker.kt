package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.View
import com.highcapable.kavaref.extension.classOf
import com.huawei.ui.commonui.dialog.CustomProgressDialog
import com.huawei.ui.commonui.dialog.CustomTextAlertDialog
import com.huawei.ui.commonui.dialog.CustomViewDialog
import com.huawei.ui.commonui.dialog.NoTitleCustomAlertDialog
import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.kdxref.HostConstructor
import moe.evil.hwhh.kdxref.HostFieldData
import moe.evil.hwhh.kdxref.HostMethod
import moe.evil.hwhh.kdxref.HostMethodData
import moe.evil.hwhh.kdxref.firstConstructorOrNullLogged
import moe.evil.hwhh.kdxref.hostMethod
import moe.evil.hwhh.kdxref.hostMethodOf
import moe.evil.hwhh.kdxref.orWarnEmpty
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.R
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HookApi
import moe.evil.hwhh.xposed.utils.moduleString
import moe.evil.hwhh.xposed.utils.toast

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
        session?.run { progressMethod.on(builder).invokeQuietly(percent) }
    }

    fun setMessage(text: String) = activity.runOnUiThread {
        session?.run { descMethod.on(builder).invokeQuietly(text) }
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
            val builder = members.constructor.createLogged(activity) ?: return@PendingDialog null
            members.context.on(builder).invokeQuietly(activity) ?: return@PendingDialog null
            members.title.on(builder).invokeQuietly(title) ?: return@PendingDialog null
            members.message.on(builder).invokeQuietly(message) ?: return@PendingDialog null
            positive?.let {
                members.positive.on(builder).invokeQuietly(it.text, it.listener)
                    ?: return@PendingDialog null
            }
            negative?.let {
                members.negative.on(builder).invokeQuietly(it.text, it.listener)
                    ?: return@PendingDialog null
            }
            members.build.on(builder).invokeQuietly()
        }

        override fun createNoTitleCustomAlertDialog(
            activity: Activity,
            message: String,
            positive: DialogButton?,
            negative: DialogButton?,
        ) = PendingDialog(activity) {
            val members = noTitleMembers ?: return@PendingDialog null
            if (!activity.isDialogHostReady()) return@PendingDialog null
            val builder = members.constructor.createLogged(activity) ?: return@PendingDialog null
            members.context.on(builder).invokeQuietly(activity) ?: return@PendingDialog null
            members.message.on(builder).invokeQuietly(message) ?: return@PendingDialog null
            positive?.let {
                members.positive.on(builder).invokeQuietly(it.text, it.listener)
                    ?: return@PendingDialog null
            }
            negative?.let {
                members.negative.on(builder).invokeQuietly(it.text, it.listener)
                    ?: return@PendingDialog null
            }
            members.build.on(builder).invokeQuietly()
        }

        override fun createProgressDialog(
            activity: Activity,
            message: String,
            onCancel: View.OnClickListener?,
        ) = ProgressDialogHandle(activity) session@{
            val members = progressMembers ?: return@session null
            if (!activity.isDialogHostReady()) return@session null
            val builder = members.constructor.createLogged(activity) ?: return@session null
            members.desc.on(builder).invokeQuietly(message) ?: return@session null
            onCancel?.let { listener ->
                members.cancelListeners.forEach { method ->
                    method.on(builder).invokeQuietly(listener) ?: return@session null
                }
            }
            val dialog = members.build.on(builder).invokeQuietly() ?: return@session null
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
            val builder = members.constructor.createLogged(activity) ?: return@PendingDialog null
            members.title.on(builder).invokeQuietly(title) ?: return@PendingDialog null
            members.contentView.on(builder).invokeQuietly(contentView) ?: return@PendingDialog null
            members.positive.on(builder).invokeQuietly(positive.text, positive.listener)
                ?: return@PendingDialog null
            members.negative.on(builder).invokeQuietly(negative.text, negative.listener)
                ?: return@PendingDialog null
            members.build.on(builder).invokeQuietly()
        }
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        customMembers = run custom@{
            val builder = classOf<CustomTextAlertDialog.Builder>()
            val constructor = builder.firstConstructorOrNullLogged {
                parameters(classOf<Context>())
            } ?: return@custom null
            val title = hostMethod<CustomTextAlertDialog.Builder>(
                label = "CustomTextAlertDialog.Builder#title",
                pick = {
                    singleOrNull { it.isPublic && it.usingStrings.any { s -> "setTitle" in s } }
                },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>())
            } ?: return@custom null
            val message = hostMethod<CustomTextAlertDialog.Builder>(
                label = "CustomTextAlertDialog.Builder#message",
                pick = { singleOrNull { it.isPublic && it.name != title.name } },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>())
            } ?: return@custom null
            val positiveFields = bridge.positiveButtonFields(builder) ?: return@custom null
            val positive = hostMethod<CustomTextAlertDialog.Builder>(
                label = "CustomTextAlertDialog.Builder#positive",
                pick = { pickButton(positiveFields) },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
            } ?: return@custom null
            val negative = hostMethod<CustomTextAlertDialog.Builder>(
                label = "CustomTextAlertDialog.Builder#negative",
                pick = { singleOrNull { it.isPublic && it.name != positive.name } },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
            } ?: return@custom null
            val context = hostMethod<CustomTextAlertDialog.Builder>(
                label = "CustomTextAlertDialog.Builder#context",
            ) {
                declaredClass(builder)
                paramTypes(classOf<Context>())
            } ?: return@custom null
            val build = hostMethod<CustomTextAlertDialog>("CustomTextAlertDialog.Builder#build") {
                declaredClass(builder)
                paramTypes()
            } ?: return@custom null
            CustomMembers(constructor, context, title, message, positive, negative, build)
        }

        noTitleMembers = run noTitle@{
            val builder = classOf<NoTitleCustomAlertDialog.Builder>()
            val constructor = builder.firstConstructorOrNullLogged {
                parameters(classOf<Context>())
            } ?: return@noTitle null
            val message = hostMethod<NoTitleCustomAlertDialog.Builder>(
                label = "NoTitleCustomAlertDialog.Builder#message",
                pick = { singleOrNull { it.isPublic && it.usingFieldCount > 0 } },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>())
            } ?: return@noTitle null
            val positiveFields = bridge.positiveButtonFields(builder) ?: return@noTitle null
            val positive = hostMethod<NoTitleCustomAlertDialog.Builder>(
                label = "NoTitleCustomAlertDialog.Builder#positive",
                pick = { pickButton(positiveFields) },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
            } ?: return@noTitle null
            val negative = hostMethod<NoTitleCustomAlertDialog.Builder>(
                label = "NoTitleCustomAlertDialog.Builder#negative",
                pick = { singleOrNull { it.isPublic && it.name != positive.name } },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
            } ?: return@noTitle null
            val context = hostMethod<NoTitleCustomAlertDialog.Builder>(
                label = "NoTitleCustomAlertDialog.Builder#context",
            ) {
                declaredClass(builder)
                paramTypes(classOf<Context>())
            } ?: return@noTitle null
            val build = hostMethod<NoTitleCustomAlertDialog>(
                label = "NoTitleCustomAlertDialog.Builder#build",
            ) {
                declaredClass(builder)
                paramTypes()
            } ?: return@noTitle null
            NoTitleMembers(constructor, context, message, positive, negative, build)
        }

        progressMembers = run progress@{
            val builder = classOf<CustomProgressDialog.Builder>()
            val constructor = builder.firstConstructorOrNullLogged {
                parameters(classOf<Context>())
            } ?: return@progress null
            val desc = hostMethod<CustomProgressDialog.Builder>(
                label = "CustomProgressDialog.Builder#desc",
                pick = { singleOrNull { it.isPublic && it.usingFieldCount >= 2 } },
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>())
            } ?: return@progress null
            val cancelListeners = bridge.findMethod {
                matcher {
                    declaredClass(builder)
                    returnType(builder)
                    paramTypes(classOf<View.OnClickListener>())
                }
            }.filter { it.isPublic }.mapNotNull {
                hostMethodOf<CustomProgressDialog.Builder>(
                    "CustomProgressDialog.Builder#cancel",
                    it,
                )
            }.orWarnEmpty("CustomProgressDialog.Builder#cancel").ifEmpty { return@progress null }
            val progress = hostMethod<CustomProgressDialog.Builder>(
                label = "CustomProgressDialog.Builder#progress",
                pick = { singleOrNull(HostMethodData::isPublic) },
            ) {
                declaredClass(builder)
                paramTypes(classOf<Int>())
                addInvoke { name = "setProgress" }
            } ?: return@progress null
            val build = hostMethod<CustomProgressDialog>("CustomProgressDialog.Builder#build") {
                declaredClass(builder)
                paramTypes()
            } ?: return@progress null
            ProgressMembers(constructor, desc, cancelListeners, progress, build)
        }

        customViewMembers = run customView@{
            val builder = classOf<CustomViewDialog.Builder>()
            val constructor = builder.firstConstructorOrNullLogged {
                parameters(classOf<Context>())
            } ?: return@customView null
            val title = hostMethod<CustomViewDialog.Builder>(
                label = "CustomViewDialog.Builder#title",
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>())
            } ?: return@customView null
            val contentView = hostMethod<CustomViewDialog.Builder>(
                label = "CustomViewDialog.Builder#contentView",
            ) {
                declaredClass(builder)
                paramTypes(classOf<View>())
            } ?: return@customView null
            val positive = hostMethod<CustomViewDialog.Builder>(
                label = "CustomViewDialog.Builder#positive",
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
                usingStrings = listOf("setPositiveButton called")
            } ?: return@customView null
            val negative = hostMethod<CustomViewDialog.Builder>(
                label = "CustomViewDialog.Builder#negative",
            ) {
                declaredClass(builder)
                paramTypes(classOf<String>(), classOf<View.OnClickListener>())
                usingStrings = listOf("setNegativeButton called")
            } ?: return@customView null
            val build = hostMethod<CustomViewDialog>("CustomViewDialog.Builder#build") {
                declaredClass(builder)
                paramTypes()
            } ?: return@customView null
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
