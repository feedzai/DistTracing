/*
 * Copyright 2018 Feedzai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.feedzai.commons.tracing.engine;

import com.feedzai.commons.tracing.api.Promise;
import com.feedzai.commons.tracing.api.TraceContext;
import com.feedzai.commons.tracing.engine.configuration.BaseConfiguration;
import com.feedzai.commons.tracing.engine.opentracing.noop.NoopSpanImpl;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;


/**
 * Base implementation of the tracing functionality with eventID. his implementation relies on the OpenTracing API in
 * order to remain independent from the underlying tracing engine.
 *
 * @author Gonçalo Garcia (goncalo.garcia@feedzai.com)
 */
public abstract class AbstractOpenTracingEngine implements TracingEngine {

    /**
     * The Tracer object that will be used by the library. This is an OpenTracing API class so it does not make any
     * assumption regarding the underlying tracing engine.
     */
    protected final Tracer tracer;

    /**
     * Maps a traceID to the span that currently represents its point in the execution.
     */
    protected final Cache<String, LinkedList<Span>> spanIdMappings;

    /**
     * Maps a uniquely identifying object to an open span.
     */
    protected final Cache<Object, Span> responseMappings;

    /**
     * The rate at which requests are sampled
     */
    private final double samplingRate;


    /**
     * The logger.
     */
    static final Logger logger = LoggerFactory.getLogger(AbstractOpenTracingEngine.class.getName());

    /**
     * Constructor for this abstract class to be called by the extension classes to supply the implementation specific
     * parameters.
     *
     * @param tracer        The Tracer implementation of the underlying tracing Engine.
     * @param configuration The configuration parameters for the caches.
     */
    AbstractOpenTracingEngine(final Tracer tracer, final BaseConfiguration configuration) {
        this.tracer = tracer;
        this.samplingRate = configuration.getSamplingRate();
        this.spanIdMappings = CacheBuilder.newBuilder().expireAfterWrite(configuration.getCacheExpirationAfterWrite().toNanos(), TimeUnit.NANOSECONDS)
                .maximumSize(configuration.getCacheMaxSize()).build();
        this.responseMappings = CacheBuilder.newBuilder().expireAfterWrite(configuration.getCacheExpirationAfterWrite().toNanos(), TimeUnit.NANOSECONDS)
                .maximumSize(configuration.getCacheMaxSize()).weakKeys().build();
    }

    @Override
    public <R> R newProcess(final Supplier<R> toTrace, final String description, final TraceContext context) {
        final Span span = buildSpanFromAsyncContext(description, (SpanTraceContext) context, true);
        spanIdMappings.put(getTraceIdFromSpan(span), new LinkedList<>());
        updateSpanMappings(span);

        final R result;
        result = traceParentSafelyAndReturn(toTrace, span);
        return result;
    }

    @Override
    public void newProcess(final Runnable toTrace, final String description, final TraceContext context) {
        final Span span = buildSpanFromAsyncContext(description, (SpanTraceContext) context, true);
        spanIdMappings.put(getTraceIdFromSpan(span), new LinkedList<>());
        updateSpanMappings(span);

        traceParentSafely(toTrace, span);
    }

    @Override
    public <E extends Throwable, P extends Promise<R, P, E>, R> P newProcessPromise(final Supplier<P> toTrace, final String description,
                                                            final TraceContext context) {
        final Span span = buildSpanFromAsyncContext(description, (SpanTraceContext) context, true);
        spanIdMappings.put(getTraceIdFromSpan(span), new LinkedList<>());
        updateSpanMappings(span);

        return finishParentPromiseSpan(toTrace, span);
    }

    @Override
    public <R> CompletableFuture<R> newProcessFuture(final Supplier<CompletableFuture<R>> toTrace,
                                                     final String description,
                                                     final TraceContext context) {
        final Span span = buildSpanFromAsyncContext(description, (SpanTraceContext) context, true);
        spanIdMappings.put(getTraceIdFromSpan(span), new LinkedList<>());
        updateSpanMappings(span);
        return finishParentFutureSpan(toTrace.get(), span);
    }

    @Override
    public <E extends Throwable, P extends Promise<R, P, E>, R> P addToTraceOpenPromise(final Supplier<P> toTraceAsync, final Object object,
                                                                final String description,
                                                                final TraceContext context) {
        final Span span = buildSpanAsChild(description, (SpanTraceContext) context);
        responseMappings.put(object, span);
        return toTraceAsync.get();
    }

