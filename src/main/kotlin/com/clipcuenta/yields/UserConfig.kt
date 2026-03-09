package com.clipcuenta.yields

import java.util.UUID

data class UserConfig(
    val userId: String,
    val merchantId: UUID,
    val walletSavingId: String,
    val walletAccountId: UUID
)
