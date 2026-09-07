package moe.evil.hwhh.xposed.utils

import android.app.Activity
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.huawei.ui.commonui.checkbox.HealthCheckBox
import com.huawei.ui.commonui.datepicker.HealthDatePickerDialog
import moe.evil.hwhh.shared.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.shared.log.HLog
import java.util.Calendar
import java.util.GregorianCalendar

private const val ROW_ARROW_DRAWABLE = "ic_health_list_arrow_gray"
private val rowLog = HLog("DialogRows")

fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

fun matchWidth() = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
)

fun Activity.hostTextColor() = HealthCheckBox(this).currentTextColor

fun Int.muted() = (this and 0x00FFFFFF) or (0x99 shl 24)

fun Activity.dialogContent(vararg rows: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = matchWidth()
    val hPad = dp(8)
    setPadding(hPad, 0, hPad, 0)
    rows.forEach(::addView)
}

fun dateSelected(onSelected: (Int, Int, Int) -> Unit) =
    object : HealthDatePickerDialog.DateSelectedListener {
        override fun onDateSelected(year: Int, month: Int, dayOfMonth: Int) =
            onSelected(year, month, dayOfMonth)
    }

private fun GregorianCalendar.dayText() = "%04d-%02d-%02d".format(
    get(Calendar.YEAR),
    get(Calendar.MONTH) + 1,
    get(Calendar.DAY_OF_MONTH),
)

private fun GregorianCalendar.bound(hour: Int, minute: Int, second: Int, milli: Int) =
    (clone() as GregorianCalendar).apply {
        set(
            get(Calendar.YEAR),
            get(Calendar.MONTH),
            get(Calendar.DAY_OF_MONTH),
            hour,
            minute,
            second,
        )
        set(Calendar.MILLISECOND, milli)
    }.timeInMillis


fun Activity.summaryRow(
    title: String,
    textColor: Int = hostTextColor(),
    mutedColor: Int = textColor.muted(),
): Pair<LinearLayout, TextView> {
    val value = TextView(this).apply {
        setTextColor(mutedColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }
    val ripple = TypedValue().also {
        theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        setBackgroundResource(ripple.resourceId)
        layoutParams = matchWidth()
        val vPad = dp(16)
        setPadding(0, vPad, 0, vPad)
        addView(
            TextView(this@summaryRow).apply {
                text = title
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        addView(value)
        val arrowRes = resources.getIdentifier(ROW_ARROW_DRAWABLE, "drawable", HOOK_TARGET_PACKAGE)
        if (arrowRes == 0) {
            rowLog.debug { "Summary row drawable not found: $ROW_ARROW_DRAWABLE" }
        } else {
            addView(
                ImageView(this@summaryRow).apply {
                    setImageResource(arrowRes)
                    imageTintList = ColorStateList.valueOf(mutedColor)
                    val size = dp(16)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginStart = dp(6)
                    }
                },
            )
        }
    }
    return row to value
}

class DateRangeRow(
    activity: Activity,
    title: String,
    initialStart: GregorianCalendar.() -> Unit,
) {
    private val startCal = GregorianCalendar().apply(initialStart)
    private val endCal = GregorianCalendar()

    val view = activity.summaryRow(title).let { (row, value) ->
        value.text = label()
        row.setOnClickListener {
            HealthDatePickerDialog(
                activity,
                dateSelected { year, month, day ->
                    startCal.set(year, month, day)
                    HealthDatePickerDialog(
                        activity,
                        dateSelected { endYear, endMonth, endDay ->
                            endCal.set(endYear, endMonth, endDay)
                            value.text = label()
                        },
                        endCal,
                    ).show()
                },
                startCal,
            ).show()
        }
        row
    }

    val range get() = startCal.bound(0, 0, 0, 0)..endCal.bound(23, 59, 59, 999)

    private fun label() = "${startCal.dayText()} ~ ${endCal.dayText()}"
}