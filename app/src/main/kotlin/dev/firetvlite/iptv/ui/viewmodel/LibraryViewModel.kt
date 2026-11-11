package dev.firetvlite.iptv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.data.db.ChannelRow
import dev.firetvlite.iptv.data.db.FavoriteEntity
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.data.library.ChannelSearch
import dev.firetvlite.iptv.data.library.ImportState
import dev.firetvlite.iptv.data.library.LibraryRepository
import dev.firetvlite.iptv.data.library.ListZapContext
import dev.firetvlite.iptv.data.library.QueryZapContext
import dev.firetvlite.iptv.data.library.ZapContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

/** An entry of the group list: a [GroupKey] with the number of channels in it. */
data class GroupItem(
    val key: GroupKey,
    val channelCount: Int,
)

/** Everything the live TV screen and the settings need to know about the channel list. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as FireLiteApplication).container
    private val repository: LibraryRepository = container.library
    private val channelDao = container.database.channelDao()
    private val userDao = container.database.userDao()

    val source: StateFlow<SourceEntity?> =
        repository.source.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val importState: StateFlow<ImportState> = repository.importState
    val epgStates = container.epg.states

    val sources = repository.sources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val sourceId: Flow<Long?> = source.map { it?.id }.distinctUntilChanged()

    val recentRows = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else channelDao.observeRecentRows(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val homeFavorites = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else channelDao.observeHomeFavorites(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableSearchText = MutableStateFlow("")
    val searchText = mutableSearchText.asStateFlow()
    val searchRows = combine(sourceId, searchText.map { ChannelSearch.pattern(it) }.distinctUntilChanged()) { id, pattern ->
        id to pattern
    }.flatMapLatest { (id, pattern) ->
        flow {
            emit(emptyList<ChannelRow>())
            if (id != null && pattern != null) {
                delay(300)
                emitAll(channelDao.observeSearch(id, pattern))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchText(text: String) { mutableSearchText.value = text }

    val channelCount: StateFlow<Int> = sourceId
        .flatMapLatest { id -> if (id == null) flowOf(0) else channelDao.observeCount(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val mutableSelectedGroup = MutableStateFlow<GroupKey>(GroupKey.All)
    val selectedGroup: StateFlow<GroupKey> = mutableSelectedGroup.asStateFlow()

    init {
        viewModelScope.launch {
            sourceId.collect { mutableSelectedGroup.value = GroupKey.All }
        }
    }

    fun selectGroup(key: GroupKey) {
        mutableSelectedGroup.value = key
    }

    /** Favourites, recently watched, all channels, then the groups of the playlist. */
    val groups: StateFlow<List<GroupItem>> = sourceId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(emptyList())
            } else {
                combine(
                    channelDao.observeGroups(id),
                    channelDao.observeCount(id),
                    userDao.observeFavoriteCount(id),
                    userDao.observeRecentCount(id),
                ) { named, all, favorites, recent ->
                    buildList {
                        add(GroupItem(GroupKey.Favorites, favorites))
                        add(GroupItem(GroupKey.Recent, recent))
                        add(GroupItem(GroupKey.All, all))
                        named.forEach { add(GroupItem(GroupKey.Named(it.groupName), it.channelCount)) }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The channels of the selected group, loaded page by page. */
    fun channels(key: GroupKey): Flow<PagingData<ChannelRow>> = sourceId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(PagingData.empty<ChannelRow>())
            } else {
                Pager(PagingConfig(pageSize = 40, enablePlaceholders = true)) {
                    when (key) {
                        GroupKey.Favorites -> channelDao.pagingFavorites(id)
                        GroupKey.Recent -> channelDao.pagingRecent(id)
                        GroupKey.All -> channelDao.pagingAll(id)
                        is GroupKey.Named -> channelDao.pagingGroup(id, key.name)
                    }
                }.flow
            }
        }

    fun toggleFavorite(streamUrl: String) {
        viewModelScope.launch {
            if (userDao.isFavorite(streamUrl)) {
                userDao.removeFavorite(streamUrl)
            } else {
                userDao.addFavorite(FavoriteEntity(streamUrl, System.currentTimeMillis()))
            }
        }
    }

    fun removeFavorite(streamUrl: String) {
        viewModelScope.launch { userDao.removeFavorite(streamUrl) }
    }

    suspend fun channelIndex(key: GroupKey, url: String): Int? {
        val id = source.value?.id ?: return null
        return when (key) {
            GroupKey.All -> channelDao.allIndex(id, url)
            is GroupKey.Named -> channelDao.groupIndex(id, key.name, url)
            GroupKey.Favorites -> channelDao.favoriteChannels(id).indexOfFirst { it.streamUrl == url }.takeIf { it >= 0 }
            GroupKey.Recent -> channelDao.recentChannels(id).indexOfFirst { it.streamUrl == url }.takeIf { it >= 0 }
        }
    }

    fun currentProgrammes(channelIds: List<Long>, time: Long) = container.epg.programmes(channelIds, time, time + 1)

    fun programmes(channelIds: List<Long>, from: Long, until: Long) = container.epg.programmes(channelIds, from, until)

    fun guideChannels(channelIds: List<Long>) = container.epg.guideChannels(channelIds)

    fun guideLimits(key: GroupKey): Flow<Pair<Int, Long?>> = sourceId.flatMapLatest { id ->
        if (id == null) flowOf(0 to null) else {
            val group = (key as? GroupKey.Named)?.name
            combine(
                channelDao.guideCatchupDays(id, group, key == GroupKey.Favorites, key == GroupKey.Recent),
                container.database.epgDao().guideEnd(id, group, key == GroupKey.Favorites, key == GroupKey.Recent),
            ) { days, end -> days to end }
        }
    }

    fun guide(channelId: Long, time: Long) = container.epg.observeCurrentAndNextAt(channelId, time)

    /** The list to walk through with channel up/down when a channel of [key] is opened. */
    suspend fun zapContext(key: GroupKey): ZapContext? {
        val id = source.value?.id ?: return null
        return when (key) {
            GroupKey.Favorites -> ListZapContext(channelDao.favoriteChannels(id))
            GroupKey.Recent -> ListZapContext(channelDao.recentChannels(id))
            GroupKey.All -> QueryZapContext(
                countQuery = { channelDao.count(id) },
                atQuery = { channelDao.allAt(id, it) },
            )
            is GroupKey.Named -> QueryZapContext(
                countQuery = { channelDao.groupCount(id, key.name) },
                atQuery = { channelDao.groupAt(id, key.name, it) },
            )
        }
    }

    fun saveSource(url: String, userAgent: String, epgUrl: String, id: Long?) =
        repository.saveSource(url, userAgent, epgUrl, id)

    fun activateSource(id: Long) = repository.activateSource(id)

    fun deleteSource(id: Long) = repository.deleteSource(id)

    fun refreshSource() = repository.refresh()
}
