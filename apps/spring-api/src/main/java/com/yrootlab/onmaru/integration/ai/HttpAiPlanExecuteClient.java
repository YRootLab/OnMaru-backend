package com.yrootlab.onmaru.integration.ai;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.integration.ai.plan.AiPlanWireCodec;
import com.yrootlab.onmaru.integration.ai.security.InternalAiOperationHeadersFactory;
import com.yrootlab.onmaru.integration.ai.security.InternalAiOperationHeadersFactory.Operation;
import com.yrootlab.onmaru.journey.worker.*;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import com.yrootlab.onmaru.journey.worker.plan.AiPlanExecuteClient;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One attempt per operation. The timeout covers both headers and the bounded response body. */
public final class HttpAiPlanExecuteClient implements AiPlanExecuteClient {
    private static final int MAX_BYTES = 262_144;
    private final HttpClient http;
    private final AiPlanWireCodec codec;
    private final URI base;
    private final Duration timeout;
    private final Clock clock;
    private final InternalAiOperationHeadersFactory headers;

    public HttpAiPlanExecuteClient(HttpClient http, AiPlanWireCodec codec, URI base, SecretProvider secrets, Duration timeout, Clock clock) {
        if (base == null || !java.util.Set.of("http","https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                || timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("invalid AI transport configuration");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) throw new IllegalArgumentException("AI redirect following is prohibited");
        this.http=http; this.codec=codec; this.base=base; this.timeout=timeout; this.clock=clock;
        this.headers=new InternalAiOperationHeadersFactory(secrets,clock);
    }

    @Override public PlanProposal plan(SafeQueryRequest request, CallContext context) {
        try {
            require(request.runId().equals(context.runId()));
            var plan=codec.readPlan(call(Operation.PLAN,request,context,request.versions(),null));
            require("ai-plan-proposal.v1".equals(plan.schemaVersion()) && request.runId().equals(plan.runId()) && request.versions().equals(plan.versions()));
            return plan;
        } catch (AiProposalException e) { throw e; } catch (RuntimeException e) { throw invalid(); }
    }

    @Override public ExecutionResult execute(ApprovedQueryPlan request, CallContext context, String progressToken) {
        try {
            require(request.runId().equals(context.runId()));
            require(request.approvedRoute().requiresLookup() == (request.queryGrantId()!=null && !request.queryGrantId().isBlank()));
            var result=codec.readResult(call(Operation.EXECUTE,request,context,request.versions(),progressToken));
            require("ai-execute-result.v1".equals(result.schemaVersion()) && request.runId().equals(result.runId())
                    && request.approvedPlanHash().equals(result.planHash()) && request.versions().equals(result.versions()));
            return result;
        } catch (AiProposalException e) { throw e; } catch (RuntimeException e) { throw invalid(); }
    }

    private byte[] call(Operation operation, Object body, CallContext context, Versions versions, String progressToken) {
        Duration remaining=Duration.between(clock.instant(),context.deadlineAt());
        if (remaining.isNegative() || remaining.isZero()) throw failure(WorkerDegradedReason.AI_TIMEOUT);
        Duration budget=remaining.compareTo(timeout)<0 ? remaining : timeout;
        final HttpRequest request;
        try {
            var builder=HttpRequest.newBuilder(base.resolve("/internal/v1/journey/"+(operation==Operation.PLAN?"plan":"execute")))
                    .timeout(budget).header("Content-Type","application/json").header("Accept","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(codec.write(body)));
            headers.headers(operation,context,versions.corpusRevision()).forEach(builder::header);
            if(progressToken!=null) {
                require(!progressToken.isBlank() && progressToken.length()<=4096 && !progressToken.contains("\r") && !progressToken.contains("\n"));
                builder.header("X-Run-Progress-Token",progressToken);
            }
            request=builder.build();
        } catch (RuntimeException e) { throw invalid(); }
        var activeBody=new AtomicReference<InputStream>();
        var task=new FutureTask<byte[]>(() -> {
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            activeBody.set(response.body());
            try(var input=response.body()) {
                if(response.statusCode()==429) throw failure(WorkerDegradedReason.AI_QUOTA_EXCEEDED);
                if(response.statusCode()!=200) throw failure(WorkerDegradedReason.AI_SERVICE_UNAVAILABLE);
                if(!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) throw invalid();
                byte[] bytes=input.readNBytes(MAX_BYTES+1);
                if(bytes.length>MAX_BYTES) throw invalid();
                return bytes;
            }
        });
        Thread.startVirtualThread(task);
        try { return task.get(Math.max(1,budget.toMillis()),TimeUnit.MILLISECONDS); }
        catch(TimeoutException e) { throw failure(WorkerDegradedReason.AI_TIMEOUT); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw failure(WorkerDegradedReason.AI_SERVICE_UNAVAILABLE); }
        catch(ExecutionException e) {
            if(e.getCause() instanceof AiProposalException typed) throw typed;
            if(e.getCause() instanceof HttpTimeoutException) throw failure(WorkerDegradedReason.AI_TIMEOUT);
            throw failure(WorkerDegradedReason.AI_SERVICE_UNAVAILABLE);
        } finally {
            task.cancel(true);
            var input=activeBody.get(); if(input!=null) try{input.close();}catch(java.io.IOException ignored){ }
        }
    }
    private static void require(boolean value) { if(!value) throw invalid(); }
    private static AiProposalException invalid() { return failure(WorkerDegradedReason.AI_INVALID_RESPONSE); }
    private static AiProposalException failure(WorkerDegradedReason reason) { return new AiProposalException(reason); }
}
