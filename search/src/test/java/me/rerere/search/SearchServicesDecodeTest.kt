package me.rerere.search

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

/**
 * 锁定 SearchServiceOptions 的两条健壮性修复：
 *
 * 1) decodeListSafely —— "诚实解码"语义
 *    - null（全新用户）→ 默认一个 BingLocalOptions
 *    - 正常成功 → 保留各子类运行时类型与顺序
 *    - 坏数据（缺判别符 / 未知供应商 / 全限定类名老判别符 / 非法 JSON / 空数组）→ 清空，
 *      不再伪装成单个 Bing（这正是"无论选什么都只剩一张 Bing 卡"的根因，本测试防回归）。
 *
 * 2) create —— 显式 when 工厂替代反射 primaryConstructor.callBy
 *    - 对 TYPES 中每个类型造出正确实例
 *    - 未知类型显式报错（而不是静默造 Bing）
 *
 * 复用与 App 端 JsonInstant 的 decode 行为一致的 Json（ignoreUnknownKeys=true）。
 */
class SearchServicesDecodeTest {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun uuid(n: Int) = "00000000-0000-0000-0000-%012d".format(n)

    // region decodeListSafely

    @Test
    fun nullRaw_returnsDefaultSingleBing_forNewUser() {
        val out = SearchServiceOptions.decodeListSafely(null)
        assertEquals(1, out.size)
        assertTrue(out[0] is SearchServiceOptions.BingLocalOptions)
        assertEquals("Bing", out[0].displayName)
    }

    @Test
    fun validMixed_preservesRuntimeTypesAndOrder() {
        val raw: List<SearchServiceOptions> = listOf(
            SearchServiceOptions.TavilyOptions(apiKey = "k", depth = "basic"),
            SearchServiceOptions.BingLocalOptions(),
            SearchServiceOptions.ZhipuOptions(apiKey = "z"),
        )
        val encoded = json.encodeToString(raw)
        val out = SearchServiceOptions.decodeListSafely(encoded)
        assertEquals(3, out.size)
        assertTrue(out[0] is SearchServiceOptions.TavilyOptions)
        assertTrue(out[1] is SearchServiceOptions.BingLocalOptions)
        assertTrue(out[2] is SearchServiceOptions.ZhipuOptions)
        assertEquals("Tavily", out[0].displayName)
        assertEquals("Bing", out[1].displayName)
        assertEquals("智谱", out[2].displayName)
    }

    @Test
    fun emptyArray_returnsEmpty_notFakedBing() {
        val out = SearchServiceOptions.decodeListSafely("[]")
        assertTrue("空数组应是空列表，而非伪装成 Bing", out.isEmpty())
    }

    @Test
    fun missingDiscriminator_returnsEmpty_notFakedBing() {
        val raw = """[{"id":"${uuid(1)}","apiKey":"k"}]"""
        val out = SearchServiceOptions.decodeListSafely(raw)
        assertTrue("缺判别符应清空，而非伪装成 Bing", out.isEmpty())
    }

    @Test
    fun unknownVendorDiscriminator_returnsEmpty_notFakedBing() {
        val raw = """[{"type":"totally_unknown_vendor","id":"${uuid(1)}"}]"""
        val out = SearchServiceOptions.decodeListSafely(raw)
        assertTrue("未知供应商判别符应清空", out.isEmpty())
    }

    @Test
    fun fullyQualifiedClassNameDiscriminator_returnsEmpty_notFakedBing() {
        val raw = """[{"type":"me.rerere.search.SearchServiceOptions.TavilyOptions","id":"${uuid(2)}","apiKey":"k"}]"""
        val out = SearchServiceOptions.decodeListSafely(raw)
        assertTrue("全限定类名老判别符应清空", out.isEmpty())
    }

    @Test
    fun malformedJson_returnsEmpty_notFakedBing() {
        val out = SearchServiceOptions.decodeListSafely("}{not json")
        assertTrue("非法 JSON 应清空", out.isEmpty())
    }

    @Test
    fun validSingleBing_preserved() {
        val raw = """[{"type":"bing_local","id":"${uuid(7)}"}]"""
        val out = SearchServiceOptions.decodeListSafely(raw)
        assertEquals(1, out.size)
        assertTrue(out[0] is SearchServiceOptions.BingLocalOptions)
        assertEquals("Bing", out[0].displayName)
    }

    @Test
    fun currentUserProviders_notFakedAsBing() {
        val raw: List<SearchServiceOptions> = listOf(
            SearchServiceOptions.TavilyOptions(apiKey = "k"),
            SearchServiceOptions.GrokOptions(apiKey = "g"),
            SearchServiceOptions.MetasoOptions(apiKey = "m"),
        )
        val encoded = json.encodeToString(raw)
        val out = SearchServiceOptions.decodeListSafely(encoded)
        assertEquals(3, out.size)
        assertTrue(out[0] is SearchServiceOptions.TavilyOptions)
        assertTrue(out[1] is SearchServiceOptions.GrokOptions)
        assertTrue(out[2] is SearchServiceOptions.MetasoOptions)
        out.forEach { assertNotEquals("Bing", it.displayName) }
    }

    // endregion

    // region create 工厂

    @Test
    fun create_eachType_producesMatchingInstance_noReflection() {
        for (type in SearchServiceOptions.TYPES.keys) {
            val inst = SearchServiceOptions.create(type)
            assertEquals("create($type) 造错类型", type, inst::class)
            assertEquals(SearchServiceOptions.TYPES[type], inst.displayName)
        }
    }

    @Test
    fun create_unknownType_throws_notFake() {
        // sealed 类自身的 KClass 不在 TYPES 内 —— 必须显式报错，而不是静默造 Bing
        try {
            SearchServiceOptions.create(SearchServiceOptions::class)
            fail("未知类型应抛 IllegalStateException，却造出了实例")
        } catch (e: IllegalStateException) {
            // 期望：显式报错
        }
    }

    // endregion
}
