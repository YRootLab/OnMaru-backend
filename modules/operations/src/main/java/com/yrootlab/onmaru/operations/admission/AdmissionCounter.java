package com.yrootlab.onmaru.operations.admission;

import java.time.Instant;

record AdmissionCounter(Instant windowStart, int consumed) {
}
