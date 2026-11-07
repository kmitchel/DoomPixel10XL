package com.id.doom

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

class DoomSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "DoomSurfaceView"
    }

    init {
        holder.addCallback(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        val parentH = MeasureSpec.getSize(heightMeasureSpec)
        if (parentW <= 0 || parentH <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // Maintain authentic 4:3 aspect ratio (pillarboxed horizontally on widescreen displays)
        var targetW = (parentH * 4) / 3
        var targetH = parentH
        if (targetW > parentW) {
            targetW = parentW
            targetH = (parentW * 3) / 4
        }

        setMeasuredDimension(targetW, targetH)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceCreated: Attaching native 4:3 surface")
        DoomBridge.nativeSetSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surfaceChanged: format=$format, size=${width}x${height} (4:3)")
        DoomBridge.nativeSetSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceDestroyed: Releasing native surface")
        DoomBridge.nativeSurfaceDestroyed()
    }
}
