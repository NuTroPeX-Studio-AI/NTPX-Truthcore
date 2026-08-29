#![forbid(unsafe_code)]

use ntpx_core_common::{
    CoreValidationError, DataClass, Iso8601, Metadata, ObjectRef, ProvenanceRecord, SemVer,
    TrustClass, validate_non_empty, validate_non_nil,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use uuid::Uuid;

pub const NTPX_OBJECT_SCHEMA: &str = "ntpx.object/v1";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NTPXObject {
    pub id: Uuid,
    #[serde(rename = "type")]
    pub object_type: String,
    pub schema_version: String,
    pub owner_id: Option<Uuid>,
    pub workspace_id: Option<Uuid>,
    pub tenant_id: Option<Uuid>,
    pub name: Option<String>,
    pub description: Option<String>,
    pub created_at: Iso8601,
    pub updated_at: Iso8601,
    pub version: SemVer,
    pub trust: TrustClass,
    pub data_class: DataClass,
    pub tags: Vec<String>,
    pub capabilities: Vec<String>,
    pub provenance: Option<ProvenanceRecord>,
    pub metadata: Metadata,
}

impl NTPXObject {
    pub fn new(
        id: Uuid,
        object_type: impl Into<String>,
        version: impl Into<String>,
        created_at: impl Into<String>,
        updated_at: impl Into<String>,
    ) -> Self {
        Self {
            id,
            object_type: object_type.into(),
            schema_version: NTPX_OBJECT_SCHEMA.to_owned(),
            owner_id: None,
            workspace_id: None,
            tenant_id: None,
            name: None,
            description: None,
            created_at: created_at.into(),
            updated_at: updated_at.into(),
            version: version.into(),
            trust: TrustClass::Unknown,
            data_class: DataClass::Internal,
            tags: Vec::new(),
            capabilities: Vec::new(),
            provenance: None,
            metadata: Metadata::new(),
        }
    }
    pub fn object_ref(&self) -> ObjectRef {
        ObjectRef::new(
            self.id,
            self.object_type.clone(),
            Some(self.version.clone()),
        )
    }
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        self.validate_as(NTPX_OBJECT_SCHEMA)
    }
    pub fn validate_as(&self, expected_schema: &str) -> Result<(), CoreValidationError> {
        validate_non_nil("object.id", self.id)?;
        validate_non_empty("object.type", &self.object_type)?;
        validate_non_empty("object.schema_version", &self.schema_version)?;
        validate_non_empty("object.version", &self.version)?;
        validate_non_empty("object.created_at", &self.created_at)?;
        validate_non_empty("object.updated_at", &self.updated_at)?;
        if self.schema_version != expected_schema {
            return Err(CoreValidationError::InvalidState("object.schema_version"));
        }
        for (field, values) in [
            ("object.tags", self.tags.as_slice()),
            ("object.capabilities", self.capabilities.as_slice()),
        ] {
            if values.iter().any(|value| value.trim().is_empty()) {
                return Err(CoreValidationError::EmptyField(field));
            }
            let unique: BTreeSet<_> = values.iter().collect();
            if unique.len() != values.len() {
                return Err(CoreValidationError::DuplicateReference(field));
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn sample() -> NTPXObject {
        NTPXObject::new(
            Uuid::new_v4(),
            "Document",
            "1.0.0",
            "2026-08-29T06:00:00Z",
            "2026-08-29T06:00:00Z",
        )
    }
    #[test]
    fn object_uses_frozen_schema() {
        let object = sample();
        assert_eq!(object.schema_version, NTPX_OBJECT_SCHEMA);
        assert!(object.validate().is_ok());
    }
    #[test]
    fn concrete_schema_validation_supports_derived_contracts() {
        let mut object = sample();
        object.schema_version = "ntpx.artifact/v1".into();
        assert!(object.validate_as("ntpx.artifact/v1").is_ok());
        assert!(object.validate().is_err());
    }
    #[test]
    fn wire_format_preserves_type_field() {
        let object = sample();
        let json = serde_json::to_value(object).unwrap();
        assert_eq!(json["type"], "Document");
        assert_eq!(json["schema_version"], NTPX_OBJECT_SCHEMA);
    }
    #[test]
    fn duplicate_capabilities_are_rejected() {
        let mut object = sample();
        object.capabilities = vec!["browser.read".into(), "browser.read".into()];
        assert_eq!(
            object.validate(),
            Err(CoreValidationError::DuplicateReference(
                "object.capabilities"
            ))
        );
    }
}
