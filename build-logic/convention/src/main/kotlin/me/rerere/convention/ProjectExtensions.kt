package me.rerere.convention

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

private val CORE_OPT_INS = listOf(
    "kotlin.uuid.ExperimentalUuidApi",
    "kotlin.time.ExperimentalTime",
)

private val COMPOSE_OPT_INS = listOf(
    "androidx.compose.material3.ExperimentalMaterial3Api",
    "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
    "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
    "androidx.compose.animation.ExperimentalAnimationApi",
    "androidx.compose.animation.ExperimentalSharedTransitionApi",
    "androidx.compose.foundation.ExperimentalFoundationApi",
    "androidx.compose.foundation.layout.ExperimentalLayoutApi",
)

private val COROUTINES_OPT_IN = "kotlinx.coroutines.ExperimentalCoroutinesApi"

/** 配置核心 opt-in（uuid, time）。非 compose library 用这个。 */
fun Project.configureCoreOptIns() {
    tasks.withType(KotlinCompile::class.java).configureEach {
        compilerOptions.optIn.addAll(CORE_OPT_INS)
    }
}

/** 配置 compose + core + coroutines opt-in。compose library/application 用这个。 */
fun Project.configureComposeOptIns() {
    tasks.withType(KotlinCompile::class.java).configureEach {
        compilerOptions.optIn.addAll(CORE_OPT_INS + COMPOSE_OPT_INS + COROUTINES_OPT_IN)
    }
}
