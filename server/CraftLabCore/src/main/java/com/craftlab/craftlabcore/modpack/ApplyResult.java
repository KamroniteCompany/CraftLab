package com.craftlab.craftlabcore.modpack;

public final class ApplyResult {

    public enum Status { APPLIED, ALREADY_UP_TO_DATE, NOT_READY, VALIDATION_FAILED, APPLY_FAILED }

    private final Status status;
    private final String message;
    private final ModPackDiff diff;

    private ApplyResult(Status status, String message, ModPackDiff diff) {
        this.status = status;
        this.message = message;
        this.diff = diff;
    }

    public static ApplyResult success(ModPackDiff diff) {
        return new ApplyResult(Status.APPLIED, null, diff);
    }

    public static ApplyResult alreadyUpToDate() {
        return new ApplyResult(Status.ALREADY_UP_TO_DATE, null, null);
    }

    public static ApplyResult failure(Status status, String message) {
        return new ApplyResult(status, message, null);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public ModPackDiff getDiff() {
        return diff;
    }
}
