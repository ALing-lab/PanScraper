package com.tamu.pan123

object BuildConfigInfo { const val VERSION = "0.3.6" }

/** 应用信息（关于页 / 检查更新共用）。 */
object AppInfo {
    const val NAME = "PanScraper"
    const val NAME_EN = "PanScraper"
    const val AUTHOR = "ALing-lab"
    const val QQ = "65646589"

    /**
     * GitHub 仓库（owner/repo）。仓库地址只改这一处，关于页链接、检查更新、手动更新都会跟着变。
     */
    const val GITHUB_REPO = "ALing-lab/PanScraper"
    const val GITHUB_URL = "https://github.com/$GITHUB_REPO"
    const val RELEASES_URL = "$GITHUB_URL/releases"
}
