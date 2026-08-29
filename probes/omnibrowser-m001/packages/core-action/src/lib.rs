use ntpx_core_common::{
    CoreValidationError, Iso8601, Metadata, ObjectRef, RiskLevel, validate_non_empty,
    validate_non_nil,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeSet;
use uuid::Uuid;

pub const NTPX_ACTION_SCHEMA: &str = "ntpx.action/v1";
pub const NTPX_CHANGESET_SCHEMA: &str = "ntpx.changeset/v1";
pub const NTPX_APPROVAL_SCHEMA: &str = "ntpx.approval/v1";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ActionStatus {
    Proposed,
    Authorized,
    Denied,
    Executing,
    Succeeded,
    Failed,
    RolledBack,
}

impl ActionStatus {
    pub fn is_terminal(self) -> bool {
        matches!(
            self,
            Self::Denied | Self::Succeeded | Self::Failed | Self::RolledBack
        )
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ActionResult {
    pub summary: String,
    pub output_refs: Vec<ObjectRef>,
    pub metadata: Metadata,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NTPXAction {
    pub schema_version: String,
    pub id: Uuid,
    pub actor_id: Uuid,
    pub capability: String,
    pub target: Option<ObjectRef>,
    pub intent: String,
    pub parameters: Metadata,
    pub workspace_id: Option<Uuid>,
    pub session_id: Option<Uuid>,
    pub risk: RiskLevel,
    pub requested_at: Iso8601,
    pub expires_at: Option<Iso8601>,
    pub approval_required: bool,
    pub approval_id: Option<Uuid>,
    pub reversible: bool,
    pub change_set_id: Option<Uuid>,
    pub status: ActionStatus,
    pub result: Option<ActionResult>,
    pub trace_id: Uuid,
}

impl NTPXAction {
    pub fn new(
        id: Uuid,
        actor_id: Uuid,
        capability: impl Into<String>,
        intent: impl Into<String>,
        risk: RiskLevel,
        requested_at: impl Into<String>,
        trace_id: Uuid,
    ) -> Self {
        Self {
            schema_version: NTPX_ACTION_SCHEMA.to_owned(),
            id,
            actor_id,
            capability: capability.into(),
            target: None,
            intent: intent.into(),
            parameters: Metadata::new(),
            workspace_id: None,
            session_id: None,
            risk,
            requested_at: requested_at.into(),
            expires_at: None,
            approval_required: false,
            approval_id: None,
            reversible: false,
            change_set_id: None,
            status: ActionStatus::Proposed,
            result: None,
            trace_id,
        }
    }

    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_nil("action.id", self.id)?;
        validate_non_nil("action.actor_id", self.actor_id)?;
        validate_non_nil("action.trace_id", self.trace_id)?;
        validate_non_empty("action.capability", &self.capability)?;
        validate_non_empty("action.intent", &self.intent)?;
        validate_non_empty("action.requested_at", &self.requested_at)?;

        if self.schema_version != NTPX_ACTION_SCHEMA {
            return Err(CoreValidationError::InvalidState("action.schema_version"));
        }
        if self.approval_required && self.approval_id.is_none() {
            return Err(CoreValidationError::InvalidState("action.approval_id"));
        }
        if let Some(target) = &self.target {
            target.validate()?;
        }
        if let Some(result) = &self.result {
            validate_non_empty("action.result.summary", &result.summary)?;
            for output in &result.output_refs {
                output.validate()?;
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ChangeOperation {
    pub path: String,
    pub before: Option<Value>,
    pub after: Option<Value>,
}

impl ChangeOperation {
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_empty("changeset.operation.path", &self.path)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ChangeSet {
    pub schema_version: String,
    pub id: Uuid,
    pub action_id: Uuid,
    pub affected_objects: Vec<ObjectRef>,
    pub additions: Vec<ChangeOperation>,
    pub modifications: Vec<ChangeOperation>,
    pub removals: Vec<ChangeOperation>,
    pub preview_available: bool,
    pub rollback_available: bool,
    pub before_snapshot: Option<String>,
    pub after_snapshot: Option<String>,
    pub created_at: Iso8601,
}

impl ChangeSet {
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_nil("changeset.id", self.id)?;
        validate_non_nil("changeset.action_id", self.action_id)?;
        validate_non_empty("changeset.created_at", &self.created_at)?;
        if self.schema_version != NTPX_CHANGESET_SCHEMA {
            return Err(CoreValidationError::InvalidState(
                "changeset.schema_version",
            ));
        }

        let mut ids = BTreeSet::new();
        for reference in &self.affected_objects {
            reference.validate()?;
            if !ids.insert(reference.id) {
                return Err(CoreValidationError::DuplicateReference(
                    "changeset.affected_objects",
                ));
            }
        }

        for operation in self
            .additions
            .iter()
            .chain(&self.modifications)
            .chain(&self.removals)
        {
            operation.validate()?;
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ApprovalScope {
    Once,
    Session,
    Workspace,
    Persistent,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ApprovalState {
    Pending,
    Approved,
    Modified,
    Denied,
    Expired,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ApprovalRequest {
    pub schema_version: String,
    pub id: Uuid,
    pub requester_id: Uuid,
    pub action_id: Uuid,
    pub capability: String,
    pub target: Option<ObjectRef>,
    pub reason: String,
    pub risk: RiskLevel,
    pub scope: ApprovalScope,
    pub requested_at: Iso8601,
    pub expires_at: Option<Iso8601>,
    pub alternatives: Vec<String>,
    pub state: ApprovalState,
    pub decided_by: Option<Uuid>,
    pub decided_at: Option<Iso8601>,
}

impl ApprovalRequest {
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_nil("approval.id", self.id)?;
        validate_non_nil("approval.requester_id", self.requester_id)?;
        validate_non_nil("approval.action_id", self.action_id)?;
        validate_non_empty("approval.capability", &self.capability)?;
        validate_non_empty("approval.reason", &self.reason)?;
        validate_non_empty("approval.requested_at", &self.requested_at)?;

        if self.schema_version != NTPX_APPROVAL_SCHEMA {
            return Err(CoreValidationError::InvalidState(
                "approval.schema_version",
            ));
        }

        if self.state != ApprovalState::Pending
            && (self.decided_by.is_none() || self.decided_at.is_none())
            && self.state != ApprovalState::Expired
        {
            return Err(CoreValidationError::InvalidState(
                "approval.decision_metadata",
            ));
        }

        if let Some(target) = &self.target {
            target.validate()?;
        }
        if self.alternatives.iter().any(|value| value.trim().is_empty()) {
            return Err(CoreValidationError::EmptyField("approval.alternatives"));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_action() -> NTPXAction {
        NTPXAction::new(
            Uuid::new_v4(),
            Uuid::new_v4(),
            "browser.download",
            "Download requested report",
            RiskLevel::R2,
            "2026-08-29T06:00:00Z",
            Uuid::new_v4(),
        )
    }

    #[test]
    fn action_requires_trace_identity() {
        let mut action = sample_action();
        action.trace_id = Uuid::nil();
        assert_eq!(
            action.validate(),
            Err(CoreValidationError::NilId("action.trace_id"))
        );
    }

    #[test]
    fn approval_required_action_requires_approval_id() {
        let mut action = sample_action();
        action.approval_required = true;
        assert_eq!(
            action.validate(),
            Err(CoreValidationError::InvalidState("action.approval_id"))
        );
    }

    #[test]
    fn approved_request_requires_decision_metadata() {
        let request = ApprovalRequest {
            schema_version: NTPX_APPROVAL_SCHEMA.into(),
            id: Uuid::new_v4(),
            requester_id: Uuid::new_v4(),
            action_id: Uuid::new_v4(),
            capability: "file.write".into(),
            target: None,
            reason: "Persist an approved artifact".into(),
            risk: RiskLevel::R3,
            scope: ApprovalScope::Once,
            requested_at: "2026-08-29T06:00:00Z".into(),
            expires_at: None,
            alternatives: vec![],
            state: ApprovalState::Approved,
            decided_by: None,
            decided_at: None,
        };
        assert_eq!(
            request.validate(),
            Err(CoreValidationError::InvalidState(
                "approval.decision_metadata"
            ))
        );
    }

    #[test]
    fn action_status_wire_values_are_stable() {
        assert_eq!(
            serde_json::to_string(&ActionStatus::RolledBack).unwrap(),
            "\"ROLLED_BACK\""
        );
    }
}
