package com.swiftplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.swiftplayer.databinding.ActivityMainBinding
import com.swiftplayer.ui.home.MainPagerAdapter
import com.swiftplayer.ui.home.fragments.LocalMediaFragment
import com.swiftplayer.ui.player.PlayerActivity
import com.swiftplayer.ui.util.StoragePermissionHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter
    private lateinit var permissionHelper: StoragePermissionHelper

    private val prefs by lazy {
        getSharedPreferences(LocalMediaFragment.PREFS_SETTINGS, Context.MODE_PRIVATE)
    }

    private var pendingLanTab = false
    private var lastAccessGranted = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            binding.viewPager.post {
                pagerAdapter.refreshAll()
            }
            if (pendingLanTab) {
                pendingLanTab = false
                binding.viewPager.currentItem = 1
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 (targetSdk 35) 强制 edge-to-edge：显式声明后由下方 inset 逻辑手动避让状态栏/挖孔/导航栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionHelper = StoragePermissionHelper(this)
        lastAccessGranted = permissionHelper.hasFullAccess()

        applyWindowInsets()
        setupUI()
        setupBackPress()
        setupTabListener()
        setupMenuButton()
        checkPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 处理 edge-to-edge 的窗口 inset：
     * 顶部给 AppBar 让出状态栏 / 摄像头挖孔；底部给内容区让出导航栏（手势条）。
     * 播放器 PlayerActivity 有自己的全屏逻辑，不在此处理。
     */
    private fun applyWindowInsets() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        // 深色背景下强制使用浅色状态栏/导航栏图标，避免浅色模式下图标不可见
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val topInset = maxOf(systemBars.top, cutout.top)
            val bottomInset = maxOf(systemBars.bottom, cutout.bottom)
            val leftInset = maxOf(systemBars.left, cutout.left)
            val rightInset = maxOf(systemBars.right, cutout.right)

            // 顶部栏：状态栏 + 挖孔
            binding.appBar.updatePadding(top = topInset)
            // 内容容器：导航栏 + 挖孔（横向挖孔在竖屏下为 0，横屏可兜底）
            binding.pagerContainer.updatePadding(
                left = leftInset,
                right = rightInset,
                bottom = bottomInset
            )
            insets
        }
    }

    private fun setupUI() {
        pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 2

        val tabTitles = arrayOf(
            getString(R.string.tab_local),
            getString(R.string.tab_network),
            getString(R.string.tab_emby)
        )
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (binding.viewPager.currentItem) {
                    0 -> {
                        val fragment = pagerAdapter.getLocalFragment()
                        if (fragment != null && fragment.goBack()) {
                            return
                        }
                    }
                    1 -> {
                        val fragment = pagerAdapter.getSmbFragment()
                        if (fragment != null && fragment.goBack()) {
                            return
                        }
                    }
                    2 -> {
                        val fragment = pagerAdapter.getEmbyFragment()
                        if (fragment != null && fragment.goBack()) {
                            return
                        }
                    }
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun setupTabListener() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.position == 1) {
                    checkLanPermissions()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupMenuButton() {
        binding.btnMenu.setOnClickListener { anchor -> showMainMenu(anchor) }
    }

    private fun showMainMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        val showHiddenItem = popup.menu.findItem(R.id.menu_show_hidden)
        showHiddenItem.isCheckable = true
        showHiddenItem.isChecked = prefs.getBoolean(LocalMediaFragment.KEY_SHOW_HIDDEN, true)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_open_file -> {
                    openFileBrowser()
                    true
                }
                R.id.menu_show_hidden -> {
                    toggleShowHidden(item.isChecked)
                    true
                }
                R.id.menu_refresh -> {
                    pagerAdapter.refreshAll()
                    Toast.makeText(this, R.string.menu_refresh, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.menu_settings -> {
                    showSettingsDialog()
                    true
                }
                R.id.menu_about -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleShowHidden(currentChecked: Boolean) {
        val newValue = !currentChecked
        prefs.edit().putBoolean(LocalMediaFragment.KEY_SHOW_HIDDEN, newValue).apply()
        pagerAdapter.getLocalFragment()?.refresh()
        Toast.makeText(
            this,
            if (newValue) "已显示隐藏文件夹" else "已隐藏文件夹",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openFileBrowser() {
        binding.viewPager.setCurrentItem(0, false)
        val root = Environment.getExternalStorageDirectory()
        binding.viewPager.post {
            val fragment = pagerAdapter.getLocalFragment()
            if (fragment != null) {
                fragment.openBrowserAt(root)
            } else {
                // Fragment 尚未创建完成，稍后再试一次
                binding.viewPager.postDelayed({
                    pagerAdapter.getLocalFragment()?.openBrowserAt(root)
                }, 120L)
            }
        }
    }

    private fun showAboutDialog() {
        val message = getString(R.string.about_version, BuildConfig.VERSION_NAME) +
                "\n\n本地 / SMB / Emby 三源统一播放器"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    /**
     * 设置（v2.5.2）：轻量对话框 + 开关。
     * 「继续观看」入口开关即时生效——Emby 页 onResume 会重读开关并刷新。
     */
    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val appPrefs = getSharedPreferences(
            com.swiftplayer.ui.home.fragments.EmbyFragment.PREFS_APP,
            android.content.Context.MODE_PRIVATE
        )
        val historySwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.settings_show_history)
            isChecked = appPrefs.getBoolean(
                com.swiftplayer.ui.home.fragments.EmbyFragment.KEY_SHOW_HISTORY, true
            )
            setOnCheckedChangeListener { _, checked ->
                appPrefs.edit()
                    .putBoolean(com.swiftplayer.ui.home.fragments.EmbyFragment.KEY_SHOW_HISTORY, checked)
                    .apply()
            }
        }
        val hint = android.widget.TextView(this).apply {
            text = getString(R.string.settings_show_history_hint)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface_variant))
        }
        container.addView(historySwitch)
        container.addView(hint)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun checkLanPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissions.isNotEmpty()) {
            pendingLanTab = true
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun checkPermissions() {
        val runtimePermission = permissionHelper.requiredRuntimePermission()
        if (runtimePermission != null) {
            // Android 10 及以下：常规存储权限
            if (!permissionHelper.hasFullAccess()) {
                requestPermissionLauncher.launch(arrayOf(runtimePermission))
            }
        } else if (permissionHelper.needsAllFilesGuide()) {
            // Android 11+：引导「所有文件访问」（仅首次弹说明）
            if (!prefs.getBoolean(LocalMediaFragment.KEY_ALL_FILES_GUIDED, false)) {
                showAllFilesGuide()
            }
        }
    }

    private fun showAllFilesGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_all_files_title)
            .setMessage(R.string.perm_all_files_message)
            .setPositiveButton(R.string.perm_grant) { _, _ ->
                prefs.edit().putBoolean(LocalMediaFragment.KEY_ALL_FILES_GUIDED, true).apply()
                permissionHelper.openAllFilesAccessSettings()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                prefs.edit().putBoolean(LocalMediaFragment.KEY_ALL_FILES_GUIDED, true).apply()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        val access = permissionHelper.hasFullAccess()
        if (access != lastAccessGranted) {
            lastAccessGranted = access
            binding.viewPager.post { pagerAdapter.refreshAll() }
            if (access) {
                Toast.makeText(this, "已获得所有文件访问权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                openPlayer(uri, intent.getStringExtra(Intent.EXTRA_TITLE) ?: "")
            }
        }
    }

    /**
     * 统一播放入口：所有媒体源最终都以「一个可播放 URI + 一组元数据」进入 PlayerActivity。
     */
    fun openPlayer(
        uri: Uri,
        title: String = "",
        fileSize: Long = 0,
        sourceType: String = PlayerActivity.SOURCE_LOCAL,
        embyItemId: String? = null,
        embySubtitlesJson: String? = null,
        embyBaseUrl: String = "",
        embyToken: String = "",
        embyPlaySessionId: String = "",
        embyMediaSourceId: String = "",
        embyResumePositionMs: Long = 0,
        smbServer: com.swiftplayer.data.model.SmbServer? = null
    ) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = uri
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_FILE_SIZE, fileSize)
            putExtra(PlayerActivity.EXTRA_SOURCE_TYPE, sourceType)
            embyItemId?.let { putExtra(PlayerActivity.EXTRA_EMBY_ITEM_ID, it) }
            if (!embySubtitlesJson.isNullOrEmpty()) {
                putExtra(PlayerActivity.EXTRA_EMBY_SUBTITLES, embySubtitlesJson)
            }
            if (embyBaseUrl.isNotEmpty()) {
                putExtra(PlayerActivity.EXTRA_EMBY_BASE_URL, embyBaseUrl)
            }
            if (embyToken.isNotEmpty()) {
                putExtra(PlayerActivity.EXTRA_EMBY_TOKEN, embyToken)
            }
            if (embyPlaySessionId.isNotEmpty()) {
                putExtra(PlayerActivity.EXTRA_EMBY_PLAY_SESSION_ID, embyPlaySessionId)
            }
            if (embyMediaSourceId.isNotEmpty()) {
                putExtra(PlayerActivity.EXTRA_EMBY_MEDIA_SOURCE_ID, embyMediaSourceId)
            }
            if (embyResumePositionMs > 0) {
                putExtra(PlayerActivity.EXTRA_RESUME_POSITION, embyResumePositionMs)
            }
            smbServer?.let { server ->
                putExtra(PlayerActivity.EXTRA_SMB_ADDRESS, server.address)
                putExtra(PlayerActivity.EXTRA_SMB_SHARE, server.shareName)
                putExtra(PlayerActivity.EXTRA_SMB_USERNAME, server.username)
                putExtra(PlayerActivity.EXTRA_SMB_PASSWORD, server.password)
                putExtra(PlayerActivity.EXTRA_SMB_DOMAIN, server.domain)
            }
        }
        startActivity(intent)
    }
}
