#![forbid(unsafe_code)]

use ntpx_core_common::{CoreValidationError, Iso8601, validate_non_empty, validate_non_nil};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ControlState {
    Off,
    On,
    Auto,
    Ask,
    Once,
    Session,
    Workspace,
    SandboxOnly,
    Locked,
    Denied,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ControlLock {
    #[default]
    None,
    LockedOn,
    LockedOff,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ControlScope {
    System,
    Profile,
    User,
    Device,
    Workspace,
    Module,
    Session,
    Task,
    Agent,
    Workflow,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TargetType {
    Module,
    Service,
    Fabric,
    Provider,
    Adapter,
    Plugin,
    Model,
    Agent,
    Team,
    Workflow,
    Device,
    Application,
    Capability,
    GodModeProfile,
    Feature,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToggleControl {
    pub control_id: Uuid,
    pub target_id: String,
    pub target_type: TargetType,
    pub requested_state: ControlState,
    pub effective_state: ControlState,
    pub lock: ControlLock,
    pub scope: ControlScope,
    pub requested_by: String,
    pub policy_source: Option<String>,
    pub reason: Option<String>,
    pub dependency_state: Option<String>,
    pub health_state: Option<String>,
    pub requires_approval: bool,
    pub reversible: bool,
    pub rollback_target: Option<ControlState>,
    pub changed_at: Iso8601,
    pub expires_at: Option<Iso8601>,
    pub trace_id: Uuid,
}

impl ToggleControl {
    pub fn new(
        target_id: impl Into<String>,
        target_type: TargetType,
        requested_state: ControlState,
        scope: ControlScope,
        requested_by: impl Into<String>,
        changed_at: impl Into<Iso8601>,
        trace_id: Uuid,
    ) -> Self {
        Self {
            control_id: Uuid::new_v4(),
            target_id: target_id.into(),
            target_type,
            requested_state,
            effective_state: requested_state,
            lock: ControlLock::None,
            scope,
            requested_by: requested_by.into(),
            policy_source: None,
            reason: None,
            dependency_state: None,
            health_state: None,
            requires_approval: false,
            reversible: true,
            rollback_target: None,
            changed_at: changed_at.into(),
            expires_at: None,
            trace_id,
        }
    }

    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_nil("toggle.control_id", self.control_id)?;
        validate_non_nil("toggle.trace_id", self.trace_id)?;
        validate_non_empty("toggle.target_id", &self.target_id)?;
        validate_non_empty("toggle.requested_by", &self.requested_by)?;
        validate_non_empty("toggle.changed_at", &self.changed_at)?;
        if let Some(value) = &self.policy_source {
            validate_non_empty("toggle.policy_source", value)?;
        }
        if let Some(value) = &self.reason {
            validate_non_empty("toggle.reason", value)?;
        }
        if let Some(value) = &self.expires_at {
            validate_non_empty("toggle.expires_at", value)?;
        }
        if self.requested_state != self.effective_state && self.reason.is_none() {
            return Err(CoreValidationError::InvalidState("toggle.reason"));
        }
        if self.rollback_target.is_some() && !self.reversible {
            return Err(CoreValidationError::InvalidState("toggle.rollback_target"));
        }
        match self.lock {
            ControlLock::None => {}
            ControlLock::LockedOn if !state_enables_execution(self.effective_state) => {
                return Err(CoreValidationError::InvalidState("toggle.lock"));
            }
            ControlLock::LockedOff if state_enables_execution(self.effective_state) => {
                return Err(CoreValidationError::InvalidState("toggle.lock"));
            }
            _ => {}
        }
        Ok(())
    }

    pub fn set_effective_state(
        &mut self,
        effective_state: ControlState,
        reason: Option<String>,
        policy_source: Option<String>,
    ) -> Result<(), CoreValidationError> {
        if effective_state != self.requested_state
            && reason.as_deref().unwrap_or("").trim().is_empty()
        {
            return Err(CoreValidationError::InvalidState("toggle.reason"));
        }
        let previous = (
            self.effective_state,
            self.reason.clone(),
            self.policy_source.clone(),
        );
        self.effective_state = effective_state;
        self.reason = reason;
        self.policy_source = policy_source;
        if let Err(error) = self.validate() {
            self.effective_state = previous.0;
            self.reason = previous.1;
            self.policy_source = previous.2;
            return Err(error);
        }
        Ok(())
    }

    pub fn set_lock(&mut self, lock: ControlLock) -> Result<(), CoreValidationError> {
        let previous = self.lock;
        self.lock = lock;
        if let Err(error) = self.validate() {
            self.lock = previous;
            return Err(error);
        }
        Ok(())
    }

    pub fn is_execution_enabled(&self) -> bool {
        state_enables_execution(self.effective_state)
    }
}

pub fn state_enables_execution(state: ControlState) -> bool {
    matches!(
        state,
        ControlState::On
            | ControlState::Auto
            | ControlState::Once
            | ControlState::Session
            | ControlState::Workspace
            | ControlState::SandboxOnly
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> ToggleControl {
        ToggleControl::new(
            "module://search",
            TargetType::Module,
            ControlState::On,
            ControlScope::Workspace,
            "user:test",
            "2026-08-29T08:30:00Z",
            Uuid::new_v4(),
        )
    }

    #[test]
    fn state_wire_values_are_stable() {
        assert_eq!(
            serde_json::to_string(&ControlState::SandboxOnly).unwrap(),
            "\"SANDBOX_ONLY\""
        );
        assert_eq!(
            serde_json::to_string(&ControlLock::LockedOn).unwrap(),
            "\"LOCKED_ON\""
        );
    }

    #[test]
    fn different_effective_state_requires_reason() {
        let mut control = sample();
        control.effective_state = ControlState::Denied;
        assert_eq!(
            control.validate(),
            Err(CoreValidationError::InvalidState("toggle.reason"))
        );
    }

    #[test]
    fn denied_is_not_execution_enabled() {
        let mut control = sample();
        control
            .set_effective_state(ControlState::Denied, Some("policy blocked".into()), None)
            .unwrap();
        assert!(!control.is_execution_enabled());
    }

    #[test]
    fn locked_on_cannot_be_made_off() {
        let mut control = sample();
        control.set_lock(ControlLock::LockedOn).unwrap();
        assert_eq!(
            control.set_effective_state(ControlState::Off, Some("requested".into()), None),
            Err(CoreValidationError::InvalidState("toggle.lock"))
        );
        assert_eq!(control.effective_state, ControlState::On);
    }

    #[test]
    fn rollback_target_requires_reversible_control() {
        let mut control = sample();
        control.reversible = false;
        control.rollback_target = Some(ControlState::Off);
        assert_eq!(
            control.validate(),
            Err(CoreValidationError::InvalidState("toggle.rollback_target"))
        );
    }
}
