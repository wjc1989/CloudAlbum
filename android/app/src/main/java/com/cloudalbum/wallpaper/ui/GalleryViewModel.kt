package com.cloudalbum.wallpaper.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudalbum.wallpaper.R
import com.cloudalbum.wallpaper.data.ApiException
import com.cloudalbum.wallpaper.data.ImageApi
import com.cloudalbum.wallpaper.data.ImageInfo
import com.cloudalbum.wallpaper.data.WallpaperActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** 一次性提示事件 */
sealed interface UiEvent {
    data class Toast(val text: String) : UiEvent
    data class Dialog(val title: String, val content: String) : UiEvent
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    val list = mutableStateListOf<ImageInfo>()
    var loading by mutableStateOf(false)
        private set

    /** 设置壁纸 / 保存相册时的进度文字，null 表示不显示 */
    var busyText by mutableStateOf<String?>(null)
        private set

    private var totalPage = 0
    private var pageNum = 1
    private val pageSize = 21
    private var loadJob: Job? = null

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        init()
    }

    /** 刷新：清空列表从第一页开始 */
    fun init() {
        loadJob?.cancel()
        list.clear()
        pageNum = 1
        totalPage = 0
        loadData()
    }

    /** 滚动到底部 */
    fun onReachBottom() {
        if (loading) return
        if (totalPage > pageNum) {
            pageNum += 1
            loadData()
        }
    }

    private fun loadData() {
        loading = true
        loadJob = viewModelScope.launch {
            try {
                val res = ImageApi.getImageList(pageNum, pageSize)
                list.addAll(res.rows)
                totalPage = ceil(res.total / pageSize.toDouble()).toInt()
            } catch (e: ApiException) {
                if (e.silent) {
                    if (!e.message.isNullOrEmpty()) _events.send(UiEvent.Toast(e.message!!))
                } else {
                    _events.send(UiEvent.Dialog(str(R.string.error), e.message.orEmpty()))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(UiEvent.Dialog(str(R.string.error), e.message ?: e.toString()))
            } finally {
                loading = false
            }
        }
    }

    fun setLockScreen(index: Int) = runAction(index, R.string.setting, R.string.lock_screen_set) { url ->
        WallpaperActions.setLockScreen(getApplication(), url)
    }

    fun saveToAlbum(index: Int) = runAction(index, R.string.saving, R.string.saved_to_photos) { url ->
        WallpaperActions.saveToAlbum(getApplication(), url)
    }

    private fun runAction(index: Int, busyRes: Int, successRes: Int, block: suspend (String) -> Unit) {
        val item = list.getOrNull(index) ?: return
        if (busyText != null) return
        busyText = str(busyRes)
        viewModelScope.launch {
            try {
                block(item.url)
                _events.send(UiEvent.Toast(str(successRes)))
            } catch (e: Exception) {
                _events.send(UiEvent.Dialog(str(R.string.failed), e.message ?: e.toString()))
            } finally {
                busyText = null
            }
        }
    }

    private fun str(id: Int) = getApplication<Application>().getString(id)
}
