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
    val blurb: String = ""
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
