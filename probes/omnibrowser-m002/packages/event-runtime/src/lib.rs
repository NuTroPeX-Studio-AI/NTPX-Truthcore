#![forbid(unsafe_code)]

use ntpx_core_common::{
    CoreValidationError, Iso8601, ObjectRef, validate_non_empty, validate_non_nil,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};
use uuid::Uuid;

pub const NTPX_EVENT_SCHEMA: &str = "ntpx.event/v1";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NTPXEvent {
    pub schema_version: String,
    pub event_id: Uuid,
    pub event_type: String,
    pub occurred_at: Iso8601,
    pub producer: ObjectRef,
    pub payload: Value,
    pub trace_id: Uuid,
    pub correlation_id: Uuid,
    pub causation_id: Option<Uuid>,
    pub chain_id: Uuid,
    pub parent_chain_id: Option<Uuid>,
    pub sequence: u64,
}
impl NTPXEvent {
    pub fn new(
        event_type: impl Into<String>,
        occurred_at: impl Into<Iso8601>,
        producer: ObjectRef,
        payload: Value,
        trace_id: Uuid,
        correlation_id: Uuid,
        chain_id: Uuid,
    ) -> Self {
        Self {
            schema_version: NTPX_EVENT_SCHEMA.to_owned(),
            event_id: Uuid::new_v4(),
            event_type: event_type.into(),
            occurred_at: occurred_at.into(),
            producer,
            payload,
            trace_id,
            correlation_id,
            causation_id: None,
            chain_id,
            parent_chain_id: None,
            sequence: 0,
        }
    }
    pub fn validate(&self) -> Result<(), CoreValidationError> {
        if self.schema_version != NTPX_EVENT_SCHEMA {
            return Err(CoreValidationError::InvalidState("event.schema_version"));
        }
        validate_non_nil("event.event_id", self.event_id)?;
        validate_non_nil("event.trace_id", self.trace_id)?;
        validate_non_nil("event.correlation_id", self.correlation_id)?;
        validate_non_nil("event.chain_id", self.chain_id)?;
        validate_non_empty("event.event_type", &self.event_type)?;
        validate_non_empty("event.occurred_at", &self.occurred_at)?;
        self.producer.validate()?;
        if self.causation_id == Some(self.event_id) {
            return Err(CoreValidationError::SelfReference("event.causation_id"));
        }
        if self.parent_chain_id == Some(self.chain_id) {
            return Err(CoreValidationError::SelfReference("event.parent_chain_id"));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EventChain {
    pub chain_id: Uuid,
    pub root_event_id: Uuid,
    pub parent_chain_id: Option<Uuid>,
    pub event_ids: Vec<Uuid>,
    pub started_at: Iso8601,
    pub updated_at: Iso8601,
}
impl EventChain {
    fn from_first(event: &NTPXEvent) -> Self {
        Self {
            chain_id: event.chain_id,
            root_event_id: event.event_id,
            parent_chain_id: event.parent_chain_id,
            event_ids: vec![event.event_id],
            started_at: event.occurred_at.clone(),
            updated_at: event.occurred_at.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EventRuntimeError {
    Validation(CoreValidationError),
    DuplicateEvent(Uuid),
    ChainParentMismatch(Uuid),
    UnknownSubscription(String),
    InvalidAck { subscription: String, sequence: u64 },
}
impl From<CoreValidationError> for EventRuntimeError {
    fn from(value: CoreValidationError) -> Self {
        Self::Validation(value)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Subscription {
    pub id: String,
    pub event_types: BTreeSet<String>,
    pub next_sequence: u64,
    pub pending_sequence: Option<u64>,
}
impl Subscription {
    pub fn all(id: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            event_types: BTreeSet::new(),
            next_sequence: 1,
            pending_sequence: None,
        }
    }
    fn matches(&self, event: &NTPXEvent) -> bool {
        self.event_types.is_empty() || self.event_types.contains(&event.event_type)
    }
}

#[derive(Debug, Default)]
pub struct EventBus {
    log: Vec<NTPXEvent>,
    ids: BTreeSet<Uuid>,
    chains: BTreeMap<Uuid, EventChain>,
    subscriptions: BTreeMap<String, Subscription>,
}
impl EventBus {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn publish(&mut self, mut event: NTPXEvent) -> Result<u64, EventRuntimeError> {
        event.validate()?;
        if self.ids.contains(&event.event_id) {
            return Err(EventRuntimeError::DuplicateEvent(event.event_id));
        }
        let sequence = self.log.len() as u64 + 1;
        event.sequence = sequence;
        match self.chains.get_mut(&event.chain_id) {
            Some(chain) => {
                if chain.parent_chain_id != event.parent_chain_id {
                    return Err(EventRuntimeError::ChainParentMismatch(event.chain_id));
                }
                chain.event_ids.push(event.event_id);
                chain.updated_at = event.occurred_at.clone();
            }
            None => {
                self.chains
                    .insert(event.chain_id, EventChain::from_first(&event));
            }
        }
        self.ids.insert(event.event_id);
        self.log.push(event);
        Ok(sequence)
    }
    pub fn register_subscription(
        &mut self,
        subscription: Subscription,
    ) -> Result<(), CoreValidationError> {
        validate_non_empty("subscription.id", &subscription.id)?;
        self.subscriptions
            .insert(subscription.id.clone(), subscription);
        Ok(())
    }
    pub fn poll(&mut self, subscription_id: &str) -> Result<Option<NTPXEvent>, EventRuntimeError> {
        let subscription = self
            .subscriptions
            .get_mut(subscription_id)
            .ok_or_else(|| EventRuntimeError::UnknownSubscription(subscription_id.to_owned()))?;
        let start = subscription
            .pending_sequence
            .unwrap_or(subscription.next_sequence);
        let found = self
            .log
            .iter()
            .find(|event| event.sequence >= start && subscription.matches(event))
            .cloned();
        if let Some(event) = &found {
            subscription.pending_sequence = Some(event.sequence);
        }
        Ok(found)
    }
    pub fn ack(&mut self, subscription_id: &str, sequence: u64) -> Result<(), EventRuntimeError> {
        let subscription = self
            .subscriptions
            .get_mut(subscription_id)
            .ok_or_else(|| EventRuntimeError::UnknownSubscription(subscription_id.to_owned()))?;
        if subscription.pending_sequence != Some(sequence) {
            return Err(EventRuntimeError::InvalidAck {
                subscription: subscription_id.to_owned(),
                sequence,
            });
        }
        subscription.pending_sequence = None;
        subscription.next_sequence = sequence + 1;
        Ok(())
    }
    pub fn replay_chain(&self, chain_id: Uuid) -> Vec<NTPXEvent> {
        self.log
            .iter()
            .filter(|event| event.chain_id == chain_id)
            .cloned()
            .collect()
    }
    pub fn chain(&self, chain_id: Uuid) -> Option<&EventChain> {
        self.chains.get(&chain_id)
    }
    pub fn events(&self) -> &[NTPXEvent] {
        &self.log
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn event(kind: &str, chain_id: Uuid) -> NTPXEvent {
        NTPXEvent::new(
            kind,
            "2026-08-29T08:40:00Z",
            ObjectRef::new(Uuid::new_v4(), "Module", Some("1.0.0".into())),
            serde_json::json!({"ok": true}),
            Uuid::new_v4(),
            Uuid::new_v4(),
            chain_id,
        )
    }
    #[test]
    fn event_wire_contract_is_frozen() {
        let value = serde_json::to_value(event("MODULE_STARTED", Uuid::new_v4())).unwrap();
        assert_eq!(value["schema_version"], NTPX_EVENT_SCHEMA);
    }
    #[test]
    fn publish_assigns_immutable_sequence_and_chain_order() {
        let chain = Uuid::new_v4();
        let mut bus = EventBus::new();
        assert_eq!(bus.publish(event("A", chain)).unwrap(), 1);
        assert_eq!(bus.publish(event("B", chain)).unwrap(), 2);
        let replay = bus.replay_chain(chain);
        assert_eq!(replay.len(), 2);
        assert_eq!(replay[0].sequence, 1);
        assert_eq!(replay[1].sequence, 2);
    }
    #[test]
    fn unacked_delivery_is_replayed_at_least_once() {
        let mut bus = EventBus::new();
        bus.publish(event("A", Uuid::new_v4())).unwrap();
        bus.register_subscription(Subscription::all("worker"))
            .unwrap();
        let first = bus.poll("worker").unwrap().unwrap();
        let second = bus.poll("worker").unwrap().unwrap();
        assert_eq!(first.event_id, second.event_id);
        bus.ack("worker", first.sequence).unwrap();
        assert!(bus.poll("worker").unwrap().is_none());
    }
    #[test]
    fn causation_cannot_reference_self() {
        let mut value = event("A", Uuid::new_v4());
        value.causation_id = Some(value.event_id);
        assert_eq!(
            value.validate(),
            Err(CoreValidationError::SelfReference("event.causation_id"))
        );
    }
}
