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
        lang: String? = null
    ): List<VodItem> =
        coroutineScope {
            val sem = Semaphore(10)
            sources
                .filter { tidBySource.containsKey(it.api) }
                .take(maxSources)
                .map { src ->
                    async(Dispatchers.IO) {
                        val tid = tidBySource[src.api] ?: return@async emptyList<VodItem>()
                        sem.withPermit { MacCmsApi(src).list(tid, page, by, area, cls, year, lang) }
                    }
                }.awaitAll().flatten()
                .distinctBy { mergeKey(it.name, it.year) }
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
                    tab.matchers.any { m -> n.contains(m) }
                }
                if (hit == null) {
                    // 源连通但分类名不匹配：主分类（电影/剧集/综艺/动漫）走通用 tid 兜底；
                    // 扩展分类（纪录片/少儿/体育/短剧）没有通用 tid —— 跳过该源，避免显示错内容
                    val fallback = tab.defaultTid
                    if (fallback == null) {
                        ok[src.name] = true
                        null
                    } else {
                        ok[src.name] = true
                        src.api to fallback
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
