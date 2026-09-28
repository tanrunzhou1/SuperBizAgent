package org.example.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Single-case score response following Cloud-OpsBench metric names. */
public record CloudOpsBenchScoreResponse(String caseId, Scores scores) {

    @JsonPropertyOrder({
            "componentAccuracy",
            "faultAccuracy",
            "jointRcaAccuracy",
            "milestoneCoverage",
            "evidenceOrderConsistency",
            "evidenceEfficiency",
            "steps",
            "redundantActionRate"
    })
    public record Scores(
            double componentAccuracy,
            double faultAccuracy,
            double jointRcaAccuracy,
            double milestoneCoverage,
            double evidenceOrderConsistency,
            double evidenceEfficiency,
            int steps,
            double redundantActionRate) {
    }
}