    @Override
    public <E extends Throwable, P extends Promise<R, P, E>, R> P addToTraceOpenPromise(final Supplier<P> toTraceAsync, final Object object,
                                                                final String description) {
        final Span span = buildSpan(description);
        responseMappings.put(object, span);
        return toTraceAsync.get();
    }

    @Override
    public <R> CompletableFuture<R> addToTraceOpenFuture(final Supplier<CompletableFuture<R>> toTraceAsync,
                                                         final Object object,
                                                         final String description,
                                                         final TraceContext context) {
        final Span span = buildSpanAsChild(description, (SpanTraceContext) context);
        responseMappings.put(object, span);
        return toTraceAsync.get();
    }

    @Override
    public <R> CompletableFuture<R> addToTraceOpenFuture(final Supplier<CompletableFuture<R>> toTraceAsync,
                                                         final Object object,
                                                         final String description) {
        final Span span = buildSpan(description);
        responseMappings.put(object, span);
        return toTraceAsync.get();
    }

    @Override
    public void addToTraceOpen(final Runnable toTraceAsync, final Object object, final String description,
                               final TraceContext context) {
        final Span span = buildSpanAsChild(description, (SpanTraceContext) context);
        final String traceId = getTraceIdFromSpan(span);
        cacheObject(object, span, traceId);
        toTraceAsync.run();
    }

    @Override
    public <R> R addToTraceOpen(final Supplier<R> toTraceAsync, final Object value, final String description,
                                final TraceContext context) {
        final Span span = buildSpanAsChild(description, (SpanTraceContext) context);
        final String traceId = getTraceIdFromSpan(span);
        cacheObject(value, span, traceId);
        return toTraceAsync.get();
    }

    @Override
    public void addToTraceOpen(final Runnable toTrace, final Object object, final String description) {
        final Span span = buildSpan(description);
        final String traceId = getTraceIdFromSpan(span);
        cacheObject(object, span, traceId);
        toTrace.run();
    }

    @Override
    public <R> R addToTraceOpen(final Supplier<R> toTrace, final Object value, final String description) {
        final Span span = buildSpan(description);
        final String traceId = getTraceIdFromSpan(span);
        cacheObject(value, span, traceId);
        return toTrace.get();
    }

    @Override
    public <R> R newTrace(final Supplier<R> toTrace, final String description) {
        final Span span = buildActiveParentSpan(description);
        final R result;
        result = traceParentSafelyAndReturn(toTrace, span);
        return result;
    }


    @Override
    public void newTrace(final Runnable toTrace, final String description) {
        final Span span = buildActiveParentSpan(description);
        traceParentSafely(toTrace, span);
    }

    @Override
    public <R> CompletableFuture<R> newTraceAsync(final Supplier<CompletableFuture<R>> toTraceAsync,
                                                  final String description) {
        final Span span = buildActiveParentSpan(description);
        return finishParentFutureSpan(toTraceAsync.get(), span);
    }


    @Override
    public <E extends Throwable, P extends Promise<R, P, E>, R> P newTracePromise(final Supplier<P> toTraceAsync, final String description) {
        final Span span = buildActiveParentSpan(description);
        return finishParentPromiseSpan(toTraceAsync, span);
    }


    @Override
    public <R> R addToTrace(final Supplier<R> toTrace, final String description) {
        final Span span = buildActiveSpan(description);
        return traceSafelyAndReturn(toTrace, span);
    }


    @Override
    public <R> R addToTrace(final Supplier<R> toTrace, final String description, final TraceContext context) {
        final Span span = buildActiveSpanAsChild(description, (SpanTraceContext) context);
        return traceSafelyAndReturn(toTrace, span);
    }

    @Override
    public void addToTrace(final Runnable toTrace, final String description) {
        final Span span = buildActiveSpan(description);
        traceSafely(toTrace, span);
    }


    @Override
    public void addToTrace(final Runnable toTrace, final String description, final TraceContext context) {
        final Span span = buildActiveSpanAsChild(description, (SpanTraceContext) context);
        traceSafely(toTrace, span);
    }

    @Override
    public <R> CompletableFuture<R> addToTraceAsync(final Supplier<CompletableFuture<R>> toTraceAsync,
                                                    final String description) {
        final Span span = buildActiveSpan(description);
        return finishFutureSpan(toTraceAsync.get(), span);
    }


    @Override
    public <R> CompletableFuture<R> addToTraceAsync(final Supplier<CompletableFuture<R>> toTraceAsync,
                                                    final String description, final TraceContext context) {
        final Span span = buildSpanFromAsyncContext(description, (SpanTraceContext) context, true);
        return finishFutureSpan(toTraceAsync.get(), span);
    }

