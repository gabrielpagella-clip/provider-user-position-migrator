package com.clipcuenta.yields

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

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
    val nowIso = updatedAt.toString()

    return buildMap {
        put("table_pk", avS("WALLET_SAVING#$walletSavingId"))
        put("table_sk", avS("ID#$id"))
        put("created_at", avS(createdAt.toString()))
        put("entity_type", avS("TRANSACTION"))
        put("status", avS(status))
        put("wallet_account_id", avS(walletAccountId.toString()))
        put("updated_at", avS(nowIso))
        put("merchant_id", avS(merchantId.toString()))
        put("user_id", avS(userId))
        put("type_status", avS("$type#$status"))
        put("amount", avN(amount))
        put("id", avS(id.toString()))
        transactionReference?.let { ref ->
            put("transaction_reference", avS(ref))
            put("merchantid_reference", avS("${merchantId}#$ref"))
        }
        put("type", avS(type))
        put("wallet_saving_id", avS(walletSavingId))
    }
}

private fun Transaction.toPutItemRequest(tableName: String): PutItemRequest =
    PutItemRequest.builder()
        .tableName(tableName)
        .item(this.toDynamoItem())
        .conditionExpression("attribute_not_exists(table_pk) AND attribute_not_exists(table_sk)")
        .build()

val tableName = "yields-transaction-api-dev-transactions"

fun main() {
    val userId = "d5d45294-6be0-4fe2-8190-00c70b8291c8"
    val walletSavingId = "f9cd28ad-b5cb-46ed-aadd-bceddbf785c6"
    val merchantId = UUID.fromString("5399b8c1-d949-404e-99c3-25a7b8e82486")
    val walletAccountId = UUID.fromString("bb85a2a4-8d52-4e55-b0e8-f79209fe7c49")

    val region = Region.US_WEST_2
    val providerName = "KUSPIT"

    val client = DynamoDbClient.builder()
        .region(region)
        .build()

    val pk = "USER#$userId#PROVIDER_NAME#$providerName"

    println("Table: $tableName")
    println("Region: $region")
    println("PK: $pk")
    println("----")

    var lastKey: Map<String, AttributeValue>? = null
    var count = 0

    do {
        val req = QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression("table_pk = :pk")
            .filterExpression("#st = :completed")
            .expressionAttributeNames(
                mapOf(
                    "#st" to "status"
                )
            )
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

            val interestIn = avNToBigDecimal(item, "interest_in")
            val interestOut = avNToBigDecimal(item, "interest_out")
            val createdAt = avSToInstant(item, "created_at")

            println("[$count] Processing position interests: in=$interestIn out=$interestOut")

            val transactions = mutableListOf<Transaction>()

            if (interestIn > BigDecimal.ZERO) {
                transactions += Transaction(
                    id = UUID.randomUUID(),
                    walletSavingId = walletSavingId,
                    merchantId = merchantId,
                    type = "INTEREST_IN",
                    amount = interestIn,
                    createdAt = createdAt,
                    updatedAt = Instant.now(),
                    walletAccountId = walletAccountId,
                    userId = userId,
                    transactionReference = generateTransactionReference(client, merchantId)
                )
            }

            if (interestOut > BigDecimal.ZERO) {
                transactions += Transaction(
                    id = UUID.randomUUID(),
                    walletSavingId = walletSavingId,
                    merchantId = merchantId,
                    type = "INTEREST_OUT",
                    amount = interestOut,
                    createdAt = createdAt,
                    updatedAt = Instant.now(),
                    walletAccountId = walletAccountId,
                    userId = userId,
                    transactionReference = generateTransactionReference(client, merchantId)
                )
            }


            transactions.forEach { tx ->
                println("---- TRANSACTION (DOMAIN) ----")
                println(tx)

                val putRequest = tx.toPutItemRequest(tableName)

                println("---- PUT ITEM REQUEST (DRY RUN) ----")
                println("tableName=${putRequest.tableName()}")
                println("item=${putRequest.item()}")
                println("------------------------------------")

                try {
                    //client.putItem(putRequest)
                    println("✅ Transaction created successfully")
                } catch (e: Exception) {
                    System.err.println("❌ Failed to write transaction: ${e.message}")
                    // Continue processing other transactions
                }

            }

            println("----------------------")
        }

        lastKey = resp.lastEvaluatedKey().takeIf { it != null && it.isNotEmpty() }
    } while (lastKey != null)

    println("Total items: $count")
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
