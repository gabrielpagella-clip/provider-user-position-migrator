package com.clipcuenta.yields

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.Properties
import java.util.UUID

private fun loadUsersFromCsv(): List<UserConfig> {
    val fileName="dataprod13.csv"
    println("File: $fileName")
    val csvFile = File(fileName)
    if (!csvFile.exists()) {
        throw IllegalStateException("file not found")
    }
    val lines = csvFile.readLines()
    if (lines.size < 2) {
        throw IllegalStateException("file is empty or has no data rows")
    }
    return lines.drop(1).map { line ->
        val cols = line.split(",")
        UserConfig(
            userId = cols[0].trim(),
            merchantId = UUID.fromString(cols[1].trim()),
            walletSavingId = cols[2].trim(),
            walletAccountId = UUID.fromString(cols[3].trim())
        )
    }
}

private fun loadConfig(): Properties {
    val props = Properties()
    val configFile = File("config.properties")
    if (!configFile.exists()) {
        throw IllegalStateException("config.properties file not found")
    }
    configFile.inputStream().use { props.load(it) }
    return props
}

private val config = loadConfig()

val tableName = config.getProperty("table_name")
    ?: throw IllegalStateException("table_name not found in config.properties")

val users = loadUsersFromCsv()

private fun avS(v: String): AttributeValue = AttributeValue.builder().s(v).build()

private fun avNToBigDecimal(item: Map<String, AttributeValue>, key: String): BigDecimal =
    item[key]?.n()?.let { BigDecimal(it) } ?: BigDecimal.ZERO

private fun avN(value: BigDecimal): AttributeValue =
    AttributeValue.builder().n(value.toPlainString()).build()

private fun avSToInstant(item: Map<String, AttributeValue>, key: String): Instant =
    item[key]?.s()?.let { Instant.parse(it) } ?: Instant.now()

private fun avSToString(item: Map<String, AttributeValue>, key: String): String? =
    item[key]?.s()

private fun Transaction.toDynamoItem(): Map<String, AttributeValue> {
    val tx = this
    val nowIso = tx.updatedAt.toString()

    return buildMap {
        put("table_pk", avS("WALLET_SAVING#${tx.walletSavingId}"))
        put("table_sk", avS("ID#${tx.id}"))
        put("created_at", avS(tx.createdAt.toString()))
        put("entity_type", avS("TRANSACTION"))
        put("status", avS(tx.status))
        put("wallet_account_id", avS(tx.walletAccountId.toString()))
        put("updated_at", avS(nowIso))
        put("merchant_id", avS(tx.merchantId.toString()))
        put("user_id", avS(tx.userId))
        put("type_status", avS("${tx.type}#${tx.status}"))
        put("amount", avN(tx.amount))
        put("id", avS(tx.id.toString()))
        tx.transactionReference?.let { ref ->
            put("transaction_reference", avS(ref))
            put("merchantid_reference", avS("${tx.merchantId}#$ref"))
        }
        put("type", avS(tx.type))
        put("wallet_saving_id", avS(tx.walletSavingId))
    }
}

private fun Transaction.toPutItemRequest(tableName: String): PutItemRequest =
    PutItemRequest.builder()
        .tableName(tableName)
        .item(this.toDynamoItem())
        .conditionExpression("attribute_not_exists(table_pk) AND attribute_not_exists(table_sk)")
        .build()