    @Override
    public <E extends Throwable, P extends Promise<R, P, E>, R> P addToTracePromise(final Supplier<P> toTraceAsync, final String description) {
        final Span span = buildActiveSpan(description);
        return finishPromiseSpan(toTraceAsync, span);
    }


    @Override
    public <E extends Throwable, P extends Promise<R, P, E>, R> P addToTracePromise(final Supplier<P> toTraceAsync, final String description,
                                                            final TraceContext context) {
        final Span span = buildSpanFromAsyncContext(description, (SpanTraceContext) context, true);
        return finishPromiseSpan(toTraceAsync, span);
    }

    @Override
    public void closeOpen(final Object object) {
        final Span span = responseMappings.getIfPresent(object != null ? object : new Object());
        if (span != null) {
            span.finish();
            popSpanForTraceId(span);
        }
    }


    /**
     * Finishes span after the {@link CompletableFuture} has completed, either successfully or exceptionally.
     *
     * <p>Similar to {@link AbstractOpenTracingEngine#finishPromiseSpan(Supplier, Span)} but for {@link
     * CompletableFuture}
     *
     * @param toTraceAsync The {@link CompletableFuture} to which the callback will be attached.
     * @param span         The span that is wrapping the execution and should be finished.
     * @param <R>          The return type of the {@link CompletableFuture}
     * @return the same {@link CompletableFuture} that was passed in {@code toTraceAsync}
     */
    <R> CompletableFuture<R> finishFutureSpan(final CompletableFuture<R> toTraceAsync, final Span span) {
        toTraceAsync.handle((future, exception) -> {
            finishActive(span);
            popSpanForTraceId(span);
            return future;
        });
        return toTraceAsync;
    }

    protected void popSpanForTraceId(final Span span) {
        final LinkedList<Span> cached = spanIdMappings.getIfPresent(getTraceIdFromSpan(span));
        if (cached != null) {
            cached.remove(span);
        }
    }

    /**
     * Finishes span after the {@link CompletableFuture} has completed, either successfully or exceptionally.
     *
     * <p>Similar to {@link AbstractOpenTracingEngine#finishPromiseSpan(Supplier, Span)} but for {@link
     * CompletableFuture}
     *
     * @param toTraceAsync The {@link CompletableFuture} to which the callback will be attached.
     * @param span         The span that is wrapping the execution and should be finished.
     * @param <R>          The return type of the {@link CompletableFuture}
     * @return the same {@link CompletableFuture} that was passed in {@code toTraceAsync}
     */
    <R> CompletableFuture<R> finishParentFutureSpan(final CompletableFuture<R> toTraceAsync, final Span span) {
        toTraceAsync.handle((future, exception) -> {
            finishActive(span);
            return future;
        });
        return toTraceAsync;
    }

    /**
     * Attaches callback to finish span after the {@link Promise} has completed.
     *
     * <p>Similar to {@link AbstractOpenTracingEngine#finishFutureSpan(CompletableFuture, Span)} but for {@link
     * Promise}
     *
     * @param toTraceAsync The {@link Promise} to which the callback will be attached.
     * @param span         The span that is wrapping the execution and should be finished.
     * @return the same {@link Promise} that was passed in {@code toTraceAsync}
     */
    <E extends Throwable, P extends Promise<R, P, E>, R> P finishPromiseSpan(final Supplier<P> toTraceAsync, final Span span) {
        return toTraceAsync.get().onCompletePromise(x -> {
            finishActive(span);
            popSpanForTraceId(span);
        }).onErrorPromise(x -> {
            finishActive(span);
            popSpanForTraceId(span);
        });
    }

    /**
     * Attaches callback to finish span after the {@link Promise} has completed.
     *
     * <p>Similar to {@link AbstractOpenTracingEngine#finishFutureSpan(CompletableFuture, Span)} but for {@link
     * Promise}
     *
     * @param toTraceAsync The {@link Promise} to which the callback will be attached.
     * @param span         The span that is wrapping the execution and should be finished.
     * @return the same {@link Promise} that was passed in {@code toTraceAsync}
     */
    <E extends Throwable, P extends Promise<R, P, E>, R> P finishParentPromiseSpan(final Supplier<P> toTraceAsync, final Span span) {
        return toTraceAsync.get().onCompletePromise(x -> {
            span.finish();
        }).onErrorPromise(x -> {
            span.finish();
        });
    }

