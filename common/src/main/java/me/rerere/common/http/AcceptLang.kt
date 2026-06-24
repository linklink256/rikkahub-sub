package me.rerere.common.http

import java.util.Locale

/**
 * 构建 Accept-Language 头值的实用类。
 *
 * 主要特性：
 * 1) 支持 Android 系统语言获取。
 * 2) 支持将 "zh-CN" 展开为 ["zh-CN", "zh"]（可配置）。
 * 3) 去重并按优先级降权生成 q 参数（IETF RFC 7231）。
 * 4) 结果形如： "zh-CN, zh;q=0.9, en-US;q=0.8, en;q=0.7"
 */
class AcceptLanguageBuilder private constructor(
    private val localesInPreference: List<Locale>,
    private val options: Options
) {

    data class Options(
        /** 参与生成的语言标签最多个数（去重后再截断）。*/
        val maxLanguages: Int = 6,
        /** 从 1.0 起，每个后续条目的 q 递减步长（0 < step <= 1）。*/
        val qStep: Double = 0.1,
        /** q 的下限（包含）；若递减到更低则夹到该下限。*/
        val minQ: Double = 0.1,
        /** 是否为地区化语言添加“通用语言码”，如 "zh-CN" 追加 "zh"。*/
        val includeGenericLanguage: Boolean = true,
        /** 是否对标签去重（保持首次出现的顺序）。*/
        val deduplicate: Boolean = true
    )

    companion object {
        /**
         * 从 Android 系统环境创建。
         * @param context 建议传入应用或当前上下文，以获取用户“应用内语言”/系统语言首选列表
         */
        fun fromAndroid(context: android.content.Context, options: Options = Options()): AcceptLanguageBuilder {
            val locales = systemLocalesAndroid(context)
            return AcceptLanguageBuilder(locales, options)
        }

        // Android 的系统 Locale 列表获取（minSdk 26，直接使用 LocaleList API）
        private fun systemLocalesAndroid(context: android.content.Context): List<Locale> {
            val list = context.resources.configuration.locales
            return (0 until list.size()).map { idx -> list[idx] }
        }
    }

    /** 生成最终的 Accept-Language 头值（不包含 "Accept-Language:" 前缀）。*/
    fun build(): String {
        // 1) 先将 Locale 转成语言标签，并按需展开“通用语言码”
        val tags = mutableListOf<String>()
        for (loc in localesInPreference) {
            val full = loc.toLanguageTag()
            if (full.isNotBlank()) tags += full

            if (options.includeGenericLanguage) {
                val idx = full.indexOf('-')
                if (idx > 0) {
                    val head = full.substring(0, idx)
                    if (head.isNotBlank()) tags += head
                }
            }
        }

        // 2) 去重（保持首次出现顺序）
        val distinct = if (options.deduplicate) tags.distinct() else tags

        // 3) 截断
        val limited = distinct.take(options.maxLanguages.coerceAtLeast(1))

        // 4) 赋予 q 值：第一个 1.0 不写 q，其余按步长递减到 minQ
        val parts = mutableListOf<String>()
        var q = 1.0
        for ((i, tag) in limited.withIndex()) {
            if (i == 0) {
                parts += tag
            } else {
                q = (1.0 - i * options.qStep).coerceAtLeast(options.minQ)
                parts += "$tag;q=${formatQ(q)}"
            }
        }

        return parts.joinToString(separator = ", ")
    }

    /** q 值格式：最多保留 3 位小数，去掉多余 0 与小数点。*/
    private fun formatQ(value: Double): String {
        val s = String.format(java.util.Locale.ROOT, "%.3f", value)
        return s.trimEnd('0').trimEnd('.')
    }
}