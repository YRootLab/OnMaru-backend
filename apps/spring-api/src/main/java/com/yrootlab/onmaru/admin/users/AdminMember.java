package com.yrootlab.onmaru.admin.users;

import java.time.Instant;
import java.util.UUID;

public record AdminMember(UUID id, String status, Instant createdAt, long reviewCount) {
}
