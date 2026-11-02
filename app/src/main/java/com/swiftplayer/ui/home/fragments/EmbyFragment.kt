package com.swiftplayer.ui.home.fragments

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftplayer.MainActivity
import com.swiftplayer.R
import com.swiftplayer.data.emby.EmbyApi
import com.swiftplayer.data.emby.EmbyAuthManager
import com.swiftplayer.data.emby.EmbyItem
import com.swiftplayer.data.emby.EmbyRepository
import com.swiftplayer.data.emby.EmbyServer
import com.swiftplayer.data.emby.EmbySubtitleStream
import com.swiftplayer.data.emby.EmbyView
import com.swiftplayer.data.model.VideoItem
import com.swiftplayer.databinding.DialogAddEmbyServerBinding
import com.swiftplayer.databinding.DialogEmbySearchBinding
import com.swiftplayer.databinding.FragmentEmbyBinding
import com.swiftplayer.data.emby.EmbyTag
import com.swiftplayer.ui.components.EmbyPersonAdapter
import com.swiftplayer.ui.components.EmbyPosterAdapter
import com.swiftplayer.ui.components.EmbyServerAdapter
import com.swiftplayer.ui.components.EmbySimilarAdapter
import com.swiftplayer.ui.components.EmbyTagAdapter
import com.swiftplayer.ui.components.EmbyViewAdapter
import com.swiftplayer.ui.player.PlayerActivity
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Emby 主界面：服务器列表 → 媒体库 → 海报墙 → 详情 → 播放
 * 采用「单 Fragment 多视图态」以控制复杂度（R11），返回键按视图态回退。
 */
class EmbyFragment : Fragment() {

    private var _binding: FragmentEmbyBinding? = null
    private val binding get() = _binding!!

    private lateinit var authManager: EmbyAuthManager
    private lateinit var repository: EmbyRepository

    private lateinit var serverAdapter: EmbyServerAdapter
    private lateinit var viewAdapter: EmbyViewAdapter
    private lateinit var posterAdapter: EmbyPosterAdapter
    private lateinit var similarAdapter: EmbySimilarAdapter
    private lateinit var personAdapter: EmbyPersonAdapter
    private lateinit var tagAdapter: EmbyTagAdapter

    private var currentServer: EmbyServer? = null
    private var currentDetailItem: EmbyItem? = null
    private var lastViews: List<EmbyView> = emptyList()
    private var lastItems: List<EmbyItem> = emptyList()
    private var lastPeople: List<EmbyItem> = emptyList()
    private var currentState = State.SERVERS
    private var favoriteInProgress = false
    private var refreshDetailOnResume = false

    /** 海报墙的来源，决定返回时的回退目标（v2.5.0 演员/标签入口） */
    private enum class PostersOrigin { LIBRARIES, PEOPLE, TAGS }
    private var postersFrom = PostersOrigin.LIBRARIES

    private enum class State { SERVERS, LIBRARIES, POSTERS, DETAIL, PEOPLE, TAGS }

    companion object {
        private const val GRID_COLUMNS = 3
        private const val PERSON_COLUMNS = 4
        const val PREFS_APP = "app_settings"
        const val KEY_SHOW_HISTORY = "emby_show_history"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEmbyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        authManager = EmbyAuthManager(requireContext())
        val api = EmbyApi(authManager.getOrCreateDeviceId())
        repository = EmbyRepository(api, authManager)

        setupAdapters()
        binding.fabAddEmby.setOnClickListener { showAddServerDialog() }

        currentServer = authManager.current()
        loadServers()
    }

    override fun onResume() {
        super.onResume()
        // 设置里切换「继续观看」开关后返回时立即生效
        if (currentState == State.LIBRARIES) applyState()
        if (!refreshDetailOnResume) return
        refreshDetailOnResume = false
        val item = currentDetailItem ?: return
        if (currentState != State.DETAIL) return
        // 从播放器返回后延迟刷新，等待 Emby 落库最新进度。
        binding.root.postDelayed({
            if (isAdded && _binding != null && currentDetailItem?.id == item.id) {
                openDetail(item)
            }
        }, 700L)
    }

