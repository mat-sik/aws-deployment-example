package com.github.matsik;

import jakarta.validation.constraints.Min;

public record MessageCreated(@Min(0) Long offset) {
}
