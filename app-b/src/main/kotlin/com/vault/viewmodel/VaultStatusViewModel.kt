package com.vault.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vault.security.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 保险箱状态页 ViewModel。
 *
 * 仅展示已存储公钥指纹与导入时间, 绝不暴露任何私钥信息, 无导出入口。
 */
class VaultStatusViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        object Empty : State
        data class Loaded(val fingerprints: List<SecureStorage.StoredFingerprint>) : State
    }

    private val secureStorage = SecureStorage(application)

    private val _state = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * 从 SecureStorage 读取已存储指纹列表。
     */
    fun load() {
        viewModelScope.launch {
            val list = secureStorage.getStoredFingerprints()
            _state.value = if (list.isEmpty()) State.Empty else State.Loaded(list)
        }
    }
}
