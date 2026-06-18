package com.telecom.bccs.catalog.model;

import org.springframework.data.domain.Page;

import java.util.List;

/** Stable JSON page envelope for 3rd-party callers (decoupled from Spring's Page internals). */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PagedResponse<T> from(Page<T> p) {
        return new PagedResponse<>(p.getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }
}