    /**
     * Closes the enclosing scope when the supplied is finished, regardless of whether it finished correctly or
     * exceptionally.
     *
     * <p>Similar to {@link AbstractOpenTracingEngine#traceSafely(Runnable, Span)} but returning a value.
     *
     * @param toTrace The method that should be executed and traced.
     * @param span    The tracing span that encloses this method.
     * @param <R>     The return type of the executed method.
     * @return The object that is returned by the executed method.
     */
    <R> R traceSafelyAndReturn(final Supplier<R> toTrace, final Span span) {
        R result;
        try {
            result = toTrace.get();
        } finally {
            finishActive(span);
            popSpanForTraceId(span);
        }
        return result;
    }

    /**
     * Closes the enclosing scope when the supplied is finished, regardless of whether it finished correctly or
     * exceptionally.
     *
     * <p>Similar to {@link AbstractOpenTracingEngine#traceSafely(Runnable, Span)} but returning a value.
     *
     * @param toTrace The method that should be executed and traced.
     * @param span    The tracing span that encloses this method.
     * @param <R>     The return type of the executed method.
     * @return The object that is returned by the executed method.
     */
    <R> R traceParentSafelyAndReturn(final Supplier<R> toTrace, final Span span) {
        R result;
        try {
            result = toTrace.get();
        } finally {
            span.finish();
        }
        return result;
    }

    /**
     * Closes the enclosing scope when the supplied is finished, regardless of whether it finished correctly or
     * exceptionally.
     *
     * <p>Similar to {@link AbstractOpenTracingEngine#traceSafelyAndReturn(Supplier, Span)} but returning nothing.
     *
     * @param toTrace The method that should be executed and traced.
     * @param span    The tracing span that encloses this method.
     */
    void traceSafely(final Runnable toTrace, final Span span) {
        try {
            toTrace.run();
        } finally {
            finishActive(span);
            popSpanForTraceId(span);
        }
    }

    /**
     * Closes the enclosing scope when the supplied is finished, regardless of whether it finished correctly or
     * exceptionally.
     *
     * <p>Similar to {@link AbstractOpenTracingEngine#traceSafelyAndReturn(Supplier, Span)} but returning nothing.
     *
     * @param toTrace The method that should be executed and traced.
     * @param span    The tracing span that encloses this method.
     */
    void traceParentSafely(final Runnable toTrace, final Span span) {
        try {
            toTrace.run();
        } finally {
            span.finish();
        }
    }


    /**
     * Creates a new Span as child of the passed {@link SpanContext} and activates it.
     *
     * @param description The description/name of the new context.
     * @param context     Represents the context of the current execution.
     * @return The new active Span.
     */
    private Span buildSpanFromAsyncContext(final String description, final SpanTraceContext context,
                                           final boolean activate) {
        final Span span = this.tracer.buildSpan(description).ignoreActiveSpan().asChildOf(context != null ? context.get() : null).start();
        span.setBaggageItem("thread-id", Long.toString(Thread.currentThread().getId()));
        if (activate) {
            this.tracer.scopeManager().activate(span, true);
        }
        updateSpanMappings(span);
        return span;
    }

    /**
     * When given a Span that represents a context update this method will update the mapping between the trace ID and
     * the current context.
     *
     * @param span Span that represents the current context.
     */
    protected void updateSpanMappings(final Span span) {
        final String traceId = getTraceIdFromSpan(span);
        final LinkedList<Span> spans = spanIdMappings.getIfPresent(traceId);
        logger.trace("Updating Span Mappings");
        if (spans != null && (spans.isEmpty() || (spans.peek() != null && !Long.toString(Thread.currentThread().getId()).equals(spans.peek().getBaggageItem("thread-id"))))) {
            try {
                spans.push(span);
            } catch (Exception e) {
                logger.error(e.getLocalizedMessage());
            }
        }
    }

    /**
     * Obtains a TraceID from a Span object.
     *
     * @param span Span from which we want the traceID.
     * @return A String representing the traceId.
     */
    protected abstract String getTraceIdFromSpan(final Span span);


    /**
     * Creates a new parent span (a span with no parents) and activates it.
     *
     * @param description The description or name that best describes this operation.
     * @return The new parent span
     */
    Span buildActiveParentSpan(final String description) {
        double rand = ThreadLocalRandom.current().nextDouble();
        if(rand <= samplingRate) {
            final Span span = this.tracer.buildSpan(description).ignoreActiveSpan().start();
            span.setBaggageItem("sampled", "true");
            spanIdMappings.put(getTraceIdFromSpan(span), new LinkedList<>());
            span.setBaggageItem("thread-id", Long.toString(Thread.currentThread().getId()));
            this.tracer.scopeManager().activate(span, true);
            updateSpanMappings(span);
            return span;
        }
            return new NoopSpanImpl();
    }

