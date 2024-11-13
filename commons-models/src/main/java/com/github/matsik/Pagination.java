package com.github.matsik;

import jakarta.validation.constraints.Min;

public record Pagination(@Min(0) int offset, @Min(1) int limit) {
}
