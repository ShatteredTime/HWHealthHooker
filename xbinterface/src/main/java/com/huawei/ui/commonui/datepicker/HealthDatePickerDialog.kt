@file:Suppress("unused")

package com.huawei.ui.commonui.datepicker

import android.app.Activity
import android.app.Dialog
import java.util.GregorianCalendar

class HealthDatePickerDialog(
    activity: Activity,
    listener: DateSelectedListener,
    calendar: GregorianCalendar,
) : Dialog(activity) {
    interface DateSelectedListener {
        fun onDateSelected(year: Int, month: Int, dayOfMonth: Int)
    }
}
