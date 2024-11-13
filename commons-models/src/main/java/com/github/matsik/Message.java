package com.github.matsik;

import jakarta.validation.constraints.Size;

public record Message(
        @Size(min = 1, max = 128) String sender,
        @Size(min = 1, max = 1024) String content
) {
}
