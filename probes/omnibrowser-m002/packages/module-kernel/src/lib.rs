#![forbid(unsafe_code)]

use ntpx_core_common::{
    CoreValidationError, Iso8601, ObjectRef, SemVer, validate_non_empty, validate_non_nil,
};
use ntpx_event_runtime::{EventBus, EventRuntimeError, NTPXEvent};
use ntpx_runtime_control::{
    ControlLock, ControlScope, ControlState, TargetType, ToggleControl, state_enables_execution,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet};
use uuid::Uuid;

pub const NTPX_MODULE_SCHEMA: &str = "ntpx.module/v1";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ModuleLifecycleState {
    Discovered,
    Validated,
    Registered,
    Disabled,
    Enabled,
    Starting,
    Running,
    Suspended,
    Stopping,
    Stopped,
    Degraded,
    Faulted,
    Quarantined,
    Incompatible,
    Blocked,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ModuleHealth {
    Unknown,
    Healthy,
    Degraded,
    Unhealthy,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModuleDependency {
    pub module_id: Uuid,
    pub version_constraint: Option<SemVer>,
    pub required: bool,
}

impl ModuleDependency {
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_nil("module.dependency.module_id", self.module_id)?;
        if let Some(value) = &self.version_constraint {
            validate_non_empty("module.dependency.version_constraint", value)?;
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModuleManifest {
    pub schema_version: String,
    pub module_id: Uuid,
    pub name: String,
    pub version: SemVer,
    pub category: String,
    pub description: String,
    pub dependencies: Vec<ModuleDependency>,
    pub default_state: ControlState,
    pub health_check: Option<String>,
    pub help_ref: Option<String>,
}

impl ModuleManifest {
    pub fn new(
        module_id: Uuid,
        name: impl Into<String>,
        version: impl Into<SemVer>,
        category: impl Into<String>,
        description: impl Into<String>,
        default_state: ControlState,
    ) -> Self {
        Self {
            schema_version: NTPX_MODULE_SCHEMA.to_owned(),
            module_id,
            name: name.into(),
            version: version.into(),
            category: category.into(),
            description: description.into(),
            dependencies: Vec::new(),
            default_state,
            health_check: None,
            help_ref: None,
        }
    }

    pub fn validate(&self) -> Result<(), CoreValidationError> {
        if self.schema_version != NTPX_MODULE_SCHEMA {
            return Err(CoreValidationError::InvalidState("module.schema_version"));
        }
        validate_non_nil("module.module_id", self.module_id)?;
        validate_non_empty("module.name", &self.name)?;
        validate_non_empty("module.version", &self.version)?;
        validate_non_empty("module.category", &self.category)?;
        validate_non_empty("module.description", &self.description)?;
        if let Some(value) = &self.health_check {
            validate_non_empty("module.health_check", value)?;
        }
        if let Some(value) = &self.help_ref {
            validate_non_empty("module.help_ref", value)?;
        }
        let mut dependencies = BTreeSet::new();
        for dependency in &self.dependencies {
            dependency.validate()?;
            if dependency.module_id == self.module_id {
                return Err(CoreValidationError::SelfReference("module.dependencies"));
            }
            if !dependencies.insert(dependency.module_id) {
                return Err(CoreValidationError::DuplicateReference(
                    "module.dependencies",
                ));
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModuleSelfTestReport {
    pub passed: bool,
    pub summary: String,
    pub checked_at: Iso8601,
}

impl ModuleSelfTestReport {
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        validate_non_empty("module.self_test.summary", &self.summary)?;
        validate_non_empty("module.self_test.checked_at", &self.checked_at)?;
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModuleRecord {
    pub manifest: ModuleManifest,
    pub lifecycle: ModuleLifecycleState,
    pub health: ModuleHealth,
    pub control: ToggleControl,
    pub last_self_test: Option<ModuleSelfTestReport>,
    pub registered_at: Iso8601,
    pub updated_at: Iso8601,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ModuleKernelSnapshot {
    pub total: usize,
    pub running: usize,
    pub degraded: usize,
    pub faulted: usize,
    pub quarantined: usize,
    pub blocked: usize,
    pub disabled_or_stopped: usize,
    pub event_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ModuleKernelError {
    Validation(CoreValidationError),
    Event(EventRuntimeError),
    DuplicateModule(Uuid),
    UnknownModule(Uuid),
    DependencyMissing {
        module_id: Uuid,
        dependency_id: Uuid,
    },
    DependencyNotRunning {
        module_id: Uuid,
        dependency_id: Uuid,
    },
    ControlDisabled(Uuid),
    InvalidTransition {
        module_id: Uuid,
        from: ModuleLifecycleState,
        to: ModuleLifecycleState,
    },
}

impl From<CoreValidationError> for ModuleKernelError {
    fn from(value: CoreValidationError) -> Self {
        Self::Validation(value)
    }
}

impl From<EventRuntimeError> for ModuleKernelError {
    fn from(value: EventRuntimeError) -> Self {
        Self::Event(value)
    }
}

fn lifecycle_for_control(state: ControlState) -> ModuleLifecycleState {
    if state == ControlState::Denied {
        ModuleLifecycleState::Blocked
    } else if state_enables_execution(state) {
        ModuleLifecycleState::Enabled
    } else {
        ModuleLifecycleState::Disabled
    }
}

fn lifecycle_event(state: ModuleLifecycleState) -> &'static str {
    match state {
        ModuleLifecycleState::Enabled => "MODULE_ENABLED",
        ModuleLifecycleState::Blocked => "MODULE_BLOCKED",
        _ => "MODULE_DISABLED",
    }
}

#[derive(Debug, Default)]
pub struct ModuleKernel {
    modules: BTreeMap<Uuid, ModuleRecord>,
    events: EventBus,
}

impl ModuleKernel {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn register(
        &mut self,
        manifest: ModuleManifest,
        now: impl Into<Iso8601>,
        actor: impl Into<String>,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        manifest.validate()?;
        if self.modules.contains_key(&manifest.module_id) {
            return Err(ModuleKernelError::DuplicateModule(manifest.module_id));
        }
        let now = now.into();
        let actor = actor.into();
        let final_lifecycle = lifecycle_for_control(manifest.default_state);
        let control = ToggleControl::new(
            format!("module://{}", manifest.module_id),
            TargetType::Module,
            manifest.default_state,
            ControlScope::Module,
            actor,
            now.clone(),
            trace_id,
        );
        control.validate()?;
        let module_id = manifest.module_id;
        let record = ModuleRecord {
            manifest,
            lifecycle: ModuleLifecycleState::Discovered,
            health: ModuleHealth::Unknown,
            control,
            last_self_test: None,
            registered_at: now.clone(),
            updated_at: now.clone(),
        };
        self.modules.insert(module_id, record);
        self.emit(module_id, "MODULE_DISCOVERED", now.clone(), trace_id)?;
        self.transition(
            module_id,
            ModuleLifecycleState::Validated,
            now.clone(),
            trace_id,
            "MODULE_VALIDATED",
        )?;
        self.transition(
            module_id,
            ModuleLifecycleState::Registered,
            now.clone(),
            trace_id,
            "MODULE_REGISTERED",
        )?;
        self.transition(
            module_id,
            final_lifecycle,
            now,
            trace_id,
            lifecycle_event(final_lifecycle),
        )?;
        Ok(())
    }

    pub fn set_control(
        &mut self,
        module_id: Uuid,
        requested_state: ControlState,
        now: impl Into<Iso8601>,
        actor: impl Into<String>,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        let now = now.into();
        let actor = actor.into();
        let record = self
            .modules
            .get_mut(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?;
        match record.control.lock {
            ControlLock::LockedOn if !state_enables_execution(requested_state) => {
                return Err(ModuleKernelError::Validation(
                    CoreValidationError::InvalidState("toggle.lock"),
                ));
            }
            ControlLock::LockedOff if state_enables_execution(requested_state) => {
                return Err(ModuleKernelError::Validation(
                    CoreValidationError::InvalidState("toggle.lock"),
                ));
            }
            _ => {}
        }

        let previous_control = record.control.clone();
        let previous_lifecycle = record.lifecycle;
        let previous_updated_at = record.updated_at.clone();
        record.control.requested_state = requested_state;
        record.control.effective_state = requested_state;
        record.control.requested_by = actor;
        record.control.changed_at = now.clone();
        record.control.reason = None;
        record.control.policy_source = None;
        record.control.trace_id = trace_id;
        if let Err(error) = record.control.validate() {
            record.control = previous_control;
            record.lifecycle = previous_lifecycle;
            record.updated_at = previous_updated_at;
            return Err(ModuleKernelError::Validation(error));
        }
        record.updated_at = now.clone();

        if !matches!(
            record.lifecycle,
            ModuleLifecycleState::Faulted
                | ModuleLifecycleState::Quarantined
                | ModuleLifecycleState::Incompatible
        ) {
            if requested_state == ControlState::Denied {
                record.lifecycle = ModuleLifecycleState::Blocked;
            } else if record.control.is_execution_enabled() {
                if matches!(
                    record.lifecycle,
                    ModuleLifecycleState::Disabled
                        | ModuleLifecycleState::Stopped
                        | ModuleLifecycleState::Blocked
                ) {
                    record.lifecycle = ModuleLifecycleState::Enabled;
                }
            } else if matches!(
                record.lifecycle,
                ModuleLifecycleState::Running | ModuleLifecycleState::Degraded
            ) {
                record.lifecycle = ModuleLifecycleState::Stopped;
            } else if matches!(
                record.lifecycle,
                ModuleLifecycleState::Enabled | ModuleLifecycleState::Blocked
            ) {
                record.lifecycle = ModuleLifecycleState::Disabled;
            }
        }
        self.emit(module_id, "MODULE_CONTROL_CHANGED", now, trace_id)?;
        Ok(())
    }

    pub fn set_control_lock(
        &mut self,
        module_id: Uuid,
        lock: ControlLock,
        now: impl Into<Iso8601>,
        actor: impl Into<String>,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        let now = now.into();
        let actor = actor.into();
        let record = self
            .modules
            .get_mut(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?;
        let previous = record.control.clone();
        record.control.requested_by = actor;
        record.control.changed_at = now.clone();
        record.control.trace_id = trace_id;
        if let Err(error) = record.control.set_lock(lock) {
            record.control = previous;
            return Err(ModuleKernelError::Validation(error));
        }
        record.updated_at = now.clone();
        self.emit(module_id, "MODULE_CONTROL_LOCK_CHANGED", now, trace_id)?;
        Ok(())
    }

    pub fn start(
        &mut self,
        module_id: Uuid,
        now: impl Into<Iso8601>,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        let now = now.into();
        let current = self
            .modules
            .get(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?;
        if !current.control.is_execution_enabled() {
            return Err(ModuleKernelError::ControlDisabled(module_id));
        }
        self.check_dependencies(module_id)?;
        let current = self
            .modules
            .get(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?;
        if !matches!(
            current.lifecycle,
            ModuleLifecycleState::Enabled
                | ModuleLifecycleState::Stopped
                | ModuleLifecycleState::Suspended
        ) {
            return Err(ModuleKernelError::InvalidTransition {
                module_id,
                from: current.lifecycle,
                to: ModuleLifecycleState::Starting,
            });
        }
        self.transition(
            module_id,
            ModuleLifecycleState::Starting,
            now.clone(),
            trace_id,
            "MODULE_STARTING",
        )?;
        self.transition(
            module_id,
            ModuleLifecycleState::Running,
            now,
            trace_id,
            "MODULE_STARTED",
        )?;
        Ok(())
    }

    pub fn suspend(
        &mut self,
        module_id: Uuid,
        now: impl Into<Iso8601>,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        let current = self
            .modules
            .get(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?
            .lifecycle;
        if !matches!(
            current,
            ModuleLifecycleState::Running | ModuleLifecycleState::Degraded
        ) {
            return Err(ModuleKernelError::InvalidTransition {
                module_id,
                from: current,
                to: ModuleLifecycleState::Suspended,
            });
        }
        self.transition(
            module_id,
            ModuleLifecycleState::Suspended,
            now.into(),
            trace_id,
            "MODULE_SUSPENDED",
        )
    }

    pub fn stop(
        &mut self,
        module_id: Uuid,
        now: impl Into<Iso8601>,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        let now = now.into();
        let current = self
            .modules
            .get(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?
            .lifecycle;
        if !matches!(
            current,
            ModuleLifecycleState::Running
                | ModuleLifecycleState::Degraded
                | ModuleLifecycleState::Suspended
                | ModuleLifecycleState::Faulted
        ) {
            return Err(ModuleKernelError::InvalidTransition {
                module_id,
                from: current,
                to: ModuleLifecycleState::Stopping,
            });
        }
        self.transition(
            module_id,
            ModuleLifecycleState::Stopping,
            now.clone(),
            trace_id,
            "MODULE_STOPPING",
        )?;
        self.transition(
            module_id,
            ModuleLifecycleState::Stopped,
            now,
            trace_id,
            "MODULE_STOPPED",
        )
    }

    pub fn quarantine(
        &mut self,
        module_id: Uuid,
        now: impl Into<Iso8601>,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        self.transition(
            module_id,
            ModuleLifecycleState::Quarantined,
            now.into(),
            trace_id,
            "MODULE_QUARANTINED",
        )
    }

    pub fn report_health(
        &mut self,
        module_id: Uuid,
        health: ModuleHealth,
        now: impl Into<Iso8601>,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        let now = now.into();
        let record = self
            .modules
            .get_mut(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?;
        record.health = health;
        record.control.health_state = Some(format!("{health:?}").to_uppercase());
        record.updated_at = now.clone();
        if health == ModuleHealth::Degraded && record.lifecycle == ModuleLifecycleState::Running {
            record.lifecycle = ModuleLifecycleState::Degraded;
        }
        if health == ModuleHealth::Unhealthy
            && matches!(
                record.lifecycle,
                ModuleLifecycleState::Running | ModuleLifecycleState::Degraded
            )
        {
            record.lifecycle = ModuleLifecycleState::Faulted;
        }
        self.emit(module_id, "MODULE_HEALTH_CHANGED", now, trace_id)?;
        Ok(())
    }

    pub fn record_self_test(
        &mut self,
        module_id: Uuid,
        report: ModuleSelfTestReport,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        report.validate()?;
        let now = report.checked_at.clone();
        let event_type = if report.passed {
            "MODULE_SELF_TEST_PASSED"
        } else {
            "MODULE_SELF_TEST_FAILED"
        };
        let record = self
            .modules
            .get_mut(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?;
        record.health = if report.passed {
            ModuleHealth::Healthy
        } else {
            ModuleHealth::Unhealthy
        };
        record.control.health_state = Some(if report.passed {
            "HEALTHY".to_owned()
        } else {
            "UNHEALTHY".to_owned()
        });
        if !report.passed
            && matches!(
                record.lifecycle,
                ModuleLifecycleState::Running | ModuleLifecycleState::Degraded
            )
        {
            record.lifecycle = ModuleLifecycleState::Faulted;
        }
        record.last_self_test = Some(report);
        record.updated_at = now.clone();
        self.emit(module_id, event_type, now, trace_id)?;
        Ok(())
    }

    pub fn recover(
        &mut self,
        module_id: Uuid,
        now: impl Into<Iso8601>,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        let now = now.into();
        let record = self
            .modules
            .get_mut(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?;
        if record.lifecycle != ModuleLifecycleState::Faulted {
            return Err(ModuleKernelError::InvalidTransition {
                module_id,
                from: record.lifecycle,
                to: ModuleLifecycleState::Enabled,
            });
        }
        record.lifecycle = lifecycle_for_control(record.control.effective_state);
        record.health = ModuleHealth::Unknown;
        record.control.health_state = Some("UNKNOWN".to_owned());
        record.updated_at = now.clone();
        self.emit(module_id, "MODULE_RECOVERED", now, trace_id)?;
        Ok(())
    }

    pub fn module(&self, module_id: Uuid) -> Option<&ModuleRecord> {
        self.modules.get(&module_id)
    }

    pub fn modules(&self) -> impl Iterator<Item = &ModuleRecord> {
        self.modules.values()
    }

    pub fn snapshot(&self) -> ModuleKernelSnapshot {
        let mut snapshot = ModuleKernelSnapshot {
            total: self.modules.len(),
            event_count: self.events.len(),
            ..ModuleKernelSnapshot::default()
        };
        for module in self.modules.values() {
            match module.lifecycle {
                ModuleLifecycleState::Running => snapshot.running += 1,
                ModuleLifecycleState::Degraded => snapshot.degraded += 1,
                ModuleLifecycleState::Faulted => snapshot.faulted += 1,
                ModuleLifecycleState::Quarantined => snapshot.quarantined += 1,
                ModuleLifecycleState::Blocked => snapshot.blocked += 1,
                ModuleLifecycleState::Disabled | ModuleLifecycleState::Stopped => {
                    snapshot.disabled_or_stopped += 1;
                }
                _ => {}
            }
        }
        snapshot
    }

    pub fn event_bus(&self) -> &EventBus {
        &self.events
    }

    pub fn event_bus_mut(&mut self) -> &mut EventBus {
        &mut self.events
    }

    fn check_dependencies(&self, module_id: Uuid) -> Result<(), ModuleKernelError> {
        let record = self
            .modules
            .get(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?;
        for dependency in record
            .manifest
            .dependencies
            .iter()
            .filter(|dependency| dependency.required)
        {
            let dependency_record = self.modules.get(&dependency.module_id).ok_or(
                ModuleKernelError::DependencyMissing {
                    module_id,
                    dependency_id: dependency.module_id,
                },
            )?;
            if !matches!(
                dependency_record.lifecycle,
                ModuleLifecycleState::Running | ModuleLifecycleState::Degraded
            ) {
                return Err(ModuleKernelError::DependencyNotRunning {
                    module_id,
                    dependency_id: dependency.module_id,
                });
            }
        }
        Ok(())
    }

    fn transition(
        &mut self,
        module_id: Uuid,
        state: ModuleLifecycleState,
        now: Iso8601,
        trace_id: Uuid,
        event_type: &str,
    ) -> Result<(), ModuleKernelError> {
        let record = self
            .modules
            .get_mut(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?;
        record.lifecycle = state;
        record.updated_at = now.clone();
        self.emit(module_id, event_type, now, trace_id)
    }

    fn emit(
        &mut self,
        module_id: Uuid,
        event_type: &str,
        now: Iso8601,
        trace_id: Uuid,
    ) -> Result<(), ModuleKernelError> {
        let record = self
            .modules
            .get(&module_id)
            .ok_or(ModuleKernelError::UnknownModule(module_id))?;
        let event = NTPXEvent::new(
            event_type,
            now,
            ObjectRef::new(module_id, "Module", Some(record.manifest.version.clone())),
            json!({
                "module_id": module_id,
                "lifecycle": record.lifecycle,
                "health": record.health,
                "requested_state": record.control.requested_state,
                "effective_state": record.control.effective_state,
                "lock": record.control.lock,
                "self_test": record.last_self_test,
            }),
            trace_id,
            trace_id,
            trace_id,
        );
        self.events.publish(event)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest(name: &str, state: ControlState) -> ModuleManifest {
        ModuleManifest::new(
            Uuid::new_v4(),
            name,
            "1.0.0",
            "test",
            format!("{name} module"),
            state,
        )
    }

    #[test]
    fn module_wire_contract_is_frozen() {
        let value = serde_json::to_value(manifest("alpha", ControlState::On)).unwrap();
        assert_eq!(value["schema_version"], NTPX_MODULE_SCHEMA);
        let schema: serde_json::Value = serde_json::from_str(include_str!(
            "../../../schemas/ntpx.module.v1.schema.json"
        ))
        .unwrap();
        assert_eq!(schema["$id"], NTPX_MODULE_SCHEMA);
    }

    #[test]
    fn manifest_rejects_self_dependency() {
        let mut value = manifest("alpha", ControlState::On);
        value.dependencies.push(ModuleDependency {
            module_id: value.module_id,
            version_constraint: None,
            required: true,
        });
        assert_eq!(
            value.validate(),
            Err(CoreValidationError::SelfReference("module.dependencies"))
        );
    }

    #[test]
    fn registration_emits_full_foundation_lifecycle() {
        let mut kernel = ModuleKernel::new();
        let value = manifest("alpha", ControlState::On);
        let trace = Uuid::new_v4();
        kernel
            .register(value, "2026-08-29T09:00:00Z", "user:test", trace)
            .unwrap();
        let types: Vec<_> = kernel
            .event_bus()
            .trace(trace)
            .into_iter()
            .map(|event| event.event_type)
            .collect();
        assert_eq!(
            types,
            vec![
                "MODULE_DISCOVERED",
                "MODULE_VALIDATED",
                "MODULE_REGISTERED",
                "MODULE_ENABLED"
            ]
        );
    }

    #[test]
    fn off_module_cannot_start() {
        let mut kernel = ModuleKernel::new();
        let value = manifest("alpha", ControlState::Off);
        let id = value.module_id;
        kernel
            .register(value, "2026-08-29T09:00:00Z", "user:test", Uuid::new_v4())
            .unwrap();
        assert_eq!(
            kernel.start(id, "2026-08-29T09:00:01Z", Uuid::new_v4()),
            Err(ModuleKernelError::ControlDisabled(id))
        );
    }

    #[test]
    fn denied_module_registers_blocked() {
        let mut kernel = ModuleKernel::new();
        let value = manifest("alpha", ControlState::Denied);
        let id = value.module_id;
        kernel
            .register(value, "2026-08-29T09:00:00Z", "user:test", Uuid::new_v4())
            .unwrap();
        assert_eq!(
            kernel.module(id).unwrap().lifecycle,
            ModuleLifecycleState::Blocked
        );
    }

    #[test]
    fn required_dependency_must_be_running() {
        let mut kernel = ModuleKernel::new();
        let dependency = manifest("dependency", ControlState::On);
        let dependency_id = dependency.module_id;
        let mut consumer = manifest("consumer", ControlState::On);
        let consumer_id = consumer.module_id;
        consumer.dependencies.push(ModuleDependency {
            module_id: dependency_id,
            version_constraint: Some("1.x".into()),
            required: true,
        });
        kernel
            .register(
                dependency,
                "2026-08-29T09:00:00Z",
                "user:test",
                Uuid::new_v4(),
            )
            .unwrap();
        kernel
            .register(
                consumer,
                "2026-08-29T09:00:00Z",
                "user:test",
                Uuid::new_v4(),
            )
            .unwrap();
        assert_eq!(
            kernel.start(consumer_id, "2026-08-29T09:00:01Z", Uuid::new_v4()),
            Err(ModuleKernelError::DependencyNotRunning {
                module_id: consumer_id,
                dependency_id
            })
        );
        kernel
            .start(dependency_id, "2026-08-29T09:00:02Z", Uuid::new_v4())
            .unwrap();
        kernel
            .start(consumer_id, "2026-08-29T09:00:03Z", Uuid::new_v4())
            .unwrap();
        assert_eq!(
            kernel.module(consumer_id).unwrap().lifecycle,
            ModuleLifecycleState::Running
        );
    }

    #[test]
    fn control_lock_change_is_audited() {
        let mut kernel = ModuleKernel::new();
        let value = manifest("alpha", ControlState::On);
        let id = value.module_id;
        let trace = Uuid::new_v4();
        kernel
            .register(value, "2026-08-29T09:00:00Z", "user:test", Uuid::new_v4())
            .unwrap();
        kernel
            .set_control_lock(
                id,
                ControlLock::LockedOn,
                "2026-08-29T09:00:01Z",
                "system:test",
                trace,
            )
            .unwrap();
        assert_eq!(kernel.event_bus().trace(trace).len(), 1);
        assert_eq!(
            kernel.event_bus().trace(trace)[0].event_type,
            "MODULE_CONTROL_LOCK_CHANGED"
        );
    }

    #[test]
    fn failed_self_test_faults_running_module_and_recovery_does_not_restart() {
        let mut kernel = ModuleKernel::new();
        let value = manifest("alpha", ControlState::On);
        let id = value.module_id;
        kernel
            .register(value, "2026-08-29T09:00:00Z", "user:test", Uuid::new_v4())
            .unwrap();
        kernel
            .start(id, "2026-08-29T09:00:01Z", Uuid::new_v4())
            .unwrap();
        kernel
            .record_self_test(
                id,
                ModuleSelfTestReport {
                    passed: false,
                    summary: "dependency handshake failed".into(),
                    checked_at: "2026-08-29T09:00:02Z".into(),
                },
                Uuid::new_v4(),
            )
            .unwrap();
        assert_eq!(
            kernel.module(id).unwrap().lifecycle,
            ModuleLifecycleState::Faulted
        );
        kernel
            .recover(id, "2026-08-29T09:00:03Z", Uuid::new_v4())
            .unwrap();
        assert_eq!(
            kernel.module(id).unwrap().lifecycle,
            ModuleLifecycleState::Enabled
        );
    }

    #[test]
    fn unhealthy_running_module_faults_closed() {
        let mut kernel = ModuleKernel::new();
        let value = manifest("alpha", ControlState::On);
        let id = value.module_id;
        kernel
            .register(value, "2026-08-29T09:00:00Z", "user:test", Uuid::new_v4())
            .unwrap();
        kernel
            .start(id, "2026-08-29T09:00:01Z", Uuid::new_v4())
            .unwrap();
        kernel
            .report_health(
                id,
                ModuleHealth::Unhealthy,
                "2026-08-29T09:00:02Z",
                Uuid::new_v4(),
            )
            .unwrap();
        assert_eq!(
            kernel.module(id).unwrap().lifecycle,
            ModuleLifecycleState::Faulted
        );
        assert_eq!(kernel.snapshot().faulted, 1);
    }
}
