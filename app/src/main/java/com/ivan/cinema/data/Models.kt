package com.ivan.cinema.data

data class VodSource(
    val name: String,
    val api: String
)

data class VodItem(
    val source: VodSource,
    val vodId: String,
    val name: String,
    val year: String,
    val pic: String,
    val remarks: String,
    val typeId: String,
    /** 一句话简介（列表接口自带），用作卡片副标题 */
    val blurb: String = "",
    /**
     * 源站播放量（MacCMS 的 vod_hits）。动漫专栏按热度排时作为第二信号 ——
     * B 站榜只覆盖到 5~15% 的条目，剩下那些总得有个依据，不能全靠源站顺序。
     * 部分源不返回该字段，此时为 0。
     */
    val hits: Int = 0
)

/** 一个片在各源的命中 */
data class SourceHit(
    val source: VodSource,
    val vodId: String,
    val remarks: String
)

/** 跨源归并后的条目 */
data class MergedVod(
    val key: String,
    val name: String,
    val year: String,
    val pic: String,
    val hits: MutableList<SourceHit> = mutableListOf()
)

data class Episode(
    val name: String,
    val url: String
)

data class PlayLine(
    val name: String,
    val episodes: List<Episode>
)

data class VodDetail(
    val source: VodSource,
    val vodId: String,
    val name: String,
    val year: String,
    val pic: String,
    val remarks: String,
    val typeName: String,
    val actor: String,
    val director: String,
    val score: String,
    val area: String,
    val lang: String,
    val content: String,
    val lines: List<PlayLine>
)
