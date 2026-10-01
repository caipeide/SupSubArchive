package com.peide.supsub.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地相似文章聚类引擎（纯词法，零模型零联网）。
 *
 * 目标：把「同事件 / 同主题但不同公众号转载或改写发布」的文章归并到同一簇，
 * 典型如「Dyna-2：百万小时人类视频…」与「Dyna-2：100 万小时人类视频，跑出了…」这类跨号同文。
 *
 * 思路：
 *  1) 归一化标题（全角→半角、小写、数字归一「百万↔100万」、去标点空白）；
 *  2) 按 keyword 建倒排，只比较「共享实体 + 时间窗内」的候选对，避免 O(n²)；
 *  3) 标题字符 n-gram Jaccard + keywords 重叠打分，并查集（Union-Find）连成簇；
 *  4) 选代表（信息最完整者）派生 clusterLabel（代表标题前缀，用于 Notion「聚类主题」分组），clusterId 取代表 contentId（稳定）。
 *
 * 落库：仅对「簇分配发生变化」的文章回写并置 sync_status=PENDING，
 * 让 Notion 同步引擎走原地 PATCH 补写「聚类主题」属性，不重建页面。
 *
 * 取舍：纯词法能命中「Dyna-2」这类实体/数字一致但措辞不同的改写；
 * 但「Scaling Law ↔ 规模化定律」这种纯语义改写不在词法覆盖范围（需语义 embedding，见计划）。
 */
class ClusterEngine(private val store: ArchiveStore) {

    /** 一次聚类的结果统计 */
    data class Result(val clusters: Int, val reassigned: Int)

