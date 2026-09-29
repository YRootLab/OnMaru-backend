package com.yrootlab.onmaru.admin.pipeline;

import java.util.UUID;

public interface AdminPipelinePort {
    AdminPipelineStatus status(String dataset);
    AdminPipelineRunResult run(String dataset);
}
