package com.anytouch.app.template

/**
 * 预制模板注册表（S5-a，军令 R3-1 三模板 + 老板 S5-R7 验收分档）。
 *
 * 本文件 **android-free**：只登记"有哪些模板、资产在哪、要不要用户先登录"，
 * 读资产在 [TemplateLoader]（要 Context）。判据（词表/形状）不在这里写——
 * 装载走的仍是 `RecorderStore.acceptModelActions` 那唯一落账口（RUNNING 档 + 词表档），
 * 本表若与资产文件脱钩，由 JVM 用例 `PresetTemplatesTest` 双向对拍锁死。
 */

/** 模板的验证档位——诚实标注，UI 与证据同一份口径（老板 S5-R7）。 */
enum class TemplateVerification {
    /** 业务步序已在验收靶机（AVD 原生 GMS 镜像）设备上整链实证。 */
    DEVICE_PROVEN_ON_AVD,

    /** 仅装载面+结构判据（JVM 形状锁 + 能落账）；业务步未在真登录态实证——不许按"可商用绿"宣传。 */
    STRUCTURAL_ONLY,
}

data class PresetTemplate(
    val id: String,
    /** UI 展示名（与军令 P0-4 清单逐字同义） */
    val label: String,
    /** assets 相对路径 */
    val assetPath: String,
    /** 使用前置：需用户先在该 App 内自行登录（本工具不做登录、不碰账号凭证——S5-R7） */
    val requiresUserSignIn: Boolean,
    val verification: TemplateVerification,
)

object PresetTemplateLibrary {

    val all: List<PresetTemplate> = listOf(
        PresetTemplate(
            id = "photos_cleanup",
            label = "Photo cleanup",
            assetPath = "templates/photos_cleanup.json",
            requiresUserSignIn = false,
            // 实证背书在盘：evidence/S5/photos_cleanup-device-proven-avd.md（avd34+avd35 各 5 轮全链绿，
            // 回执 ok=9/9 + 磁盘双真空）；`PresetTemplatesTest` 锁要求该背书件先于本标记存在
            verification = TemplateVerification.DEVICE_PROVEN_ON_AVD,
        ),
        PresetTemplate(
            id = "gmail_cleanup",
            label = "Gmail cleanup",
            assetPath = "templates/gmail_cleanup.json",
            requiresUserSignIn = true,
            verification = TemplateVerification.STRUCTURAL_ONLY,
        ),
        PresetTemplate(
            id = "discord_checkin",
            label = "Discord check-in",
            assetPath = "templates/discord_checkin.json",
            requiresUserSignIn = true,
            verification = TemplateVerification.STRUCTURAL_ONLY,
        ),
    )

    fun byId(id: String): PresetTemplate? = all.firstOrNull { it.id == id }

    /** 屏上与上架文案同一口径（S5-R7）：需要登录的模板点名说清，不碰凭证一句话在册。 */
    fun signInHint(): String {
        val names = all.filter { it.requiresUserSignIn }.joinToString(" and ") { it.label }
        return "$names require you to sign in to the app yourself first. Anytouch only automates the " +
            "interface after you are signed in; it never performs logins and never reads or stores " +
            "your account credentials."
    }
}
