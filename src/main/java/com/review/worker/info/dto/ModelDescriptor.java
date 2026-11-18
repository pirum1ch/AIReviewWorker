package com.review.worker.info.dto;

/**
 * Describes a single model file found in the configured models directory.
 * {@code format} is one of {@code "GGUF"}, {@code "safetensors"}, {@code "other"}.
 * {@code lastModified} is an ISO-8601 instant string.
 */
public record ModelDescriptor(
        String name,
        String path,
        long sizeBytes,
        String format,
        String lastModified
) {}
