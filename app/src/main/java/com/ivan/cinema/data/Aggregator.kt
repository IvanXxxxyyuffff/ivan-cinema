package com.ivan.cinema.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

object Aggregator {

    private val NAME_SUFFIX = Regex(
        "(TV版|4K|8K|1080P|蓝光|HDR|国语|粤语|完整版|经典版|未删减|加长版|导演剪辑版|高清版|珍藏版|剧场版)$",
        RegexOption.IGNORE_CASE
    )
    private val PUNCT = Regex("[\\s《》〈〉·・:：,，。.!！?？~～\\-_&+\\[\\]()【】（）\"'“”‘’]")

    fun normalize(name: String): String {
        var s = name
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in '！'..('～') -> sb.append((ch.code - 0xFEE0).toChar())
                '　' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        s = sb.toString()
        while (true) {
            val stripped = NAME_SUFFIX.replace(s, "")
            if (stripped == s) break
            s = stripped
        }
        return PUNCT.replace(s, "").lowercase()
    }

    fun mergeKey(name: String, year: String): String =
        normalize(name) + "|" + year.filter { it.isDigit() }.take(4)

    /**
     * 流式搜索：每完成一个源就归并上屏（增量 hits 追加到同一 MergedVod）。
     */
    fun searchAll(
        sources: List<VodSource>,
        query: String,
        concurrency: Int = 10
    ): Flow<MergedVod> = channelFlow {
        val index = HashMap<String, MergedVod>()
        val mutex = kotlinx.coroutines.sync.Mutex()
        val sem = Semaphore(concurrency)
        coroutineScope {
            val jobs = sources.map { src ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val items = MacCmsApi(src).search(query)
                        if (items.isNotEmpty()) {
                            val local = LinkedHashMap<String, MergedVod>()
                            for (it in items) {
                                val k = mergeKey(it.name, it.year)
                                val m = local.getOrPut(k) {
                                    MergedVod(k, it.name, it.year, it.pic)
                                }
                                m.hits.add(SourceHit(src, it.vodId, it.remarks))
                            }
                            mutex.withLock {
                                for ((k, m) in local) {
                                    val exist = index[k]
                                    if (exist == null) {
                                        index[k] = m
                                        trySend(m)
                                    } else {
                                        exist.hits.addAll(m.hits)
                                        trySend(exist)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
        close()
    }

    /** 详情页跨源拉全线路：一个归并 key 下所有源都取 detail */
    suspend fun fetchDetailAll(hits: List<SourceHit>): List<VodDetail> = coroutineScope {
        val sem = Semaphore(8)
        hits.map { hit ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    MacCmsApi(hit.source).detail(hit.vodId)
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * 补全多源命中：从首页/分类进入详情页时 hits 往往只有 1 条（该分类列表只来自一个源），
     * 于是播放器拿不到其他源、脏源无法绕过。这里按片名搜全网把同片的其他源补齐。
     */
    suspend fun expandHits(
        sources: List<VodSource>,
        name: String,
        year: String,
        existing: List<SourceHit>
    ): List<SourceHit> = coroutineScope {
        val key = mergeKey(name, year)
        val sem = Semaphore(10)
        val extra = sources.map { src ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    MacCmsApi(src).search(name).mapNotNull { it ->
                        if (mergeKey(it.name, it.year) == key) SourceHit(src, it.vodId, it.remarks) else null
                    }
                }
            }
        }.awaitAll().flatten()

        val seen = HashSet<String>()
        val out = ArrayList<SourceHit>(existing.size + extra.size)
        for (h in existing + extra) {
            if (seen.add(h.source.api)) out.add(h)
        }
        out
    }

    /**
     * 分类：聚合每源的 tid 列表，一页。
     * [maxSources] 限制参与的源数 —— 横滚行只需要几十张海报，
     * 用全部 20 源会把首屏拖慢 4 倍（20 源 × 4 行 = 80 个请求）。
     * 跨源按「片名+年份」归并去重（同部片在多个源出现时只留第一条）。
     */
    suspend fun category(
        sources: List<VodSource>,
        tidBySource: Map<String, String>,
        page: Int,
        maxSources: Int = Int.MAX_VALUE,
        by: String? = null,
        area: String? = null,
        cls: String? = null,
        year: String? = null,
        lang: String? = null,
        sourceWeight: ((String) -> Int)? = null
    ): List<VodItem> =
        coroutineScope {
            val sem = Semaphore(10)
            orderedSources(sources, tidBySource, maxSources, sourceWeight)
                .map { src ->
                    async(Dispatchers.IO) {
                        val tid = tidBySource[src.api] ?: return@async emptyList<VodItem>()
                        sem.withPermit { MacCmsApi(src).list(tid, page, by, area, cls, year, lang) }
                    }
                }.awaitAll().flatten()
                .distinctBy { mergeKey(it.name, it.year) }
        }

    /**
     * 分类源排序规则（TASK 2 共享健康降权）：
     *   - [sourceWeight] 为 null（默认）时**完全保持调用方传入的源顺序**，
     *     与旧 [category] 行为逐字节一致，不做任何重排；
     *   - 非 null 时按权重**降序**稳定排序（权重高 = 更健康 = 先发先落位），
     *     同权重保持原始顺序（sortedByDescending 是稳定排序）。
     *   - **只降权、不隐藏**：坏源照样参与查询、结果照样展示，只是排在后面。
     *     任何源都不会因为权重低而被丢弃，[maxSources] 仍是唯一的裁剪依据。
     */
    private fun orderedSources(
        sources: List<VodSource>,
        tidBySource: Map<String, String>,
        maxSources: Int,
        sourceWeight: ((String) -> Int)?
    ): List<VodSource> {
        val candidates = sources.filter { tidBySource.containsKey(it.api) }
        return if (sourceWeight == null) {
            candidates.take(maxSources)
        } else {
            candidates.sortedByDescending { sourceWeight(it.api) }.take(maxSources)
        }
    }

    /**
     * 流式分类：**每完成一个源就追加一批**，首屏约 1s 出图，不再被最慢的源卡住。
     *
     * 契约（其他调用方依赖，勿改）：
     *   ① **追加式、绝不重排**：已发出的条目位置永不改变，后到的源只在末尾追加，
     *      避免用户手指下的网格发生跳动（这是刻意的产品决定）；
     *   ② 跨源按 [mergeKey] 去重，且与**已发出的条目**再去重一次；
     *   ③ 每个源独立 [runCatching]，单源失败/超时只跳过该源，绝不取消整个 Flow；
     *   ④ 并发用 [Semaphore] 限制，与 [searchAll] 同一套模式；
     *   ⑤ [sourceWeight] 只影响**启动顺序与落位先后**（权重高者先查先落），
     *      null 时保持调用方原始源顺序；任何源都不会被丢弃。
     *
     * 没有源命中时不会发出任何值（调用方应把「从未收到值」当作空结果处理）。
     */
    fun categoryStream(
        sources: List<VodSource>,
        tidMap: Map<String, String>,
        page: Int,
        maxSources: Int = 8,
        by: String? = null,
        area: String? = null,
        cls: String? = null,
        year: String? = null,
        lang: String? = null,
        sourceWeight: ((String) -> Int)? = null
    ): Flow<List<VodItem>> = channelFlow {
        val ordered = orderedSources(sources, tidMap, maxSources, sourceWeight)
        val seen = HashSet<String>()
        val out = ArrayList<VodItem>()
        val mutex = Mutex()
        val sem = Semaphore(10)
        coroutineScope {
            val jobs = ordered.map { src ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val tid = tidMap[src.api] ?: return@withPermit
                        // 单源失败/超时只跳过，绝不冒泡取消整个 Flow
                        val items = runCatching {
                            MacCmsApi(src).list(tid, page, by, area, cls, year, lang)
                        }.getOrNull() ?: return@withPermit
                        if (items.isEmpty()) return@withPermit
                        mutex.withLock {
                            var added = false
                            for (it in items) {
                                if (seen.add(mergeKey(it.name, it.year))) {
                                    out.add(it)
                                    added = true
                                }
                            }
                            if (added) trySend(out.toList())
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
        close()
    }

    /** 每源 class 探测结果：tid 映射 + 每源成功明细（供失败态展示） */
    data class ClassResolve(
        val tidMap: Map<String, String>,
        val sourceOk: Map<String, Boolean>
    )

    /**
     * 每源 class 探测：把通用 tab 映射到各源 tid。
     * 源连通但分类名不命中时，回退到 MacCMS 通用 tid 约定（1电影/2剧集/3综艺/4动漫）。
     */
    suspend fun resolveClassMap(
        sources: List<VodSource>,
        tab: HomeTab
    ): ClassResolve = resolveClassMap(sources, tab.matchers, tab.defaultTid)

    /**
     * 同上，但按任意 matchers / defaultTid 解析 —— 动漫的子专栏（国漫/日漫）走这条。
     * [defaultTid] 为 null 时，分类名不命中的源会被跳过而不是兜底：子专栏没有通用 tid，
     * 兜底会把整库动漫塞进「国漫」里，那是错的。
     */
    suspend fun resolveClassMap(
        sources: List<VodSource>,
        matchers: List<String>,
        defaultTid: String?
    ): ClassResolve = coroutineScope {
        val sem = Semaphore(10)
        val ok = HashMap<String, Boolean>()
        val map = sources.map { src ->
            async(Dispatchers.IO) {
                val cs = sem.withPermit { MacCmsApi(src).classes() }
                if (cs.isEmpty()) {
                    ok[src.name] = false
                    return@async null
                }
                val hit = cs.firstOrNull { (_, n) ->
                    matchers.any { m -> n.contains(m) }
                }
                if (hit == null) {
                    // 源连通但分类名不匹配：主分类（电影/剧集/综艺/动漫）走通用 tid 兜底；
                    // 扩展分类（纪录片/少儿/体育/短剧、动漫子专栏）没有通用 tid —— 跳过该源
                    if (defaultTid == null) {
                        ok[src.name] = true
                        null
                    } else {
                        ok[src.name] = true
                        src.api to defaultTid
                    }
                } else {
                    ok[src.name] = true
                    src.api to hit.first
                }
            }
        }.awaitAll().filterNotNull().toMap()
        ClassResolve(map, ok)
    }
}

enum class HomeTab(
    val title: String,
    val defaultTid: String?,
    val matchers: List<String>
) {
    MOVIE("电影", "1", listOf("电影", "动作片", "喜剧片", "科幻片", "剧情片", "片")),
    SERIES("剧集", "2", listOf("电视", "国产剧", "港台剧", "日韩剧", "欧美剧", "连续剧", "剧")),
    VARIETY("综艺", "3", listOf("综艺")),
    ANIME("动漫", "4", listOf("动漫", "动画")),
    // 以下分类各源命名不一：靠 matchers 命中，命中不到就跳过该源（不兜底，避免显示错内容）
    DOCUMENTARY("纪录片", null, listOf("纪录片", "记录片", "纪录")),
    KIDS("少儿", null, listOf("少儿", "儿童", "亲子")),
    SPORTS("体育", null, listOf("体育", "足球", "篮球")),
    SHORT("短剧", null, listOf("短剧", "微短剧"))
}

/**
 * 动漫的子专栏。
 *
 * 各源对动漫的分类命名不统一（茅台是「国产动漫/日本动漫/欧美动漫」，
 * 量子/最大/无尽是「国产动漫/日韩动漫/欧美动漫/港台动漫」），所以每个专栏给一组
 * matchers 去命中，而不是写死 tid。
 *
 * [defaultTid] 只有 [ALL] 有（MacCMS 通用约定 4=动漫）：子专栏一旦兜底就会把整库动漫
 * 当成「国漫」显示，宁可不显示也不能显示错的。
 */
enum class AnimeSub(
    val title: String,
    /** 分类映射的缓存 key。必须和 HomeTab.name 区分开，否则「国漫」会读到「动漫」的缓存。 */
    val cacheKey: String,
    val matchers: List<String>,
    val defaultTid: String?
) {
    ALL("全部", "ANIME_ALL", listOf("动漫", "动画"), "4"),
    CN("国漫", "ANIME_CN", listOf("国产动漫", "国产动画", "国漫"), null),
    JP("日漫", "ANIME_JP", listOf("日本动漫", "日韩动漫", "日漫"), null),
    US("欧美", "ANIME_US", listOf("欧美动漫", "欧美动画"), null),
    HK("港台", "ANIME_HK", listOf("港台动漫", "港台动画"), null)
}
