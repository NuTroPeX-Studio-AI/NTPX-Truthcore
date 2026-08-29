#![forbid(unsafe_code)]

use ntpx_core_common::{CoreValidationError, CostBudget, Iso8601, ObjectRef, ResourceBudget, validate_non_empty, validate_non_nil};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use uuid::Uuid;

pub const NTPX_TASK_SCHEMA: &str = "ntpx.task/v1";
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)] #[serde(rename_all = "SCREAMING_SNAKE_CASE")] pub enum TaskPriority { Low, Normal, High, Critical }
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)] #[serde(rename_all = "SCREAMING_SNAKE_CASE")] pub enum TaskState { Idea, Queued, Planning, Ready, Running, Waiting, Blocked, Review, Completed, Failed, Canceled, Archived }
impl TaskState { pub fn is_terminal(self) -> bool { matches!(self, Self::Completed | Self::Failed | Self::Canceled | Self::Archived) } }

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NTPXTask { pub schema_version: String, pub id: Uuid, pub goal: String, pub requester_id: Uuid, pub owner_id: Option<Uuid>, pub workspace_id: Option<Uuid>, pub priority: TaskPriority, pub state: TaskState, pub dependencies: Vec<Uuid>, pub required_capabilities: Vec<String>, pub assigned_agent_ids: Vec<Uuid>, pub assigned_team_ids: Vec<Uuid>, pub resource_budget: ResourceBudget, pub cost_budget: Option<CostBudget>, pub inputs: Vec<ObjectRef>, pub outputs: Vec<ObjectRef>, pub actions: Vec<Uuid>, pub artifacts: Vec<Uuid>, pub deadline: Option<Iso8601>, pub created_at: Iso8601, pub updated_at: Iso8601 }
impl NTPXTask {
    pub fn new(id: Uuid, goal: impl Into<String>, requester_id: Uuid, created_at: impl Into<String>, updated_at: impl Into<String>) -> Self { Self { schema_version: NTPX_TASK_SCHEMA.to_owned(), id, goal: goal.into(), requester_id, owner_id: None, workspace_id: None, priority: TaskPriority::Normal, state: TaskState::Idea, dependencies: Vec::new(), required_capabilities: Vec::new(), assigned_agent_ids: Vec::new(), assigned_team_ids: Vec::new(), resource_budget: ResourceBudget::default(), cost_budget: None, inputs: Vec::new(), outputs: Vec::new(), actions: Vec::new(), artifacts: Vec::new(), deadline: None, created_at: created_at.into(), updated_at: updated_at.into() } }
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_nil("task.id", self.id)?; validate_non_nil("task.requester_id", self.requester_id)?; validate_non_empty("task.goal", &self.goal)?; validate_non_empty("task.created_at", &self.created_at)?; validate_non_empty("task.updated_at", &self.updated_at)?;
        if self.schema_version != NTPX_TASK_SCHEMA { return Err(CoreValidationError::InvalidState("task.schema_version")); }
        self.resource_budget.validate()?; if let Some(cost_budget) = &self.cost_budget { cost_budget.validate()?; }
        validate_uuid_collection("task.dependencies", self.id, &self.dependencies, true)?; validate_uuid_collection("task.assigned_agent_ids", self.id, &self.assigned_agent_ids, false)?; validate_uuid_collection("task.assigned_team_ids", self.id, &self.assigned_team_ids, false)?; validate_uuid_collection("task.actions", self.id, &self.actions, false)?; validate_uuid_collection("task.artifacts", self.id, &self.artifacts, false)?;
        let capabilities: BTreeSet<_> = self.required_capabilities.iter().collect(); if capabilities.len() != self.required_capabilities.len() { return Err(CoreValidationError::DuplicateReference("task.required_capabilities")); }
        if self.required_capabilities.iter().any(|capability| capability.trim().is_empty()) { return Err(CoreValidationError::EmptyField("task.required_capabilities")); }
        for reference in self.inputs.iter().chain(&self.outputs) { reference.validate()?; }
        Ok(())
    }
}
fn validate_uuid_collection(field: &'static str, owner_id: Uuid, values: &[Uuid], reject_self: bool) -> Result<(), CoreValidationError> { let mut unique = BTreeSet::new(); for value in values { validate_non_nil(field, *value)?; if reject_self && *value == owner_id { return Err(CoreValidationError::SelfReference(field)); } if !unique.insert(*value) { return Err(CoreValidationError::DuplicateReference(field)); } } Ok(()) }

#[cfg(test)] mod tests { use super::*; fn sample_task() -> NTPXTask { NTPXTask::new(Uuid::new_v4(), "Research a verified source", Uuid::new_v4(), "2026-08-29T06:00:00Z", "2026-08-29T06:00:00Z") } #[test] fn task_rejects_self_dependency() { let mut task = sample_task(); task.dependencies.push(task.id); assert_eq!(task.validate(), Err(CoreValidationError::SelfReference("task.dependencies"))); } #[test] fn task_rejects_duplicate_capabilities() { let mut task = sample_task(); task.required_capabilities = vec!["browser.read".into(), "browser.read".into()]; assert_eq!(task.validate(), Err(CoreValidationError::DuplicateReference("task.required_capabilities"))); } #[test] fn terminal_states_are_explicit() { assert!(TaskState::Completed.is_terminal()); assert!(TaskState::Failed.is_terminal()); assert!(!TaskState::Running.is_terminal()); } }