    /**
     * Creates a new Span that is a child of the passed {@link SpanTraceContext}, but does not activate it in the
     * current thread.
     *
     * @param description The description or name that best describes this operation.
     * @param context     The parent of this new span.
     * @return the new child Span.
     */
    Span buildSpanAsChild(final String description, final SpanTraceContext context) {
        if(StreamSupport.stream(context.get().baggageItems().spliterator(), false).anyMatch(e -> e.getKey().equals("sampled"))) {
            final Span span = buildSpanFromAsyncContext(description, context, false);
            span.setBaggageItem("thread-id", Long.toString(Thread.currentThread().getId()));
            updateSpanMappings(span);
            return span;
        }
        return new NoopSpanImpl();
    }

    /**
     * Creates a new Span that is a child of the passed {@link SpanTraceContext} and activates it in the current
     * thread.
     *
     * @param description The description or name that best describes this operation.
     * @param context     The parent of this new span.
     * @return the new child Span.
     */
    Span buildActiveSpanAsChild(final String description, final SpanTraceContext context) {
        if(context != null && StreamSupport.stream(context.get().baggageItems().spliterator(), false)
                                                    .anyMatch(e -> e != null && e.getKey().equals("sampled"))) {
            final Span span = buildSpanFromAsyncContext(description, context, true);
            span.setBaggageItem("thread-id", Long.toString(Thread.currentThread().getId()));
            this.tracer.scopeManager().activate(span, true);
            updateSpanMappings(span);
            return span;
        }
        return new NoopSpanImpl();
    }

    /**
     * Builds a span and activates it.
     *
     * @param description The description or name that best describes this operation.
     * @return the new active span.
     */
    private Span buildActiveSpan(final String description) {
        if(tracer.activeSpan().getBaggageItem("sampled") != null && tracer.activeSpan().getBaggageItem("sampled").equals("true")) {
            final Span span = this.tracer.buildSpan(description).start();
            span.setBaggageItem("thread-id", Long.toString(Thread.currentThread().getId()));
            this.tracer.scopeManager().activate(span, true);
            updateSpanMappings(span);
            return span;
        }
        return new NoopSpanImpl();
    }

    /**
     * Builds a span but does not activate it.
     *
     * @param description The description or name that best describes this operation.
     * @return the new active span.
     */
    private Span buildSpan(final String description) {
        if(tracer.activeSpan().getBaggageItem("sampled") != null && tracer.activeSpan().getBaggageItem("sampled").equals("true")) {
            final Span span = this.tracer.buildSpan(description).start();
            span.setBaggageItem("thread-id", Long.toString(Thread.currentThread().getId()));
            this.tracer.scopeManager().activate(span, false);
            updateSpanMappings(span);
            return span;
        }
        return new NoopSpanImpl();
    }

    /**
     * Stores this object in the {@code responseMappings} cache and updates {@code spanMappings} since it might span
     * multiple threads.
     *
     * @param object  The object used as key for the cache entry.
     * @param span    The span associated to the key.
     * @param traceId The traceId to lookup in {@code spanMappings}
     */
    void cacheObject(final Object object, final Span span, final String traceId) {
        if(span.getBaggageItem("sampled") != null && span.getBaggageItem("sampled").equals("true")) {
            LinkedList<Span> spans = spanIdMappings.getIfPresent(traceId);
            if (spans != null && spans.peek() != null && !spans.peek().equals(span)) {
                spanIdMappings.getIfPresent(traceId).push(span);
            }
            responseMappings.put(object, span);
        }
    }


    /**
     * Finishes a Span that is active and deactivates it.
     *
     * @param span The span to be finished.
     */
    private void finishActive(Span span) {
        if (tracer.activeSpan() != null && tracer.activeSpan().equals(span)) {
            tracer.scopeManager().active().close();
        } else {
            span.finish();
        }
    }


    @Override
    public boolean isActive() {
        return tracer.activeSpan() != null;
    }

    /**
     * Returns the current active content.
     *
     * @return The current active context
     */
    @Override
    public TraceContext<SpanContext> currentContext() {
        if (tracer.activeSpan() != null) {
            return new SpanTraceContext(tracer.activeSpan().context());
        } else {
            return null;
        }
    }

    /**
     * Returns the current context associated to an object.
     *
     * @param obj The object to be used as key.
     * @return The current context associated to the object.
     */
    @Override
    public TraceContext<SpanContext> currentContextforObject(final Object obj) {
        final Span span = responseMappings.getIfPresent(obj);
        if (span != null) {
            return new SpanTraceContext(span.context());
        } else {
            return null;
        }
    }


}
