#![forbid(unsafe_code)]

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{collections::BTreeMap, error::Error, fmt};
use uuid::Uuid;

pub type Iso8601 = String;
pub type SemVer = String;
pub type Uri = String;
pub type Metadata = BTreeMap<String, Value>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RiskLevel {
    R0,
    R1,
    R2,
    R3,
    R4,
    R5,
    R6,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TrustClass {
    Core,
    Official,
    Verified,
    Known,
    Community,
    Unknown,
    Suspicious,
    Revoked,
    Blocked,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DataClass {
    Public,
    Internal,
    Personal,
    Confidential,
    Secret,
    RootSecret,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PermissionDecision {
    Deny,
    Ask,
    AllowOnce,
    AllowSession,
    AllowWorkspace,
    AllowAlways,
    AutoPolicy,
    SandboxOnly,
    LockedAllow,
    LockedDeny,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ObjectRef {
    pub id: Uuid,
    #[serde(rename = "type")]
    pub object_type: String,
    pub version: Option<SemVer>,
}

impl ObjectRef {
    pub fn new(id: Uuid, object_type: impl Into<String>, version: Option<SemVer>) -> Self {
        Self {
            id,
            object_type: object_type.into(),
            version,
        }
    }
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_nil("object_ref.id", self.id)?;
        validate_non_empty("object_ref.type", &self.object_type)?;
        if let Some(version) = &self.version {
            validate_non_empty("object_ref.version", version)?;
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
pub struct ResourceBudget {
    pub cpu_percent: Option<f32>,
    pub memory_bytes: Option<u64>,
    pub gpu_memory_bytes: Option<u64>,
    pub gpu_utilization: Option<f32>,
    pub npu_utilization: Option<f32>,
    pub storage_bytes: Option<u64>,
    pub network_upload_bytes: Option<u64>,
    pub network_download_bytes: Option<u64>,
    pub model_input_tokens: Option<u64>,
    pub model_output_tokens: Option<u64>,
    pub max_wall_time_ms: Option<u64>,
    pub max_actions: Option<u64>,
}

impl ResourceBudget {
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        for (field, value) in [
            ("resource_budget.cpu_percent", self.cpu_percent),
            ("resource_budget.gpu_utilization", self.gpu_utilization),
            ("resource_budget.npu_utilization", self.npu_utilization),
        ] {
            if let Some(value) = value
                && !(0.0..=100.0).contains(&value)
            {
                return Err(CoreValidationError::InvalidRange(field));
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
pub struct CostBudget {
    pub currency: String,
    pub maximum_total: Option<f64>,
    pub maximum_per_hour: Option<f64>,
    pub maximum_per_task: Option<f64>,
    pub warn_at_percent: Option<f32>,
}

impl CostBudget {
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_empty("cost_budget.currency", &self.currency)?;
        for (field, value) in [
            ("cost_budget.maximum_total", self.maximum_total),
            ("cost_budget.maximum_per_hour", self.maximum_per_hour),
            ("cost_budget.maximum_per_task", self.maximum_per_task),
        ] {
            if let Some(value) = value
                && value < 0.0
            {
                return Err(CoreValidationError::InvalidRange(field));
            }
        }
        if let Some(value) = self.warn_at_percent
            && !(0.0..=100.0).contains(&value)
        {
            return Err(CoreValidationError::InvalidRange(
                "cost_budget.warn_at_percent",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HashRecord {
    pub algorithm: String,
    pub value: String,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SignatureRecord {
    pub algorithm: String,
    pub signer: String,
    pub value: String,
    pub signed_at: Iso8601,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PublisherIdentity {
    pub id: String,
    pub name: String,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TransformationRecord {
    pub kind: String,
    pub actor: Option<String>,
    pub timestamp: Iso8601,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExternalProvenanceReference {
    pub standard: String,
    pub reference: Uri,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProvenanceRecord {
    pub origin: String,
    pub publisher: Option<PublisherIdentity>,
    pub created_at: Option<Iso8601>,
    pub imported_at: Iso8601,
    pub parent_objects: Vec<ObjectRef>,
    pub transformations: Vec<TransformationRecord>,
    pub hashes: Vec<HashRecord>,
    pub signatures: Vec<SignatureRecord>,
    pub external_provenance: Vec<ExternalProvenanceReference>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CoreValidationError {
    EmptyField(&'static str),
    NilId(&'static str),
    InvalidRange(&'static str),
    InvalidState(&'static str),
    DuplicateReference(&'static str),
    SelfReference(&'static str),
}

impl fmt::Display for CoreValidationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let (kind, field) = match self {
            Self::EmptyField(field) => ("empty field", field),
            Self::NilId(field) => ("nil UUID", field),
            Self::InvalidRange(field) => ("invalid range", field),
            Self::InvalidState(field) => ("invalid state", field),
            Self::DuplicateReference(field) => ("duplicate reference", field),
            Self::SelfReference(field) => ("self reference", field),
        };
        write!(f, "{kind}: {field}")
    }
}
impl Error for CoreValidationError {}

pub fn validate_non_empty(field: &'static str, value: &str) -> Result<(), CoreValidationError> {
    if value.trim().is_empty() {
        Err(CoreValidationError::EmptyField(field))
    } else {
        Ok(())
    }
}
pub fn validate_non_nil(field: &'static str, value: Uuid) -> Result<(), CoreValidationError> {
    if value.is_nil() {
        Err(CoreValidationError::NilId(field))
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn risk_level_serializes_to_frozen_wire_value() {
        assert_eq!(serde_json::to_string(&RiskLevel::R4).unwrap(), "\"R4\"");
    }
    #[test]
    fn object_ref_rejects_nil_ids() {
        let reference = ObjectRef::new(Uuid::nil(), "Document", None);
        assert_eq!(
            reference.validate(),
            Err(CoreValidationError::NilId("object_ref.id"))
        );
    }
    #[test]
    fn resource_percentages_are_bounded() {
        let budget = ResourceBudget {
            cpu_percent: Some(101.0),
            ..ResourceBudget::default()
        };
        assert_eq!(
            budget.validate(),
            Err(CoreValidationError::InvalidRange(
                "resource_budget.cpu_percent"
            ))
        );
    }
}
