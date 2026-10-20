package com.esct.album

import android.graphics.Bitmap
import android.graphics.Color
import android.service.dreams.DreamService
import android.widget.ImageView
import com.esct.album.data.WallpaperActions

/** 待机界面（系统屏保）：全屏显示最近一次设置到锁屏的图片 */
class StandbyDreamService : DreamService() {

    private var bitmap: Bitmap? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        bitmap = WallpaperActions.loadStandbyBitmap(this)
        setContentView(ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
        })
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmap?.recycle()
        bitmap = null
    }
}