    private fun setupAdapters() {
        serverAdapter = EmbyServerAdapter(
            onServerClick = { server -> connectServer(server) },
            onServerDelete = { server -> confirmDeleteServer(server) }
        )
        viewAdapter = EmbyViewAdapter { view -> loadItems(view) }
        posterAdapter = EmbyPosterAdapter(
            onClick = { item -> openDetail(item) },
            urlBuilder = { item -> posterUrl(item) }
        )
        similarAdapter = EmbySimilarAdapter(
            onClick = { item -> openDetail(item) },
            urlBuilder = { item -> posterUrl(item) }
        )
        personAdapter = EmbyPersonAdapter(
            onClick = { person -> loadPersonItems(person) },
            urlBuilder = { person -> personAvatarUrl(person) }
        )
        tagAdapter = EmbyTagAdapter { tag -> loadTagItems(tag) }

        binding.serverRecycler.layoutManager = LinearLayoutManager(context)
        binding.serverRecycler.adapter = serverAdapter

        binding.libraryRecycler.layoutManager = LinearLayoutManager(context)
        binding.libraryRecycler.adapter = viewAdapter

        binding.posterRecycler.layoutManager = GridLayoutManager(context, GRID_COLUMNS)
        binding.posterRecycler.adapter = posterAdapter

        binding.personRecycler.layoutManager = GridLayoutManager(context, PERSON_COLUMNS)
        binding.personRecycler.adapter = personAdapter

        binding.tagRecycler.layoutManager = GridLayoutManager(context, 2)
        binding.tagRecycler.adapter = tagAdapter

        binding.similarRecycler.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.similarRecycler.adapter = similarAdapter

        binding.detailBack.setOnClickListener { navigateUp() }
        binding.btnDetailPlay.setOnClickListener { playCurrentDetail() }
        binding.btnDetailDelete.setOnClickListener { confirmDeleteItem() }
        binding.detailFavorite.setOnClickListener { toggleFavorite() }
        binding.favoritesRow.setOnClickListener { loadFavorites() }
        binding.historyRow.setOnClickListener { loadHistory() }
        binding.searchRow.setOnClickListener { showSearchDialog() }
        binding.peopleRow.setOnClickListener { loadPeople() }
        binding.tagsRow.setOnClickListener { loadTags() }
        binding.peopleBack.setOnClickListener { goBack() }
        binding.tagBack.setOnClickListener { goBack() }
        binding.posterBack.setOnClickListener { goBack() }

        // 演员本地过滤：不重复请求服务器
        binding.personSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                filterPeople(s?.toString().orEmpty())
            }
        })
    }

    /** 按关键字本地过滤演员（名称包含，忽略大小写） */
    private fun filterPeople(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) lastPeople else lastPeople.filter {
            it.name.contains(q, ignoreCase = true)
        }
        personAdapter.submitList(filtered)
        if (filtered.isEmpty()) showEmpty(R.string.emby_search_empty) else hideEmpty()
    }

    private fun hideEmpty() {
        binding.emptyView.visibility = View.GONE
    }

    /** 人物头像 URL（复用海报图构造，maxWidth=200 圆形裁剪由客户端完成） */
    private fun personAvatarUrl(person: EmbyItem): String? = currentServer?.let { s ->
        EmbyApi.buildImageUrl(repository.cachedBaseUrl(s), person.posterPath(200), s.accessToken)
    }

    /** 海报图 URL 构造（供海报墙与相似影片复用） */
    private fun posterUrl(item: EmbyItem): String? = currentServer?.let { s ->
        EmbyApi.buildImageUrl(repository.cachedBaseUrl(s), item.posterPath(300), s.accessToken)
    }

    // ---- 视图态 ----

    private fun applyState() {
        binding.serverRecycler.visibility = if (currentState == State.SERVERS) View.VISIBLE else View.GONE
        binding.libraryContainer.visibility = if (currentState == State.LIBRARIES) View.VISIBLE else View.GONE
        // 继续观看入口：仅 LIBRARIES 态且开关开启时显示（设置页可关闭，v2.5.2）
        binding.historyRow.visibility =
            if (currentState == State.LIBRARIES && historyEnabled()) View.VISIBLE else View.GONE
        binding.posterRecycler.visibility = if (currentState == State.POSTERS) View.VISIBLE else View.GONE
        binding.detailScroll.visibility = if (currentState == State.DETAIL) View.VISIBLE else View.GONE
        binding.peopleContainer.visibility = if (currentState == State.PEOPLE) View.VISIBLE else View.GONE
        binding.tagContainer.visibility = if (currentState == State.TAGS) View.VISIBLE else View.GONE
        binding.fabAddEmby.visibility = if (currentState == State.SERVERS) View.VISIBLE else View.GONE
        // 海报墙标题条：仅演员/标签入口显示，列表顶部 padding 随之补偿
        val showPosterHeader = currentState == State.POSTERS && postersFrom != PostersOrigin.LIBRARIES
        binding.posterHeader.visibility = if (showPosterHeader) View.VISIBLE else View.GONE
        val topPadding = if (showPosterHeader) 56 else 8
        binding.posterRecycler.setPadding(
            binding.posterRecycler.paddingLeft, topPadding,
            binding.posterRecycler.paddingRight, binding.posterRecycler.paddingBottom
        )
        binding.emptyView.visibility = View.GONE
    }

    private fun loadServers() {
        val servers = authManager.getServers()
        currentState = State.SERVERS
        applyState()
        serverAdapter.submitList(servers)
        if (servers.isEmpty()) {
            showEmpty(R.string.emby_no_servers)
        }
    }

    private fun connectServer(server: EmbyServer) {
        currentServer = server
        authManager.setCurrent(server.id)
        loadViews(server)
    }

    private fun loadViews(server: EmbyServer) {
        showLoading()
        lifecycleScope.launch {
            val result = repository.views(server)
            if (!isAdded || _binding == null) return@launch
            result.onSuccess { views ->
                hideLoading()
                lastViews = views
                currentState = State.LIBRARIES
                applyState()
                viewAdapter.submitList(views)
                if (views.isEmpty()) showEmpty(R.string.emby_no_items)
            }.onFailure { e ->
                hideLoading()
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    private fun loadItems(view: EmbyView) {
        val server = currentServer ?: return
        showLoading()
        lifecycleScope.launch {
            val result = repository.items(server, view.id)
            if (!isAdded || _binding == null) return@launch
            result.onSuccess { items ->
                hideLoading()
                lastItems = items
                postersFrom = PostersOrigin.LIBRARIES
                currentState = State.POSTERS
                applyState()
                posterAdapter.submitList(items)
                if (items.isEmpty()) showEmpty(R.string.emby_no_items)
            }.onFailure { e ->
                hideLoading()
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    // ---- 播放历史 / 继续观看（v2.5.2） ----

    /** 「继续观看」开关（设置页可关闭）；默认开启 */
    private fun historyEnabled(): Boolean =
        requireContext().getSharedPreferences(PREFS_APP, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_HISTORY, true)

    /** 播放历史：服务器侧 IsResumable 过滤（已开始未看完），最近播放优先 */
    private fun loadHistory() {
        val server = currentServer ?: return
        showLoading()
        lifecycleScope.launch {
            val result = repository.resumable(server)
            if (!isAdded || _binding == null) return@launch
            result.onSuccess { list ->
                hideLoading()
                lastItems = list
                postersFrom = PostersOrigin.LIBRARIES
                currentState = State.POSTERS
                applyState()
                posterAdapter.submitList(list)
                if (list.isEmpty()) showEmpty(R.string.emby_history_empty)
            }.onFailure { e ->
                hideLoading()
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    private fun openDetail(item: EmbyItem) {
        val server = currentServer ?: return
        showLoading()
        lifecycleScope.launch {
            val result = repository.detail(server, item.id)
            if (!isAdded || _binding == null) return@launch
            result.onSuccess { detail -> showDetail(server, detail) }
                .onFailure { e ->
                    hideLoading()
                    toast(e.message ?: getString(R.string.emby_err_invalid))
                }
        }
    }

    private fun showDetail(server: EmbyServer, item: EmbyItem) {
        hideLoading()
        currentDetailItem = item
        currentState = State.DETAIL
        applyState()

        binding.detailTitle.text = item.name
        binding.detailMeta.text = buildMeta(item)
        binding.detailOverview.text = item.overview ?: getString(R.string.emby_overview_empty)
        updateFavoriteIcon(item.isFavorite)
        updateResumeState(item)

        val backdrop = EmbyApi.buildImageUrl(repository.cachedBaseUrl(server), item.backdropPath(780), server.accessToken)
        val poster = EmbyApi.buildImageUrl(repository.cachedBaseUrl(server), item.posterPath(360), server.accessToken)
        Glide.with(this).load(backdrop).into(binding.detailBackdrop)
        Glide.with(this).load(poster).override(360, 540).into(binding.detailPoster)

        // 重置并异步加载相似影片（不阻塞详情展示）
        similarAdapter.submitList(emptyList())
        binding.similarSection.visibility = View.GONE
        loadSimilar(server, item)
    }

    private fun updateResumeState(item: EmbyItem) {
        val position = item.playbackPositionMs
        val duration = item.durationMs
        val canResume = position > 5_000L && duration > position + 5_000L
        binding.detailResumeRow.visibility = if (canResume) View.VISIBLE else View.GONE
        binding.btnDetailPlay.text = getString(
            if (canResume) R.string.emby_continue_play else R.string.emby_play
        )
        if (canResume) {
            val percent = if (item.playedPercentage > 0) {
                item.playedPercentage.toInt()
            } else {
                (position * 100 / duration).toInt()
            }.coerceIn(0, 100)
            binding.detailResumeText.text = getString(
                R.string.emby_resume_hint,
                VideoItem.formatDuration(position),
                percent
            )
            binding.detailResumeProgress.progress = percent
        }
    }

    private fun playCurrentDetail() {
        val server = currentServer ?: return
        val item = currentDetailItem ?: return
        refreshDetailOnResume = true
        showLoading()
        lifecycleScope.launch {
            val result = repository.resolvePlayback(server, item.id)
            if (!isAdded || _binding == null) return@launch
            result.onSuccess { playback ->
                hideLoading()
                (activity as? MainActivity)?.openPlayer(
                    Uri.parse(playback.playUri),
                    item.name,
                    0L,
                    PlayerActivity.SOURCE_EMBY,
                    item.id,
                    serializeSubtitles(playback.subtitleStreams),
                    embyBaseUrl = repository.cachedBaseUrl(server),
                    embyToken = server.accessToken,
                    embyPlaySessionId = playback.playSessionId,
                    embyMediaSourceId = playback.mediaSourceId,
                    embyResumePositionMs = item.playbackPositionMs
                )
            }.onFailure { e ->
                refreshDetailOnResume = false
                hideLoading()
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    // ---- 收藏（F1） ----

    /** 按收藏态刷新心形图标与色调 */
    private fun updateFavoriteIcon(isFavorite: Boolean) {
        binding.detailFavorite.setImageResource(
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        val colorRes = if (isFavorite) R.color.favorite_active else R.color.on_surface_variant
        binding.detailFavorite.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun toggleFavorite() {
        val server = currentServer ?: return
        val item = currentDetailItem ?: return
        if (favoriteInProgress) return
        val target = !item.isFavorite
        favoriteInProgress = true
        binding.detailFavorite.isEnabled = false
        lifecycleScope.launch {
            val result = repository.setFavorite(server, item.id, target)
            if (!isAdded || _binding == null) return@launch
            favoriteInProgress = false
            binding.detailFavorite.isEnabled = true
            result.onSuccess {
                val updated = item.copy(isFavorite = target)
                currentDetailItem = updated
                updateFavoriteIcon(target)
                toast(getString(if (target) R.string.emby_favorited else R.string.emby_unfavorited))
            }.onFailure { e ->
                toast(e.message ?: getString(R.string.emby_favorite_failed))
            }
        }
    }

    /** 收藏夹列表 → 海报墙态 */
    private fun loadFavorites() {
        val server = currentServer ?: return
        showLoading()
        lifecycleScope.launch {
            val result = repository.favorites(server)
            if (!isAdded || _binding == null) return@launch
            result.onSuccess { list ->
                hideLoading()
                lastItems = list
                postersFrom = PostersOrigin.LIBRARIES
                currentState = State.POSTERS
                applyState()
                posterAdapter.submitList(list)
                if (list.isEmpty()) showEmpty(R.string.emby_favorites_empty)
            }.onFailure { e ->
                hideLoading()
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    // ---- 演员 / 类型（v2.5.1 客户端聚合） ----

    /** 演员网格：从全库影片 People 聚合（只显示现有影片中有头像的人物），输入框本地过滤 */
    private fun loadPeople() {
        val server = currentServer ?: return
        showLoading()
        lifecycleScope.launch {
            val result = repository.itemsWithTags(server)
            if (!isAdded || _binding == null) return@launch
            result.onSuccess { items ->
                hideLoading()
                lastPeople = repository.aggregatePeople(items)
                personAdapter.submitList(lastPeople)
                binding.personSearch.setText("")
                currentState = State.PEOPLE
                applyState()
                if (lastPeople.isEmpty()) showEmpty(R.string.emby_people_empty)
            }.onFailure { e ->
                hideLoading()
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    /** 某演员的影片 → 海报墙（标题条显示人物名） */
    private fun loadPersonItems(person: EmbyItem) {
        val server = currentServer ?: return
        showLoading()
        lifecycleScope.launch {
            val result = repository.itemsByPerson(server, person.id)
            if (!isAdded || _binding == null) return@launch
            result.onSuccess { items ->
                hideLoading()
                lastItems = items
                postersFrom = PostersOrigin.PEOPLE
                currentState = State.POSTERS
                binding.posterTitle.text = person.name
                applyState()
                posterAdapter.submitList(items)
                if (items.isEmpty()) showEmpty(R.string.emby_person_no_items)
            }.onFailure { e ->
                hideLoading()
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    /** 类型清单：全库一次拉取 + Genres 优先聚合（空则回退 Tags），按影片数降序 */
    private fun loadTags() {
        val server = currentServer ?: return
        showLoading()
        lifecycleScope.launch {
            val result = repository.itemsWithTags(server)
            if (!isAdded || _binding == null) return@launch
            result.onSuccess { items ->
                hideLoading()
                val categories = repository.aggregateCategories(items)
                tagAdapter.submitList(categories)
                currentState = State.TAGS
                applyState()
                if (categories.isEmpty()) showEmpty(R.string.emby_tags_empty)
            }.onFailure { e ->
                hideLoading()
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    /** 某类型的影片：复用已拉取的全库条目本地过滤（★ v2.5.2 修复：聚合主源是 Genres，过滤须同步匹配 Genres，Tags 并集兜底） */
    private fun loadTagItems(tag: EmbyTag) {
        val cached = repository.cachedTagItems(currentServer?.id.orEmpty())
        if (cached == null) {
            // 缓存缺失（进程回收等）：回源一次再过滤
            val server = currentServer ?: return
            showLoading()
            lifecycleScope.launch {
                val result = repository.itemsWithTags(server)
                if (!isAdded || _binding == null) return@launch
                result.onSuccess { items ->
                    hideLoading()
                    showTagItems(filterByCategory(items, tag.name), tag.name)
                }.onFailure { e ->
                    hideLoading()
                    toast(e.message ?: getString(R.string.emby_err_invalid))
                }
            }
            return
        }
        showTagItems(filterByCategory(cached, tag.name), tag.name)
    }

    private fun filterByCategory(items: List<EmbyItem>, name: String): List<EmbyItem> =
        items.filter { it.genres.contains(name) || it.tags.contains(name) }

    private fun showTagItems(items: List<EmbyItem>, tag: String) {
        lastItems = items
        postersFrom = PostersOrigin.TAGS
        currentState = State.POSTERS
        binding.posterTitle.text = tag
        applyState()
        posterAdapter.submitList(items)
        if (items.isEmpty()) showEmpty(R.string.emby_tag_no_items)
    }

    // ---- 相似影片（F2） ----

    private fun loadSimilar(server: EmbyServer, item: EmbyItem) {
        lifecycleScope.launch {
            val result = repository.similar(server, item.id)
            if (!isAdded || _binding == null) return@launch
            // 详情可能在请求期间已切换，丢弃过期结果
            if (currentDetailItem?.id != item.id) return@launch
            result.onSuccess { list ->
                val filtered = list.filter { it.id != item.id }
                if (filtered.isEmpty()) {
                    binding.similarSection.visibility = View.GONE
                } else {
                    binding.similarSection.visibility = View.VISIBLE
                    similarAdapter.submitList(filtered)
                }
            }.onFailure {
                binding.similarSection.visibility = View.GONE
            }
        }
    }

    // ---- 搜索（F4） ----

    private fun showSearchDialog() {
        val dialogBinding = DialogEmbySearchBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emby_search)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.emby_search_action, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun submit() {
            val query = dialogBinding.etEmbySearch.text.toString().trim()
            if (query.isEmpty()) return
            dialog.dismiss()
            doSearch(query)
        }

        dialog.setOnShowListener {
            dialogBinding.etEmbySearch.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        }
        dialogBinding.etEmbySearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else {
                false
            }
        }
        dialog.show()
    }

    private fun doSearch(query: String) {
        val server = currentServer ?: return
        showLoading()
        lifecycleScope.launch {
            val result = repository.search(server, query)
            if (!isAdded || _binding == null) return@launch
            result.onSuccess { list ->
                hideLoading()
                if (list.isEmpty()) {
                    toast(getString(R.string.emby_search_empty))
                } else {
                    lastItems = list
                    postersFrom = PostersOrigin.LIBRARIES
                    currentState = State.POSTERS
                    applyState()
                    posterAdapter.submitList(list)
                }
            }.onFailure { e ->
                hideLoading()
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    /** 序列化外挂字幕流供播放器 side-load：[{index,label,language,url,default}] */
    private fun serializeSubtitles(streams: List<EmbySubtitleStream>): String {
        if (streams.isEmpty()) return ""
        val arr = JSONArray()
        streams.forEach { s ->
            val o = JSONObject()
            o.put("index", s.index)
            o.put("label", s.label)
            o.put("language", s.language ?: "")
            o.put("url", s.url)
            o.put("default", s.isDefault)
            arr.put(o)
        }
        return arr.toString()
    }

    // ---- 删除影片（库条目 + 文件 + 刮削文件，需管理员账号） ----

    private fun confirmDeleteItem() {
        val server = currentServer ?: return
        val item = currentDetailItem ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emby_delete_item_title)
            .setMessage(getString(R.string.emby_delete_item_message, item.name))
            .setPositiveButton(R.string.delete) { _, _ -> doDeleteItem(server, item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doDeleteItem(server: EmbyServer, item: EmbyItem) {
        showLoading()
        lifecycleScope.launch {
            val result = repository.deleteItem(server, item.id)
            if (!isAdded || _binding == null) return@launch
            hideLoading()
            result.onSuccess {
                toast(getString(R.string.emby_delete_item_ok))
                // 从本地列表移除并返回海报墙
                lastItems = lastItems.filterNot { it.id == item.id }
                currentDetailItem = null
                currentState = State.POSTERS
                applyState()
                posterAdapter.submitList(lastItems)
                if (lastItems.isEmpty()) showEmpty(R.string.emby_no_items)
            }.onFailure { e ->
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    private fun buildMeta(item: EmbyItem): String {
        val parts = mutableListOf<String>()
        if (item.productionYear > 0) parts.add("${item.productionYear}")
        if (item.durationMs > 0) parts.add(VideoItem.formatDuration(item.durationMs))
        if (item.communityRating > 0) parts.add(String.format("★ %.1f", item.communityRating))
        return parts.joinToString(" · ")
    }

    // ---- 导航 ----

    /** 返回上一视图态；已处于服务器列表则交由 Activity 处理 */
    fun goBack(): Boolean {
        return when (currentState) {
            State.DETAIL -> {
                currentState = State.POSTERS
                applyState()
                posterAdapter.submitList(lastItems)
                if (lastItems.isEmpty()) showEmpty(R.string.emby_no_items)
                true
            }
            State.POSTERS -> when (postersFrom) {
                PostersOrigin.PEOPLE -> {
                    currentState = State.PEOPLE
                    applyState()
                    personAdapter.submitList(lastPeople)
                    true
                }
                PostersOrigin.TAGS -> {
                    currentState = State.TAGS
                    applyState()
                    true
                }
                else -> {
                    currentState = State.LIBRARIES
                    applyState()
                    viewAdapter.submitList(lastViews)
                    if (lastViews.isEmpty()) showEmpty(R.string.emby_no_items)
                    true
                }
            }
            State.PEOPLE -> {
                currentState = State.LIBRARIES
                applyState()
                viewAdapter.submitList(lastViews)
                if (lastViews.isEmpty()) showEmpty(R.string.emby_no_items)
                true
            }
            State.TAGS -> {
                currentState = State.LIBRARIES
                applyState()
                viewAdapter.submitList(lastViews)
                if (lastViews.isEmpty()) showEmpty(R.string.emby_no_items)
                true
            }
            State.LIBRARIES -> {
                currentState = State.SERVERS
                loadServers()
                true
            }
            State.SERVERS -> false
        }
    }

    private fun navigateUp() {
        goBack()
    }

    // ---- 服务器管理 ----

    private fun showAddServerDialog() {
        val dialogBinding = DialogAddEmbyServerBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emby_add_server)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.emby_login, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener { doLogin(dialog, dialogBinding) }
        }
        dialogBinding.btnTestConnection.setOnClickListener { doTestConnection(dialogBinding) }
        dialog.show()
    }

    private fun doLogin(
        dialog: androidx.appcompat.app.AlertDialog,
        dialogBinding: DialogAddEmbyServerBinding
    ) {
        val address = dialogBinding.etEmbyAddress.text.toString().trim()
        val lanAddress = dialogBinding.etEmbyLanAddress.text.toString().trim()
        val username = dialogBinding.etEmbyUsername.text.toString().trim()
        val password = dialogBinding.etEmbyPassword.text.toString().trim()
        val remark = dialogBinding.etEmbyRemark.text.toString().trim()

        if (address.isEmpty()) {
            toast(getString(R.string.emby_err_empty_address))
            return
        }
        if (username.isEmpty() || password.isEmpty()) {
            toast(getString(R.string.emby_err_empty_credentials))
            return
        }

        val loginButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        loginButton.isEnabled = false

        lifecycleScope.launch {
            val result = repository.login(address, username, password, remark, lanAddress)
            if (!isAdded || _binding == null) return@launch
            loginButton.isEnabled = true
            result.onSuccess { server ->
                dialog.dismiss()
                toast("已添加：${server.name}")
                connectServer(server)
            }.onFailure { e ->
                toast(e.message ?: getString(R.string.emby_err_invalid))
            }
        }
    }

    private fun doTestConnection(dialogBinding: DialogAddEmbyServerBinding) {
        val address = dialogBinding.etEmbyAddress.text.toString().trim()
        if (address.isEmpty()) {
            toast(getString(R.string.emby_err_empty_address))
            return
        }
        val testButton = dialogBinding.btnTestConnection
        testButton.isEnabled = false
        lifecycleScope.launch {
            val result = repository.ping(address)
            if (!isAdded || _binding == null) return@launch
            testButton.isEnabled = true
            if (result.isSuccess) {
                toast(getString(R.string.emby_test_ok))
            } else {
                toast(result.exceptionOrNull()?.message ?: getString(R.string.emby_test_failed))
            }
        }
    }

    private fun confirmDeleteServer(server: EmbyServer) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.emby_delete_server)
            .setMessage(server.name)
            .setPositiveButton(R.string.delete) { _, _ ->
                authManager.remove(server.id)
                if (currentServer?.id == server.id) currentServer = null
                loadServers()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- 通用 UI ----

    private fun showLoading() {
        binding.loadingBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
    }

    private fun hideLoading() {
        binding.loadingBar.visibility = View.GONE
    }

    private fun showEmpty(resId: Int) {
        binding.emptyText.setText(resId)
        binding.emptyView.visibility = View.VISIBLE
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
