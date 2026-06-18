package com.telecom.bccs.management.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Inbound CRUD payload for create/update. */
public record ProductRequest(
        @NotBlank String code,
        @NotBlank String name,
        String category,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE|RETIRED") String status,
        String description
) {
}