    /**
     * 增量聚类：只聚「未聚类（cluster_id 为空）」的文章，且按自然日独立成簇（不跨天合并）。
     * 拉取后自动调用——新文章按发布日归入当天簇集，与当天已有簇合并，不重算其他天、不跨天。
     */
    suspend fun clusterNew(daysBack: Int = 3): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        // 窗口起点对齐到自然日 00:00：daysBack=4 → [08-11 00:00, now]，覆盖 08-11~当天
        // （避免滑窗 now-3天 漏掉当天早些时候发布的文章，如 08-11 的 Dyna-2 跨号样例）
        val fromSec = dayStartOf(now) - (daysBack - 1) * 86400L
        val seeds = store.loadUnclustered(fromSec, now)
        if (seeds.isEmpty()) {
            Log.i(TAG, "clusterNew: 窗口内无可聚类新文章（未聚类=0）")
            return@withContext Result(0, 0)
        }
        clusterByDay(seeds, onlyUnclustered = true)
    }

    /**
     * 全量重聚类（按天独立）：遍历历史每一天，每天内部独立聚类（不跨天合并）。
     * 手动触发，force 重算当天所有文章（含已聚类），用于改阈值后刷新历史。
     */
    suspend fun reclusterAll(maxAgeDays: Int = 365): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val all = store.loadForClustering(now - maxAgeDays * 86400L, now)
        if (all.isEmpty()) return@withContext Result(0, 0)
        clusterByDay(all, onlyUnclustered = false)
    }

    /** 按自然日分组，对每一天独立跑 clusterDay（不跨天合并）。 */
    private suspend fun clusterByDay(input: List<ClusteringRow>, onlyUnclustered: Boolean): Result {
        val byDay = input.groupBy { dayStartOf(it.publishedAt) }
        var totalClusters = 0
        var totalReassigned = 0
        for ((dayStart, dayRows) in byDay.toSortedMap()) {
            val r = clusterDay(dayStart, dayStart + 86400L, dayRows, onlyUnclustered)
            totalClusters += r.clusters
            totalReassigned += r.reassigned
        }
        Log.i(TAG, "clusterByDay: 处理 ${byDay.size} 天 / ${input.size} 篇 → 簇合计 $totalClusters，回写 $totalReassigned")
        return Result(totalClusters, totalReassigned)
    }

    /**
     * 单天聚类：对 [dayStart, dayEnd) 内文章跑并查集；不跨天合并。
     * @param daySeeds 当天参与聚类的文章（clusterNew=未聚类种子；reclusterAll=当天全部）
     * @param onlyUnclustered true 时只回写「未聚类种子」的分配，已聚类当天文章保持原样（真正增量）
     */
    private suspend fun clusterDay(dayStart: Long, dayEnd: Long, daySeeds: List<ClusteringRow>, onlyUnclustered: Boolean): Result {
        // 加载当天全部（含已聚类，作锚点，让新文章能并入已有当天簇）
        val rows = store.loadForClustering(dayStart, dayEnd)
        if (rows.isEmpty()) return Result(0, 0)
        val seedIds = if (onlyUnclustered) daySeeds.mapTo(HashSet()) { it.contentId } else null

        val n = rows.size
        // 并查集
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // URL 归一化强信号：相同规范 URL 直接同簇（跨号转载高置信）
        val urlIndex = HashMap<String, Int>()
        for (i in 0 until n) {
            val u = normalizeUrl(rows[i].url)
            if (u.isNotBlank()) {
                val seen = urlIndex[u]
                if (seen != null) union(seen, i) else urlIndex[u] = i
            }
        }

        // 候选对生成：标题字符 n-gram（bigram）倒排 + keyword 倒排。
        // 仅靠 keyword 倒排会漏掉「一端 keyword 为空」的同主题对（如两篇 Dyna-2：
        // 其中一篇 keywords=[]，两端无共享 keyword 就永远不被比较）。标题 bigram 倒排
        // 保证高标题重合的文章一定被比较到。每天 ≤~105 篇，倒排后比较量可控。
        val inv = HashMap<String, MutableList<Int>>()
        for (i in 0 until n) {
            for (bg in bigrams(normalizeTitle(rows[i].title))) inv.getOrPut(bg) { mutableListOf() }.add(i)
            for (kw in normKeywords(rows[i].keywords)) inv.getOrPut(kw) { mutableListOf() }.add(i)
        }
        val compared = HashSet<Long>()
        for ((_, idxs) in inv) {
            for (a in 0 until idxs.size) for (b in a + 1 until idxs.size) {
                val i = idxs[a]
                val j = idxs[b]
                val key = if (i < j) i.toLong() * n + j else j.toLong() * n + i
                if (!compared.add(key)) continue
                if (similar(rows[i], rows[j])) union(i, j)
            }
        }

        // 归组
        val groups = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { mutableListOf() }.add(i)

        // 选代表 + 派生标签，构造回写
        val assignments = mutableListOf<ClusterAssignment>()
        for ((_, members) in groups) {
            val rep = members.reduce { best, cur -> if (betterRep(rows[cur], rows[best])) cur else best }
            val repRow = rows[rep]
            val label = deriveLabel(repRow.title)
            for (m in members) {
                val r = rows[m]
                assignments.add(
                ClusterAssignment(
                    contentId = r.contentId,
                    clusterId = repRow.contentId,
                    clusterLabel = label,
                    clusterSize = members.size,
                    isClusterRep = (m == rep),
                )
                )
            }
        }
        // 增量模式：新种子并入「含已聚类旧成员」的簇时，若只回写种子会漏掉代表，
        // 导致代表的 cluster_size 停在过期值（如 1）——这就是「代表相似篇数=1」的 bug。
        // 因此把「被新种子触碰到的整簇」都纳入回写，让代表（及其他旧成员）的 cluster_size
        // 一并刷新为当前真实大小；markClustered 会比对全字段，未变化的行仍会跳过、不触发重同步。
        val toWrite = if (seedIds != null) {
            val touched = assignments.filter { it.contentId in seedIds }.mapTo(HashSet()) { it.clusterId }
            assignments.filter { it.clusterId in touched }
        } else {
            assignments
        }
        store.markClustered(toWrite)
        Log.i(TAG, "clusterDay[$dayStart]: 当天 ${rows.size} 篇 → ${groups.size} 簇，回写 ${toWrite.size}")
        return Result(groups.size, toWrite.size)
    }

    /** 自然日起点（当天 00:00:00 的 epoch 秒） */
    private fun dayStartOf(publishedAtSec: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = publishedAtSec * 1000
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis / 1000
    }

    /** 代表比较：标题越长信息越完整（更高优）；并列取更早发布、再取更小 contentId（确定性） */
    private fun betterRep(a: ClusteringRow, b: ClusteringRow): Boolean {
        if (a.title.length != b.title.length) return a.title.length > b.title.length
        if (a.publishedAt != b.publishedAt) return a.publishedAt < b.publishedAt
        return a.contentId < b.contentId
    }

    /**
     * 相似判定（纯词法）：
     *  - 标题字符 n-gram **重合度**（containment = 交集 / 较小集合）≥ 强阈值 → 直接同簇；
     *  - 否则要求「共享至少一个实体 keyword」且标题重合度 ≥ 弱阈值（Dyna-2 这类长公共前缀 + 同实体命中）。
     * 用重合度而非 Jaccard：长标题共享大段前缀时 Jaccard 会被各自后缀拉低，重合度更稳。
     */
    private fun similar(a: ClusteringRow, b: ClusteringRow): Boolean {
        val na = normalizeTitle(a.title)
        val nb = normalizeTitle(b.title)
        if (na.isBlank() || nb.isBlank()) return false
        val ba = bigrams(na)
        val bb = bigrams(nb)
        val inter = ba.intersect(bb).size
        if (inter == 0) return false
        val titleOverlap = inter.toDouble() / minOf(ba.size, bb.size)
        // 共享实体：keyword 相交，或标题归一化后存在 ≥ LCS_MIN 的共同子串
        // （如 "dyna2"、"100万小时人类视频"）——不依赖 keyword 列是否有值
        val shareKw = normKeywords(a.keywords).intersect(normKeywords(b.keywords)).isNotEmpty()
        val shareSub = longestCommonSubstringLen(na, nb) >= LCS_MIN
        return when {
            titleOverlap >= TITLE_OVERLAP_STRONG -> true
            (shareKw || shareSub) && titleOverlap >= TITLE_OVERLAP_SOFT -> true
            else -> false
        }
    }

    /** keyword 归一化（与标题同一套归一化，保证 "Dyna-2" / "Dyna 2" / "Dyna2" 归并） */
    private fun normKeywords(kws: List<String>): Set<String> =
        kws.mapNotNull { normalizeTitle(it).takeIf { s -> s.isNotBlank() } }.toSet()

    /** 两串最长公共子串长度（归一化标题用，捕捉 "dyna2" 这类共享实体）。O(|a|·|b|)，标题很短可接受。 */
    fun longestCommonSubstringLen(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var best = 0
        for (i in a.indices) {
            for (j in b.indices) {
                var k = 0
                while (i + k < a.length && j + k < b.length && a[i + k] == b[j + k]) {
                    k++
                    if (k > best) best = k
                }
            }
        }
        return best
    }

    companion object {
        private const val TAG = "ClusterEngine"
        private const val TITLE_OVERLAP_STRONG = 0.55
        private const val TITLE_OVERLAP_SOFT = 0.40
        private const val LCS_MIN = 5

        /** 标题归一化：全角→半角、小写、数字归一、去标点空白 */
        fun normalizeTitle(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                val c = when {
                    ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                    ch.code == 0x3000 -> ' '
                    else -> ch
                }
                sb.append(c)
            }
            var t = sb.toString().lowercase()
            t = t.replace("百万", "100万")
                .replace("千万", "1000万")
                .replace("亿", "10000万")
            return t.filter { it.isLetterOrDigit() }
        }

        /** URL 归一化：去协议、转小写、去 query/fragment、去尾部斜杠、去 www. */
        fun normalizeUrl(url: String): String {
            if (url.isBlank()) return ""
            val u = url.lowercase()
                .replace(Regex("^https?://"), "")
                .replace(Regex("[?#].*$"), "")
                .removePrefix("www.")
                .removeSuffix("/")
            return if (u.length > 4 && u.contains(".")) u else ""
        }

        /** 字符 n-gram 集合（默认 bigram），CJK / 混合文本都适用 */
        fun bigrams(s: String): Set<String> {
            if (s.length < 2) return setOf(s)
            val set = LinkedHashSet<String>(s.length)
            for (i in 0 until s.length - 1) set.add(s.substring(i, i + 2))
            return set
        }

        /**
         * 从代表标题派生紧凑「聚类主题」标签：取首个分隔符（：:｜| — - ·）前的内容；
         * 过短则退化为标题前 [LABEL_MAX] 字。用于 Notion select 分组。
         */
        fun deriveLabel(title: String): String {
            if (title.isBlank()) return ""
            val delimiters = listOf("：", ":", "｜", "|", "—", " - ", "-", "·")
            var prefix = title
            for (d in delimiters) {
                val idx = title.indexOf(d)
                if (idx in 1 until title.length) {
                    prefix = title.substring(0, idx)
                    break
                }
            }
            val label = prefix.trim()
            return if (label.length <= 4) title.take(LABEL_MAX).trim() else label.take(LABEL_MAX)
        }

        private const val LABEL_MAX = 30
    }
}

/** 聚类输入投影（轻量，不含 rawJson 等大字段） */
data class ClusteringRow(
    val contentId: String,
    val title: String,
    val summary: String?,
    val keywords: List<String>,
    val url: String,
    val publishedAt: Long,
    val clusterId: String,
    val clusterLabel: String,
)

/** 聚类回写单元 */
data class ClusterAssignment(
    val contentId: String,
    val clusterId: String,
    val clusterLabel: String,
    val clusterSize: Int,
    val isClusterRep: Boolean,
)