fun main() {

    val region = Region.US_WEST_2
    val providerName = "KUSPIT"

    val client = DynamoDbClient.builder()
        .region(region)
        .build()

    println("Table: $tableName")
    println("Region: $region")
    println("Users to process: ${users.size}")
    println("----")

    var totalPositionsProcessed = 0
    var totalTransactionsCreated = 0
    var totalTransactionsSkipped = 0
    var totalTransactionsFailed = 0

    for (userConfig in users) {
        println("=== Processing user: ${userConfig.userId} ===")

        val pk = "USER#${userConfig.userId}#PROVIDER_NAME#$providerName"
        println("PK: $pk")

        var lastKey: Map<String, AttributeValue>? = null
        var count = 0
        var transactionsCreated = 0
        var transactionsSkipped = 0
        var transactionsFailed = 0

        do {
            val req = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("table_pk = :pk")
                .filterExpression("#st = :completed")
                .expressionAttributeNames(mapOf("#st" to "status"))
                .expressionAttributeValues(
                    mapOf(
                        ":pk" to avS(pk),
                        ":completed" to avS("COMPLETED")
                    )
                )
                .exclusiveStartKey(lastKey)
                .build()

            val resp = client.query(req)
            println("Page => returnedCount=${resp.count()} scannedCount=${resp.scannedCount()} hasLastKey=${resp.lastEvaluatedKey()?.isNotEmpty() == true}")

            for (item in resp.items()) {
                count++

                val positionPk = avSToString(item, "table_pk") ?: continue
                val positionSk = avSToString(item, "table_sk") ?: continue

                val existingReferenceId = avSToString(item, "reference_id")
                if (existingReferenceId != null) {
                    println("⚠️  Position already migrated (reference_id=$existingReferenceId), skipping...")
                    transactionsSkipped++
                    continue
                }

                val interestIn = avNToBigDecimal(item, "interest_in")
                val interestOut = avNToBigDecimal(item, "interest_out")
                val createdAt = avSToInstant(item, "created_at")
                val updatedAt = avSToInstant(item, "updated_at")

                println("[$count] Processing position interests (sk=$positionSk): in=$interestIn out=$interestOut")

                val transactions = mutableListOf<Transaction>()

                if (interestIn > BigDecimal.ZERO) {
                    val txReference = generateTransactionReference(client, userConfig.merchantId)
                    if (txReference == null) {
                        System.err.println("❌ Could not generate transaction reference for INTEREST_IN, skipping position...")
                        transactionsFailed++
                        continue
                    }
                    transactions += Transaction(
                        id = UUID.randomUUID(),
                        walletSavingId = userConfig.walletSavingId,
                        merchantId = userConfig.merchantId,
                        type = "INTEREST_IN",
                        amount = interestIn,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        walletAccountId = userConfig.walletAccountId,
                        userId = userConfig.userId,
                        transactionReference = txReference
                    )
                }

                if (interestOut > BigDecimal.ZERO) {
                    val txReference = generateTransactionReference(client, userConfig.merchantId)
                    if (txReference == null) {
                        System.err.println("❌ Could not generate transaction reference for INTEREST_OUT, skipping position...")
                        transactionsFailed++
                        continue
                    }
                    transactions += Transaction(
                        id = UUID.randomUUID(),
                        walletSavingId = userConfig.walletSavingId,
                        merchantId = userConfig.merchantId,
                        type = "INTEREST_OUT",
                        amount = interestOut,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        walletAccountId = userConfig.walletAccountId,
                        userId = userConfig.userId,
                        transactionReference = txReference
                    )
                }

                transactions.forEach { tx ->
//                    println("---- TRANSACTION (DOMAIN) ----")
//                    println(tx)

                    if (transactionExists(client, tx.walletSavingId, tx.createdAt, tx.type)) {
                        println("⚠️  Transaction already exists (created_at=${tx.createdAt}, type=${tx.type}), skipping...")
                        transactionsSkipped++
                        return@forEach
                    }

                    val putRequest = tx.toPutItemRequest(tableName)

//                    println("---- PUT ITEM REQUEST ----")
//                    println("tableName=${putRequest.tableName()}")
//                    println("item=${putRequest.item()}")
//                    println("------------------------------------")
//
                    try {
                        client.putItem(putRequest)
                        println("✅ Transaction created successfully")
                        transactionsCreated++
                        updateProviderUserPositionReferenceId(client, positionPk, positionSk, tx.id)
                        println("✅ Provider user position updated with reference_id=${tx.id}")
                    } catch (e: Exception) {
                        System.err.println("❌ Failed to write transaction: ${e.message}")
                        transactionsFailed++
                    }
                }

                println("----------------------")
            }

            lastKey = resp.lastEvaluatedKey().takeIf { it != null && it.isNotEmpty() }
        } while (lastKey != null)

        println("User ${userConfig.userId} - positions: $count, created: $transactionsCreated, skipped: $transactionsSkipped, failed: $transactionsFailed")
        println("----")
        println("Waiting 2 seconds...")
        Thread.sleep(2000)

        totalPositionsProcessed += count
        totalTransactionsCreated += transactionsCreated
        totalTransactionsSkipped += transactionsSkipped
        totalTransactionsFailed += transactionsFailed
    }

    println("=== TOTAL ===")
    println("Total positions processed: $totalPositionsProcessed")
    println("Transactions created: $totalTransactionsCreated")
    println("Transactions skipped: $totalTransactionsSkipped")
    println("Transactions failed: $totalTransactionsFailed")
    client.close()
}


