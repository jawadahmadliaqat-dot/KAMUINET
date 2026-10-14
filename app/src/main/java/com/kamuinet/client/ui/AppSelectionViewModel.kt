package com.kamuinet.client.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kamuinet.client.data.AppConfigRepository
import com.kamuinet.client.data.AppInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AppSelectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appConfigRepository: AppConfigRepository
) : ViewModel() {

    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadInstalledApps()
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoading.value = true
            val allowedApps = appConfigRepository.getAllowedApps()
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                installedApps
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
                    .map { appInfo ->
                        AppInfo(
                            name = pm.getApplicationLabel(appInfo).toString(),
                            packageName = appInfo.packageName,
                            icon = pm.getApplicationIcon(appInfo),
                            isSelected = allowedApps.contains(appInfo.packageName)
                        )
                    }
                    .sortedBy { it.name }
            }
            _appList.value = apps
            _isLoading.value = false
        }
    }

    fun toggleAppSelection(packageName: String) {
        val currentList = _appList.value.toMutableList()
        val index = currentList.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            val app = currentList[index]
            val newIsSelected = !app.isSelected
            currentList[index] = app.copy(isSelected = newIsSelected)
            _appList.value = currentList
            
            if (newIsSelected) {
                appConfigRepository.addApp(packageName)
            } else {
                appConfigRepository.removeApp(packageName)
            }
        }
    }
}
