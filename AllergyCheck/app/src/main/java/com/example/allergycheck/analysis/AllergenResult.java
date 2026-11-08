package com.example.allergycheck.analysis;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/** The outcome for a single allergen the user asked to check, with all supporting evidence. */
public final class AllergenResult implements Serializable {

    private static final long serialVersionUID = 2L;

    private final Allergen allergen;
    private final AnalysisReport.Status status;
    private final List<Finding> findings;

    AllergenResult(Allergen allergen, AnalysisReport.Status status, List<Finding> findings) {
        this.allergen = allergen;
        this.status = status;
        this.findings = Collections.unmodifiableList(findings);
    }

    public Allergen allergen() {
        return allergen;
    }

    /** CONTAINS, MAY_CONTAIN, POSSIBLE, NOT_DETECTED — or UNREADABLE when the whole label was. */
    public AnalysisReport.Status status() {
        return status;
    }

    /** Evidence, strongest first. Empty for NOT_DETECTED and UNREADABLE. */
    public List<Finding> findings() {
        return findings;
    }
}
