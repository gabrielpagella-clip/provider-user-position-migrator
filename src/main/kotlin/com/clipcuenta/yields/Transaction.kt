package com.clipcuenta.yields

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class Transaction(
    val id: UUID,
    //val batchTransactionId: UUID? = null,
    val walletSavingId: String,
    val merchantId: UUID,
    val type: String, // INTEREST_IN o INTEREST_OUT
    val amount: BigDecimal,
    var status: String = "COMPLETED",
    val createdAt: Instant,
    var updatedAt: Instant,
    var walletAccountId : UUID,
    var transactionReference: String? = null,
    val userId: String
)

/*
const val PK_PREFIX = "WALLET_SAVING#"
const val SK_PREFIX = "ID#"
const val PK_FIELD = "table_pk"
const val SK_FIELD = "table_sk"
const val TRANSACTION_REFERENCE_FIELD = "transaction_reference"
const val MERCHANTID_REFERENCE_FIELD = "merchantid_reference"
// type_status
// entity_type

*/