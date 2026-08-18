package com.ntpx.truthcore.core.semantic

data class ContradictionResolution(
    val resolved: Boolean,
    val selected: TemporalFact? = null,
    val reason: String,
    val unresolved: List<TemporalFact> = emptyList(),
)

object ContradictionResolver {
    fun resolve(resolution: TemporalResolution, trustMargin: Double = 0.15): ContradictionResolution {
        val selected = resolution.selected
            ?: return ContradictionResolution(false, reason = "No active fact is available")
        if (resolution.conflicts.isEmpty()) {
            return ContradictionResolution(true, selected, "No active contradiction")
        }
        val strongestConflict = resolution.conflicts.maxByOrNull { it.trust }!!
        val margin = selected.trust - strongestConflict.trust
        if (margin < trustMargin) {
            return ContradictionResolution(
                resolved = false,
                selected = null,
                reason = "Conflicting active facts are too close in trust to resolve safely",
                unresolved = listOf(selected, strongestConflict),
            )
        }
        return ContradictionResolution(
            resolved = true,
            selected = selected,
            reason = "Higher-trust active fact exceeds contradiction resolution margin",
        )
    }
}
