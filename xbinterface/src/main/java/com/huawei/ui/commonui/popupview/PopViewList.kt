@file:Suppress("unused")

package com.huawei.ui.commonui.popupview

import android.content.Context
import android.view.View

class PopViewList private constructor(context: Context, view: View, items: ArrayList<String>) {
    interface PopViewClickListener {
        fun setOnClick(position: Int)
    }
}
