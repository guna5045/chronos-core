package com.chronoscore.common.enums;

/**
 * Publication lifecycle status of a workflow version definition.
 */
public enum WorkflowVersionStatus {

    /**
     * Version is currently under construction and has not yet been validated or published.
     * New executions cannot be started from a DRAFT version.
     */
    DRAFT,

    /**
     * Version has passed DAG validation and is published.
     * Published versions are strictly immutable.
     * New executions can be started from this version.
     */
    PUBLISHED,

    /**
     * Version has been superseded or retired.
     * New executions cannot be started, but in-flight executions continue uninterrupted.
     */
    DEPRECATED
}
