package com.serverspulse.agent.api.dto

/**
 * Payload sent to `POST /api/v1/ingest/spark-result` after a Spark profile completes.
 */
data class SparkResultPayload(
    val incidentId: String,
    val sparkUrl: String
)