fun generateTransactionReference(
    dynamoDbClient: DynamoDbClient,
    merchantId: UUID
): String? {
    val TRANSACTION_REFERENCE_LENGTH = 7
    val ATTEMPTS = 5

    for (i in 0 until ATTEMPTS) {
        val generatedReference = RandomGenerator.fromCharset(Charsets.ALPHANUMERIC, TRANSACTION_REFERENCE_LENGTH)

        val exists = existsEntityByMerchantIdAndTransactionReference(dynamoDbClient, merchantId, generatedReference)

        if (!exists) {
            return generatedReference
        }
    }
    return null
}

val MERCHANTID_REFERENCE_FIELD = "merchantid_reference"
val MERCHANTID_TRANSACTIONREFERENCE_ENTITYTYPE_GSI = "merchantid-transactionreference-entitytype-gsi"
val MERCHANTID_TRANSACTIONREFERENCE_ENTITYTYPE_GSI_CONDITION_EXPRESSION_PK_ONLY =
    "$MERCHANTID_REFERENCE_FIELD = :${MERCHANTID_REFERENCE_FIELD}"



fun existsEntityByMerchantIdAndTransactionReference(dynamoDbClient: DynamoDbClient, merchantId: UUID, transactionReference: String): Boolean {
    val pkValue = "$merchantId#$transactionReference"
    val expressionAttributeValues = mapOf<String, AttributeValue>(
        ":${MERCHANTID_REFERENCE_FIELD}" to AttributeValue.builder().s(pkValue).build()
    )
    val keyConditionExpression = MERCHANTID_TRANSACTIONREFERENCE_ENTITYTYPE_GSI_CONDITION_EXPRESSION_PK_ONLY

    val request =
        QueryRequest.builder()
            .tableName(tableName)
            .indexName(MERCHANTID_TRANSACTIONREFERENCE_ENTITYTYPE_GSI)
            .limit(1)
            .keyConditionExpression(keyConditionExpression)
            .expressionAttributeValues(expressionAttributeValues)
            .build()

    val queryResponse = dynamoDbClient.query(request)
    return queryResponse.items().isNotEmpty()
}

fun updateProviderUserPositionReferenceId(
    dynamoDbClient: DynamoDbClient,
    positionPk: String,
    positionSk: String,
    transactionId: UUID
) {
    val request = UpdateItemRequest.builder()
        .tableName(tableName)
        .key(mapOf(
            "table_pk" to avS(positionPk),
            "table_sk" to avS(positionSk)
        ))
        .updateExpression("SET reference_id = :transactionId")
        .expressionAttributeValues(mapOf(
            ":transactionId" to avS(transactionId.toString())
        ))
        .build()

    dynamoDbClient.updateItem(request)
}

fun transactionExists(
    dynamoDbClient: DynamoDbClient,
    walletSavingId: String,
    createdAt: Instant,
    type: String
): Boolean {
    val request = QueryRequest.builder()
        .tableName(tableName)
        .indexName("createdat-lsi")
        .keyConditionExpression("table_pk = :pk AND created_at = :createdAt")
        .filterExpression("#type = :type")
        .expressionAttributeNames(mapOf("#type" to "type"))
        .expressionAttributeValues(mapOf(
            ":pk" to avS("WALLET_SAVING#$walletSavingId"),
            ":createdAt" to avS(createdAt.toString()),
            ":type" to avS(type)
        ))
        .limit(1)
        .build()

    val queryResponse = dynamoDbClient.query(request)
    return queryResponse.items().isNotEmpty()
}
