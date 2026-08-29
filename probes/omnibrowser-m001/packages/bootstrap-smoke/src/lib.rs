//! M000 bootstrap-only crate.
//! Product primitives begin in M001.

/// Marker used by CI to prove the permanent Rust workspace compiles and tests.
pub const M000_BOOTSTRAP_MARKER: &str = "NTPX-OMNIBROWSER-M000-READY";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bootstrap_marker_is_stable() {
        assert_eq!(M000_BOOTSTRAP_MARKER, "NTPX-OMNIBROWSER-M000-READY");
    }
}
