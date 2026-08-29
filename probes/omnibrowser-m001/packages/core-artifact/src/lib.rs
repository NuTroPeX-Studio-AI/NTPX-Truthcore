use ntpx_core_common::{
    CoreValidationError, SignatureRecord, validate_non_empty, validate_non_nil,
};
use ntpx_core_object::NTPXObject;
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use uuid::Uuid;

pub const NTPX_ARTIFACT_SCHEMA: &str = "ntpx.artifact/v1";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ArtifactType {
    SourceCode,
    Build,
    Package,
    Document,
    Report,
    Image,
    Audio,
    Video,
    Model,
    Dataset,
    Configuration,
    ResearchResult,
    SecurityReport,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SecurityState {
    Unscanned,
    Scanning,
    Passed,
    Failed,
    Quarantined,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ReleaseState {
    Draft,
    Validated,
    Approved,
    Signed,
    Released,
    Deprecated,
    Revoked,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ArtifactDependency {
    pub artifact_id: Uuid,
    pub version_constraint: Option<String>,
    pub content_hash: Option<String>,
}

impl ArtifactDependency {
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_nil("artifact.dependency.artifact_id", self.artifact_id)?;
        if let Some(version) = &self.version_constraint {
            validate_non_empty("artifact.dependency.version_constraint", version)?;
        }
        if let Some(hash) = &self.content_hash {
            validate_non_empty("artifact.dependency.content_hash", hash)?;
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NTPXArtifact {
    pub schema_version: String,
    pub object: NTPXObject,
    pub artifact_type: ArtifactType,
    pub content_hash: String,
    pub signature: Option<SignatureRecord>,
    pub dependencies: Vec<ArtifactDependency>,
    pub security_state: SecurityState,
    pub release_state: ReleaseState,
}

impl NTPXArtifact {
    pub fn new(
        object: NTPXObject,
        artifact_type: ArtifactType,
        content_hash: impl Into<String>,
    ) -> Self {
        Self {
            schema_version: NTPX_ARTIFACT_SCHEMA.to_owned(),
            object,
            artifact_type,
            content_hash: content_hash.into(),
            signature: None,
            dependencies: Vec::new(),
            security_state: SecurityState::Unscanned,
            release_state: ReleaseState::Draft,
        }
    }

    pub fn validate(&self) -> Result<(), CoreValidationError> {
        self.object.validate()?;
        validate_non_empty("artifact.content_hash", &self.content_hash)?;

        if self.schema_version != NTPX_ARTIFACT_SCHEMA {
            return Err(CoreValidationError::InvalidState(
                "artifact.schema_version",
            ));
        }

        if matches!(self.release_state, ReleaseState::Signed | ReleaseState::Released)
            && self.signature.is_none()
        {
            return Err(CoreValidationError::InvalidState("artifact.signature"));
        }

        if let Some(signature) = &self.signature {
            validate_non_empty("artifact.signature.algorithm", &signature.algorithm)?;
            validate_non_empty("artifact.signature.signer", &signature.signer)?;
            validate_non_empty("artifact.signature.value", &signature.value)?;
            validate_non_empty("artifact.signature.signed_at", &signature.signed_at)?;
        }

        let mut dependency_ids = BTreeSet::new();
        for dependency in &self.dependencies {
            dependency.validate()?;
            if dependency.artifact_id == self.object.id {
                return Err(CoreValidationError::SelfReference(
                    "artifact.dependencies",
                ));
            }
            if !dependency_ids.insert(dependency.artifact_id) {
                return Err(CoreValidationError::DuplicateReference(
                    "artifact.dependencies",
                ));
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    fn sample_artifact() -> NTPXArtifact {
        let object = NTPXObject::new(
            Uuid::new_v4(),
            "Artifact",
            "1.0.0",
            "2026-08-29T06:00:00Z",
            "2026-08-29T06:00:00Z",
        );
        NTPXArtifact::new(object, ArtifactType::Document, "sha256:abc123")
    }

    #[test]
    fn released_artifact_requires_signature() {
        let mut artifact = sample_artifact();
        artifact.release_state = ReleaseState::Released;
        assert_eq!(
            artifact.validate(),
            Err(CoreValidationError::InvalidState("artifact.signature"))
        );
    }

    #[test]
    fn artifact_rejects_self_dependency() {
        let mut artifact = sample_artifact();
        artifact.dependencies.push(ArtifactDependency {
            artifact_id: artifact.object.id,
            version_constraint: Some("^1".into()),
            content_hash: None,
        });
        assert_eq!(
            artifact.validate(),
            Err(CoreValidationError::SelfReference("artifact.dependencies"))
        );
    }

    #[test]
    fn release_state_wire_value_is_stable() {
        assert_eq!(
            serde_json::to_string(&ReleaseState::Released).unwrap(),
            "\"RELEASED\""
        );
    }
}
