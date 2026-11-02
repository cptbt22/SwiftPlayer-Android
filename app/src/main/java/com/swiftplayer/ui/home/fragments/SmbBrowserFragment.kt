package com.swiftplayer.ui.home.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftplayer.MainActivity
import com.swiftplayer.R
import com.swiftplayer.data.model.SmbServer
import com.swiftplayer.databinding.DialogAddServerBinding
import com.swiftplayer.databinding.FragmentSmbBrowserBinding
import com.swiftplayer.smb.LanDiscoveryService
import com.swiftplayer.smb.SmbClient
import com.swiftplayer.ui.components.SmbServerAdapter
import com.swiftplayer.ui.components.VideoAdapter
import kotlinx.coroutines.launch

/**
 * SMB 浏览页（v2.5.0 重构）。
 *
 * - 连接：connect（TCP+认证，错误分类提示）→ resolveShare（共享名验证/候选探测）→ 进目录。
 * - 目录导航：子目录作为条目返回，可逐级进入；返回键逐级回退（goBack）。
 * - 路径条：浏览时显示当前 share//path。
 */
class SmbBrowserFragment : Fragment() {

    private var _binding: FragmentSmbBrowserBinding? = null
    private val binding get() = _binding!!
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var serverAdapter: SmbServerAdapter
    private val smbClient = SmbClient()
    private val discoveryService by lazy { LanDiscoveryService(requireContext()) }
    private var currentServer: SmbServer? = null
    private var currentPath = ""
    private var isBrowsing = false
    private var discoveredServers = mutableListOf<SmbServer>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSmbBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()
        setupFab()
        setupSwipeRefresh()
        startDiscovery()
    }

    private fun setupRecyclerViews() {
        videoAdapter = VideoAdapter { videoItem ->
            if (videoItem.isDirectory) {
                // 进入子目录
                val server = currentServer ?: return@VideoAdapter
                browseShare(server, videoItem.path)
            } else {
                (activity as? MainActivity)?.openPlayer(
                    videoItem.uri,
                    videoItem.title,
                    videoItem.size,
                    com.swiftplayer.ui.player.PlayerActivity.SOURCE_SMB,
                    smbServer = currentServer
                )
            }
        }

        serverAdapter = SmbServerAdapter(
            onServerClick = { server -> connectToServer(server) },
            onAddClick = { showAddServerDialog() }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = serverAdapter
        }
    }

    private fun setupFab() {
        binding.fabAddServer.setOnClickListener {
            showAddServerDialog()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            if (isBrowsing) {
                currentServer?.let { server ->
                    browseShare(server, currentPath)
                }
            } else {
                startDiscovery()
            }
        }
    }

    private fun hasLanPermissions(): Boolean {
        val ctx = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun startDiscovery() {
        if (!hasLanPermissions()) {
            showServerList()
            return
        }

        binding.loadingBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE

        lifecycleScope.launch {
            discoveryService.discoverServers(object : LanDiscoveryService.DiscoveryCallback {
                override fun onServerFound(server: SmbServer) {
                    if (!isAdded || _binding == null) return
                    if (discoveredServers.none { it.address == server.address }) {
                        discoveredServers.add(server)
                        if (!isBrowsing) {
                            showServerList()
                        }
                    }
                }

                override fun onDiscoveryFinished(servers: List<SmbServer>) {
                    if (!isAdded || _binding == null) return
                    discoveredServers.clear()
                    discoveredServers.addAll(servers)
                    binding.loadingBar.visibility = View.GONE
                    if (!isBrowsing) {
                        showServerList()
                    }
                    if (servers.isEmpty()) {
                        binding.emptyView.visibility = View.VISIBLE
                    }
                }

                override fun onDiscoveryError(message: String) {
                    if (!isAdded || _binding == null) return
                    binding.loadingBar.visibility = View.GONE
                    showServerList()
                }
            })
        }
    }

    private fun showServerList() {
        isBrowsing = false
        binding.smbPathBar.visibility = View.GONE
        binding.recyclerView.setPadding(
            binding.recyclerView.paddingLeft, 8,
            binding.recyclerView.paddingRight, binding.recyclerView.paddingBottom
        )
        binding.recyclerView.adapter = serverAdapter
        serverAdapter.submitList(discoveredServers.toList())
        binding.loadingBar.visibility = View.GONE
        binding.emptyView.visibility = if (discoveredServers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddServerDialog() {
        val dialogBinding = DialogAddServerBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_server)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.connect) { _, _ ->
                val name = dialogBinding.etName.text.toString().trim()
                val address = dialogBinding.etAddress.text.toString().trim()
                    .removePrefix("smb://").removeSuffix("/")
                val share = dialogBinding.etShare.text.toString().trim().trim('/')
                val username = dialogBinding.etUsername.text.toString().trim()
                val password = dialogBinding.etPassword.text.toString().trim()

                if (address.isNotEmpty()) {
                    val server = SmbServer(
                        name = name.ifEmpty { address },
                        address = address,
                        shareName = share,
                        username = username,
                        password = password
                    )
                    connectToServer(server)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 连接链路（v2.5.0）：TCP+认证 → 共享验证/自动识别 → 进入目录。
     * 每一步失败都给出中文归因，不再只显示「连接失败」。
     */
    private fun connectToServer(server: SmbServer) {
        lifecycleScope.launch {
            try {
                if (!isAdded || _binding == null) return@launch
                binding.loadingBar.visibility = View.VISIBLE
                binding.emptyView.visibility = View.GONE
                Toast.makeText(context, R.string.connecting, Toast.LENGTH_SHORT).show()

                val connectResult = smbClient.connect(server)
                if (!isAdded || _binding == null) return@launch
                connectResult
                    .onFailure { e ->
                        binding.loadingBar.visibility = View.GONE
                        toast(e.message ?: getString(R.string.connection_failed))
                        return@launch
                    }

                val shareResult = smbClient.resolveShare(server)
                if (!isAdded || _binding == null) return@launch
                shareResult
                    .onSuccess { share ->
                        val connected = server.copy(shareName = share)
                        currentServer = connected
                        toast(getString(R.string.smb_connected_share, share))
                        browseShare(connected, "/")
                    }
                    .onFailure { e ->
                        binding.loadingBar.visibility = View.GONE
                        toast(e.message ?: getString(R.string.smb_no_shares_found))
                    }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                binding.loadingBar.visibility = View.GONE
                toast("连接失败: ${e.message}")
            }
        }
    }

    /** 浏览共享目录（path 为 `/` 风格；""/"/" 表示共享根） */
    private fun browseShare(server: SmbServer, path: String) {
        isBrowsing = true
        lifecycleScope.launch {
            try {
                val result = smbClient.listDirectory(server.shareName, path)
                if (!isAdded || _binding == null) return@launch

                result
                    .onSuccess { videos ->
                        currentPath = if (path.isEmpty()) "/" else path
                        binding.recyclerView.adapter = videoAdapter
                        val listTopPadding = if (currentPath == "/") 8 else 48
                        binding.recyclerView.setPadding(
                            binding.recyclerView.paddingLeft, listTopPadding,
                            binding.recyclerView.paddingRight, binding.recyclerView.paddingBottom
                        )
                        binding.smbPathBar.text = "${server.shareName}${currentPath}"
                        binding.smbPathBar.visibility = View.VISIBLE
                        videoAdapter.submitList(videos)
                        binding.loadingBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        binding.emptyView.visibility =
                            if (videos.isEmpty()) View.VISIBLE else View.GONE

                        if (videos.isEmpty()) {
                            Toast.makeText(context, R.string.smb_dir_empty, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .onFailure { e ->
                        binding.loadingBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        toast(e.message ?: "浏览失败")
                    }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                binding.loadingBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(context, "浏览失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 返回键处理（v2.5.0）：
     * 浏览中且非根目录 → 返回上一级；浏览根目录 → 回服务器列表；否则交给 Activity 退出。
     */
    fun goBack(): Boolean {
        if (!isBrowsing) return false
        val server = currentServer
        if (server != null && currentPath != "/") {
            browseShare(server, com.swiftplayer.smb.SmbPaths.parentOf(currentPath))
            return true
        }
        showServerList()
        return true
    }

    fun refresh() {
        if (isBrowsing) {
            currentServer?.let { server ->
                browseShare(server, currentPath)
            }
        } else {
            startDiscovery()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        discoveryService.stop()
        smbClient.disconnect()
        _binding = null
    }
}
