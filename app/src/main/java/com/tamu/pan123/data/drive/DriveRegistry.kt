package com.tamu.pan123.data.drive

import com.tamu.pan123.data.pan.Pan123Drive

/** 账号 → CloudDrive 实例。测试可通过 [override] 注入 Fake。 */
object DriveRegistry {
    @Volatile var override: CloudDrive? = null

    fun forAccount(id: String?): CloudDrive {
        override?.let { return it }
        if (id.isNullOrBlank()) return create(AccountStore.active())
        // 计划指定了账号却找不到：绝不回退到别的网盘执行
        val acct = AccountStore.get(id) ?: error("这个计划所属的网盘账号已删除，请重新扫描")
        return create(acct)
    }

    fun active(): CloudDrive = forAccount(AccountStore.activeId.value)

    fun create(acct: DriveAccount): CloudDrive = when (acct.provider) {
        ProviderKind.P123 -> Pan123Drive(acct.id)
        ProviderKind.WEBDAV -> com.tamu.pan123.data.webdav.WebDavDrive.from(acct)
        ProviderKind.GUANGYA -> com.tamu.pan123.data.guangya.GuangyaDrive.from(acct)
        ProviderKind.P115 -> com.tamu.pan123.data.p115.Drive115.from(acct)
        // 其它未实现，先返回不发请求的占位实现
        else -> PendingDrive(acct.provider, acct.id)
    }
}
