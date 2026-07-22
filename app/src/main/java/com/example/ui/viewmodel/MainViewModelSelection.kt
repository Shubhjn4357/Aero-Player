package com.example.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.data.database.MediaEntity
import com.example.data.model.BrowseScreenState
import com.example.data.model.SelectionState
import kotlinx.coroutines.launch

fun MainViewModel.onFolderClick(folderName: String) {
    val sel = _selectionState.value
    if (sel.isInSelectionMode) {
        toggleFolderSelection(folderName)
    } else {
        _browseScreenState.value = BrowseScreenState.FileList(folderName)
    }
}

fun MainViewModel.onBackPress() {
    if (_selectionState.value.isInSelectionMode) {
        clearSelection()
    } else if (_browseScreenState.value is BrowseScreenState.FileList) {
        _browseScreenState.value = BrowseScreenState.FolderList
    }
}

fun MainViewModel.toggleFolderSelection(folderPath: String) {
    val current = _selectionState.value
    val updated = if (current.selectedFolderPaths.contains(folderPath)) {
        current.selectedFolderPaths - folderPath
    } else {
        current.selectedFolderPaths + folderPath
    }
    val inSelection = updated.isNotEmpty() || current.selectedVideoIds.isNotEmpty()
    _selectionState.value = current.copy(
        isInSelectionMode = inSelection,
        selectedFolderPaths = updated
    )
}

fun MainViewModel.toggleVideoSelection(videoId: String) {
    val current = _selectionState.value
    val updated = if (current.selectedVideoIds.contains(videoId)) {
        current.selectedVideoIds - videoId
    } else {
        current.selectedVideoIds + videoId
    }
    val inSelection = current.selectedFolderPaths.isNotEmpty() || updated.isNotEmpty()
    _selectionState.value = current.copy(
        isInSelectionMode = inSelection,
        selectedVideoIds = updated
    )
}

fun MainViewModel.selectAllVideos(videoIds: List<String>) {
    val current = _selectionState.value
    _selectionState.value = current.copy(
        isInSelectionMode = true,
        selectedVideoIds = videoIds.toSet()
    )
}

fun MainViewModel.selectAllFoldersAndFiles(folderPaths: List<String>, videoIds: List<String>) {
    val current = _selectionState.value
    _selectionState.value = current.copy(
        isInSelectionMode = folderPaths.isNotEmpty() || videoIds.isNotEmpty(),
        selectedFolderPaths = folderPaths.toSet(),
        selectedVideoIds = videoIds.toSet()
    )
}

fun MainViewModel.clearSelection() {
    _selectionState.value = SelectionState(
        isInSelectionMode = false,
        selectedFolderPaths = emptySet(),
        selectedVideoIds = emptySet()
    )
}

fun MainViewModel.deleteSelectedItems() {
    val sel = _selectionState.value
    viewModelScope.launch {
        val list = filteredMediaList.value
        val itemsToDelete = mutableListOf<MediaEntity>()
        
        val videosToDelete = list.filter { sel.selectedVideoIds.contains(it.uriString) }
        itemsToDelete.addAll(videosToDelete)
        
        if (sel.selectedFolderPaths.isNotEmpty()) {
            val foldersToDelete = list.filter { item ->
                val parentName = java.io.File(item.path).parentFile?.name ?: "Root Folder"
                sel.selectedFolderPaths.contains(parentName)
            }
            itemsToDelete.addAll(foldersToDelete)
        }
        
        if (itemsToDelete.isNotEmpty()) {
            deleteMediaBatch(itemsToDelete)
        }
        
        clearSelection()
    }
}
