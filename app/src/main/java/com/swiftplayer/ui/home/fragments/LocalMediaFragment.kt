package com.swiftplayer.ui.home.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.swiftplayer.MainActivity
import com.swiftplayer.R
import com.swiftplayer.data.local.FolderBrowser
import com.swiftplayer.data.local.BrowseItem
import com.swiftplayer.data.local.FolderItem
import com.swiftplayer.databinding.FragmentLocalMediaBinding
import com.swiftplayer.ui.components.FolderBrowseAdapter
import com.swiftplayer.ui.components.FolderShortcutAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Stack

class LocalMediaFragment : Fragment() {

    private var _binding: FragmentLocalMediaBinding? = null
    private val binding get() = _binding!!
    private lateinit var folderBrowser: FolderBrowser
    private lateinit var browseAdapter: FolderBrowseAdapter
    private lateinit var shortcutAdapter: FolderShortcutAdapter

    private val pathStack = Stack<File>()
    private var currentPath: File? = null
    private var isShowingShortcuts = true
    private var showHiddenFiles = true

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
    }

    companion object {
        private const val STATE_PATH_STACK = "path_stack"
        private const val STATE_CURRENT_PATH = "current_path"
        private const val STATE_SHOWING_SHORTCUTS = "showing_shortcuts"

        /** 主界面菜单「显示隐藏文件」开关的 SharedPreferences 约定（跨文件一致） */
        const val PREFS_SETTINGS = "app_settings"
        const val KEY_SHOW_HIDDEN = "show_hidden_files"
        const val KEY_ALL_FILES_GUIDED = "all_files_guided"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocalMediaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        folderBrowser = FolderBrowser(requireContext())

        setupRecyclerViews()
        setupSwipeRefresh()

        showHiddenFiles = prefs.getBoolean(KEY_SHOW_HIDDEN, true)

        if (savedInstanceState != null) {
            isShowingShortcuts = savedInstanceState.getBoolean(STATE_SHOWING_SHORTCUTS, true)
            val savedPath = savedInstanceState.getString(STATE_CURRENT_PATH)
            val savedStack = savedInstanceState.getStringArray(STATE_PATH_STACK)

            if (savedStack != null) {
                for (p in savedStack) pathStack.push(File(p))
            }

            if (!isShowingShortcuts && savedPath != null) {
                navigateTo(File(savedPath), saveToStack = false)
            } else {
                showShortcuts()
            }
        } else {
            showShortcuts()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOWING_SHORTCUTS, isShowingShortcuts)
        currentPath?.let { outState.putString(STATE_CURRENT_PATH, it.absolutePath) }
        outState.putStringArray(STATE_PATH_STACK, pathStack.map { it.absolutePath }.toTypedArray())
    }

    private fun setupRecyclerViews() {
        browseAdapter = FolderBrowseAdapter(
            onFolderClick = { folder -> navigateTo(folder.file) },
            onVideoClick = { videoItem ->
                (activity as? MainActivity)?.openPlayer(videoItem.uri, videoItem.title, videoItem.size)
            }
        )

        shortcutAdapter = FolderShortcutAdapter { folderItem ->
            navigateTo(folderItem.file)
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = shortcutAdapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            if (isShowingShortcuts) showShortcuts()
            else currentPath?.let { browseFolder(it) }
        }
    }

    private fun showShortcuts() {
        isShowingShortcuts = true
        binding.recyclerView.adapter = shortcutAdapter
        binding.pathBarContainer.visibility = View.GONE
        binding.loadingBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE

        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) {
                folderBrowser.getRootFolders(showHiddenFiles)
            }
            if (!isAdded || _binding == null) return@launch

            shortcutAdapter.submitList(folders)
            binding.loadingBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            binding.emptyView.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun navigateTo(dir: File, saveToStack: Boolean = true) {
        if (!dir.canRead()) {
            Toast.makeText(context, "无法访问该文件夹", Toast.LENGTH_SHORT).show()
            return
        }

        if (saveToStack) currentPath?.let { pathStack.push(it) }
        currentPath = dir
        isShowingShortcuts = false

        binding.recyclerView.adapter = browseAdapter
        binding.pathBarContainer.visibility = View.VISIBLE
        binding.pathBar.text = dir.absolutePath

        browseFolder(dir)
    }

    private fun browseFolder(dir: File) {
        binding.loadingBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    folderBrowser.browseFolder(dir, showHiddenFiles)
                }
                if (!isAdded || _binding == null) return@launch

                browseAdapter.submitList(result.items)
                binding.loadingBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                binding.emptyView.visibility = if (result.items.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                if (!isAdded || _binding == null) return@launch
                binding.loadingBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                binding.emptyView.visibility = View.VISIBLE
                Toast.makeText(context, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 从指定目录开始浏览（「打开文件」入口使用，D6：起始目录为内部存储根）
     * 会清空返回栈，直接进入该目录。
     */
    fun openBrowserAt(dir: File) {
        if (!isAdded || _binding == null) return
        pathStack.clear()
        currentPath = null
        navigateTo(dir, saveToStack = false)
    }

    fun goBack(): Boolean {
        if (isShowingShortcuts) return false

        if (pathStack.isNotEmpty()) {
            val parent = pathStack.pop()
            currentPath = parent
            binding.pathBar.text = parent.absolutePath
            browseFolder(parent)
            return true
        } else {
            showShortcuts()
            return true
        }
    }

    fun refresh() {
        if (!isAdded || _binding == null) return
        showHiddenFiles = prefs.getBoolean(KEY_SHOW_HIDDEN, true)
        if (isShowingShortcuts) showShortcuts()
        else currentPath?.let { browseFolder(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
