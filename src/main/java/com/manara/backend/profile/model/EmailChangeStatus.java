package com.manara.backend.profile.model;

/** Where an email-change request stands. See V18 for what each state means. */
public enum EmailChangeStatus {
    PENDING,
    CONSUMED,
    SUPERSEDED,
    LOCKED
}
