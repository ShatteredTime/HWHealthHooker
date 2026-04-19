@file:Suppress("unused")

package com.huawei.ui.commonui.titlebar

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.RelativeLayout

class CustomTitleBar : RelativeLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    fun setRightThirdKeyBackground(drawable: Drawable?, description: CharSequence): Unit = stub()
    fun setRightThirdKeyVisibility(visibility: Int): Unit = stub()
    fun setRightThirdKeyOnClickListener(listener: OnClickListener): Unit = stub()
}

private fun stub(): Nothing = throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
