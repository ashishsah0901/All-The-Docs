package com.ashish.allthedocs.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scenarios")
data class ScenarioEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val countryCode: String?,
    val origin: String,
    val requirementsJson: String,
    val assumptionsJson: String,
    val answersJson: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "scenario_drafts")
data class ScenarioDraftEntity(
    @PrimaryKey val id: String,
    val description: String,
    val analysisJson: String?,
    val answersJson: String,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "vault_documents")
data class VaultDocumentEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val holderName: String?,
    val issueDate: String?,
    val expiryDate: String?,
    val mimeType: String,
    val originalFileName: String,
    val storedFileName: String,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    val verified: Boolean,
    val verificationSummary: String?,
    val issuesJson: String,
    val issuer: String? = null,
    val source: String? = null,
    val signatureStatus: String? = null,
    val signedBy: String? = null,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
