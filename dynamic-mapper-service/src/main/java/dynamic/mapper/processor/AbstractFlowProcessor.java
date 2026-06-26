/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.processor;

import dynamic.mapper.processor.util.CamelHeaders;

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.camel.Exchange;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.flow.JavaScriptConsole;
import dynamic.mapper.processor.util.JavaScriptModuleStripper;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.OutputCollector;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.core.GraalVMContextService;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for FlowProcessor processors that provides common functionality
 * for executing JavaScript smart functions using GraalVM.
 *
 * Handles JavaScript code loading, execution, result processing, and GraalVM resource cleanup.
 */
@Slf4j
public abstract class AbstractFlowProcessor extends CommonProcessor {

    /**
     * Polyfill for browser-standard atob() / btoa() functions.
     * GraalVM's JS engine is ECMAScript-only — it does not include Web APIs.
     * The polyfill uses the already-sandboxed java.util.Base64 host class so no
     * additional host-class permissions are required.
     */
    private static final Source BASE64_POLYFILL_SOURCE = Source.newBuilder("js", """
            (function() {
              var _Base64   = Java.type('java.util.Base64');
              var _JString  = Java.type('java.lang.String');
              var _Charsets = Java.type('java.nio.charset.StandardCharsets');
              var _Arrays   = Java.type('java.util.Arrays');
              globalThis.atob = function(encoded) {
                return new _JString(_Base64.getDecoder().decode(encoded), _Charsets.UTF_8);
              };
              globalThis.btoa = function(plain) {
                // String.getBytes(Charset) has overload-resolution issues in GraalVM;
                // use Charset.encode(String) → ByteBuffer and extract exact bytes via Arrays.copyOfRange.
                var buf = _Charsets.UTF_8.encode(plain);
                return _Base64.getEncoder().encodeToString(
                  _Arrays.copyOfRange(buf.array(), buf.position(), buf.limit()));
              };
            })();
            """, "__base64_polyfill__.js")
            .cached(true)
            .buildLiteral();

    private static final ScheduledExecutorService JS_TIMEOUT_SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "js-cpu-timeout");
                t.setDaemon(true);
                return t;
            });

    protected final MappingService mappingService;
    protected final GraalVMContextService graalVMContextService;

    protected AbstractFlowProcessor(MappingService mappingService, GraalVMContextService graalVMContextService) {
        this.mappingService = mappingService;
        this.graalVMContextService = graalVMContextService;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        // Register a GraalVM cancel action on the wrapper (if present) so that a
        // TimeoutException in the MQTT callback can forcibly stop JS execution via
        // Context.close(cancelIfExecuting=true) — plain thread interruption is ignored by GraalVM.
        dynamic.mapper.processor.model.ProcessingResultWrapper<?> wrapper =
                exchange.getIn().getHeader(CamelHeaders.PROCESSING_RESULT_WRAPPER,
                        dynamic.mapper.processor.model.ProcessingResultWrapper.class);

        // ── Early-exit: cancellation was requested before this processor was even reached.
        // This happens when the MQTT timeout fires before the Camel route reaches the
        // FlowProcessor (cancel actions list was empty at cancel time, so nothing fired).
        if (wrapper != null && wrapper.getCancellationRequested().get()) {
            log.info("{} - Cancellation already requested before process() started, skipping JS execution for mapping: {}",
                    tenant, mapping.getName());
            return;
        }

        org.graalvm.polyglot.Context graalCtx = context.getGraalContext();
        Runnable cancelAction = null;
        if (wrapper != null && graalCtx != null) {
            // Capture context identity for diagnostics
            String contextId = Integer.toHexString(System.identityHashCode(graalCtx));
            log.debug("{} - Registering GraalVM cancel action for context: {} ({})", tenant, contextId, graalCtx.getClass().getSimpleName());

            cancelAction = () -> {
                log.debug("{} - GraalVM cancel action INVOKED on thread {}, closing context {}",
                        tenant, Thread.currentThread().getName(), contextId);
                try {
                    log.debug("{} - Calling graalCtx.close(true) to forcibly interrupt running JS", tenant);
                    graalCtx.close(true); // forcibly interrupt running JS
                    log.debug("{} - graalCtx.close(true) completed successfully", tenant);
                } catch (Exception e2) {
                    log.warn("{} - GraalVM context close(true) threw an exception: {} ({})",
                            tenant, e2.getClass().getSimpleName(), e2.getMessage(), e2);
                }
            };
            wrapper.addCancelAction(cancelAction);
        } else {
            log.warn("{} - Cannot register cancel action: wrapper={}, graalCtx={}",
                    tenant, wrapper != null, graalCtx != null);
        }

        try {
            // Set the wrapper on the context so processSmartMapping() can access it
            if (wrapper != null) {
                context.setProcessingResultWrapper(wrapper);
                log.debug("{} - ProcessingResultWrapper assigned to context", tenant);
            }
            log.debug("{} - Starting processSmartMapping on thread: {}", tenant, Thread.currentThread().getName());
            processSmartMapping(context);
            log.debug("{} - processSmartMapping completed successfully", tenant);
        } catch (Exception e) {
            // Salvage any console.log() messages written before the exception so they
            // are included in the test/error response even when processing fails.
            if (context.getFlowContext() != null) {
                OutputCollector salvage = new OutputCollector();
                extractLogs(context.getFlowContext(), salvage, tenant);
                if (!salvage.getLogs().isEmpty()) {
                    context.getLogs().addAll(salvage.getLogs());
                }
            }

            int lineNumber = extractJsLineNumber(e);
            String errorMessage = String.format("%s, line %s", e.getMessage(), lineNumber);

            // isCancelled() = killed by Context.close(true) (CPU timeout or wall-clock cancel)
            // isResourceExhausted() = future ResourceLimits enforcement; treat the same way
            boolean isKilled = e instanceof PolyglotException
                    && (((PolyglotException) e).isCancelled()
                            || ((PolyglotException) e).isResourceExhausted());
            if (isKilled) {
                log.warn("{} - JS execution forcibly stopped in {} for mapping {}: {}",
                        tenant, getProcessorName(), mapping.getName(), errorMessage);
            } else {
                log.error("{} - Error in {} for mapping {}: {}", tenant, getProcessorName(), mapping.getName(),
                        errorMessage, e);
            }

            handleProcessingError(e, errorMessage, context, tenant, mapping);
        } finally {
            // Unregister the GraalVM cancel action — context is about to be closed normally.
            if (wrapper != null && cancelAction != null) {
                wrapper.removeCancelAction(cancelAction);
            }
            // Close the Context completely
            if (context != null) {
                try {
                    context.close();
                } catch (Exception e) {
                    log.warn("{} - Error closing context in finally block: {}", tenant, e.getMessage());
                }
            }
        }
    }

    /**
     * Process smart mapping by executing JavaScript function.
     */
    public void processSmartMapping(ProcessingContext<?> context) throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();

        if (serviceConfiguration.getLogPayload() || mapping.getDebug()) {
            String payload = toPrettyJsonString(payloadObject);
            log.info("{} - Incoming payload (patched) in onMessage(): {}", tenant, payload);
        }

        if (mapping.getCode() != null) {
            Context graalContext = context.getGraalContext();

            // Use try-finally to ensure cleanup
            Value bindings = null;
            Value onMessageFunction = null;
            Value inputMessage = null;
            Value result = null;

            try {
                 // Task 1: Invoking JavaScript function
                 String identifier = Mapping.SMART_FUNCTION_NAME + "_" + mapping.getIdentifier();
                 bindings = graalContext.getBindings("js");

                 // Always provide console for JavaScript code
                 if (context.getFlowContext() != null) {
                    JavaScriptConsole console = new JavaScriptConsole(context.getFlowContext(), tenant, mapping);
                     bindings.putMember("console", console);
                 }

                  // Inject a cancellation checker object so JavaScript code can periodically check
                  // if processing has been cancelled and exit early.
                  // The ProcessingResultWrapper updates the cancellationRequested flag on timeout.
                  dynamic.mapper.processor.model.ProcessingResultWrapper<?> wrapper =
                          context.getProcessingResultWrapper();
                  if (wrapper != null) {
                      // Create a helper object that JS can call to check if it's been cancelled
                      Object cancellationHelper = new Object() {
                          @SuppressWarnings("unused")
                          public boolean isCancelled() {
                              boolean cancelled = wrapper.getCancellationRequested().get();
                              if (cancelled) {
                                  log.warn("{} - CANCELLATION CHECK: Code has been CANCELLED, returning true", tenant);
                              }
                              return cancelled;
                          }
                      };
                      bindings.putMember("__cancellationHelper", cancellationHelper);
                      log.debug("{} - Injected cancellation helper into JavaScript bindings for thread: {}",
                               tenant, Thread.currentThread().getName());
                  } else {
                      log.debug("{} - No ProcessingResultWrapper available, cancellation helper not injected", tenant);
                  }

                 // Load shared/system code first — populates globalThis with helpers/libraries
                 loadSharedCode(graalContext, context);

                byte[] decodedBytes = Base64.getDecoder().decode(mapping.getCode());
                String decodedCode = new String(decodedBytes);

                boolean supportESM = Boolean.TRUE.equals(serviceConfiguration.getSupportESM());
                Source source;
                if (supportESM) {
                    // ESM mode: keep export keywords, evaluate as ES module.
                    // The function is retrieved from the module namespace via
                    // js.esm-eval-returns-exports (enabled in createGraalContext).
                    source = Source.newBuilder("js", decodedCode, identifier + ".mjs")
                            .cached(true)
                            .buildLiteral();
                } else {
                    // Flat-script mode: strip ES module export/import statements so the code
                    // runs without SyntaxErrors, then wrap in an IIFE to scope any top-level
                    // declarations (e.g. `const globalConfig` from bundled libraries).
                    // No rename needed: each message gets a fresh GraalVM context, so there
                    // is no risk of onMessage() colliding with another mapping's function.
                    decodedCode = JavaScriptModuleStripper.toPlainScript(decodedCode);
                    String wrappedCode = "(function() {\n"
                            + decodedCode + "\n"
                            + "globalThis['" + Mapping.SMART_FUNCTION_NAME + "'] = " + Mapping.SMART_FUNCTION_NAME + ";\n"
                            + "})();";
                    source = Source.newBuilder("js", wrappedCode, identifier + ".js")
                            .cached(true)
                            .buildLiteral();
                }

                graalVMContextService.recordCompilation(tenant, source.getName(),
                        source.getCharacters().toString());

                if (supportESM) {
                    Value exports = graalContext.eval(source);
                    onMessageFunction = exports.getMember(Mapping.SMART_FUNCTION_NAME);
                } else {
                    graalContext.eval(source);
                    onMessageFunction = bindings.getMember(Mapping.SMART_FUNCTION_NAME);
                }

                 if (onMessageFunction == null || onMessageFunction.isNull()) {
                     if (supportESM) {
                     throw new ProcessingException(String.format(
                         "Function '%s' not found in mapping code. " +
                             "Ensure the script defines and exports a function named '%s' (for example: export { %s };).",
                         Mapping.SMART_FUNCTION_NAME, Mapping.SMART_FUNCTION_NAME, Mapping.SMART_FUNCTION_NAME));
                     }
                     throw new ProcessingException(String.format(
                         "Function '%s' not found in mapping code. " +
                             "Ensure the script defines a function named '%s'. " +
                             "Export is not required when supportESM is disabled.",
                         Mapping.SMART_FUNCTION_NAME, Mapping.SMART_FUNCTION_NAME));
                 }

                 inputMessage = createInputMessage(graalContext, context);

                 // Last chance to abort before handing control to JavaScript.
                 // Between registering the cancel action above and reaching this line,
                 // the timeout thread may have fired and set cancellationRequested.
                 dynamic.mapper.processor.model.ProcessingResultWrapper<?> wrapperCheck =
                         context.getProcessingResultWrapper();
                 if (wrapperCheck != null && wrapperCheck.getCancellationRequested().get()) {
                     log.warn("{} - Cancellation requested just before JS execute(), skipping for mapping: {}",
                             tenant, mapping.getName());
                     return;
                 }

                 // Enforce maxCPUTimeMS: schedule a hard kill via Context.close(true) so an
                 // infinite loop or runaway script cannot exceed the configured budget.
                 // Context.close(true) is the only reliable interrupt for CPU-bound GraalVM JS;
                 // plain thread interruption is ignored by the Truffle engine.
                 int maxCPUTimeMS = serviceConfiguration.getMaxCPUTimeMS() != null
                         ? serviceConfiguration.getMaxCPUTimeMS() : 0;
                 ScheduledFuture<?> cpuTimeoutFuture = null;
                 // Mutual exclusion between the timer and the finally-block: exactly one of
                 // the two will win the compareAndSet(false→true). Only the timer calls
                 // close(true) when it wins; if execute() completes first the timer sees
                 // true and skips. This prevents a false PolyglotException(isCancelled=true)
                 // in processResult() when JS finishes just as the deadline fires.
                 final java.util.concurrent.atomic.AtomicBoolean executionWindowClosed =
                         new java.util.concurrent.atomic.AtomicBoolean(false);
                 if (maxCPUTimeMS > 0) {
                     final Context graalCtxRef = graalContext;
                     final dynamic.mapper.processor.model.ProcessingResultWrapper<?> wrapperRef =
                             context.getProcessingResultWrapper();
                     cpuTimeoutFuture = JS_TIMEOUT_SCHEDULER.schedule(() -> {
                         if (!executionWindowClosed.compareAndSet(false, true)) return;
                         log.warn("{} - JS CPU time limit exceeded ({}ms), closing GraalVM context for mapping: {}",
                                 tenant, maxCPUTimeMS, mapping.getName());
                         // Signal cancellation so post-JS C8Y calls in SendInboundProcessor
                         // and C8YAgent.createMEAO() skip their requests.
                         if (wrapperRef != null) {
                             wrapperRef.getCancellationRequested().set(true);
                         }
                         try {
                             graalCtxRef.close(true);
                         } catch (Exception ex) {
                             log.debug("{} - GraalVM close(true) on CPU timeout threw: {}", tenant, ex.getMessage());
                         }
                     }, maxCPUTimeMS, TimeUnit.MILLISECONDS);
                 }
                 try {
                     result = onMessageFunction.execute(inputMessage, context.getFlowContext());
                 } finally {
                     executionWindowClosed.compareAndSet(false, true);
                     if (cpuTimeoutFuture != null) {
                         cpuTimeoutFuture.cancel(false);
                     }
                 }

                // Task 2: Extracting the result
                processResult(result, context, tenant);

            } finally {
                // Explicitly null out GraalVM Value references
                onMessageFunction = null;
                inputMessage = null;
                result = null;
                bindings = null;
            }
        }
    }

    /**
     * Load shared and system code into GraalVM context using cached Sources - OPTIMIZED!
     */
    protected void loadSharedCode(Context graalContext, ProcessingContext<?> context) {
        // Inject atob()/btoa() — GraalVM is ECMAScript-only; these are Web APIs not in the spec.
        graalContext.eval(BASE64_POLYFILL_SOURCE);

        // Use pre-cached Source if available - no decoding or parsing needed
        if (context.getSharedSource() != null) {
            graalContext.eval(context.getSharedSource());
        }

        // Also load system code if available
        if (context.getSystemSource() != null) {
            graalContext.eval(context.getSystemSource());
        }
    }

    /**
     * Extract warnings from the flow context into a List target.
     */
    protected void extractWarnings(DataPrepContext flowContext, List<String> target, String tenant) {
        Value warnings = null;
        try {
            warnings = flowContext.getState(DataPrepContext.WARNINGS);
            if (warnings != null && warnings.hasArrayElements()) {
                long size = warnings.getArraySize();
                for (long i = 0; i < size; i++) {
                    Value warningElement = null;
                    try {
                        warningElement = warnings.getArrayElement(i);
                        if (warningElement != null && warningElement.isString()) {
                            target.add(warningElement.asString());
                        }
                    } finally {
                        warningElement = null;
                    }
                }
                log.debug("{} - Collected {} warning(s) from flow execution", tenant, target.size());
            }
        } finally {
            warnings = null;
        }
    }

    /** Overload for callers that accumulate into an OutputCollector. */
    protected void extractWarnings(DataPrepContext flowContext, OutputCollector output, String tenant) {
        List<String> temp = new ArrayList<>();
        extractWarnings(flowContext, temp, tenant);
        temp.forEach(output::addWarning);
    }

    /**
     * Extract logs from the flow context into a List target.
     */
    protected void extractLogs(DataPrepContext flowContext, List<String> target, String tenant) {
        Value logs = null;
        try {
            logs = flowContext.getState(DataPrepContext.LOGS);
            if (logs != null && logs.hasArrayElements()) {
                long size = logs.getArraySize();
                for (long i = 0; i < size; i++) {
                    Value logElement = null;
                    try {
                        logElement = logs.getArrayElement(i);
                        if (logElement != null && logElement.isString()) {
                            target.add(logElement.asString());
                        }
                    } finally {
                        logElement = null;
                    }
                }
                log.debug("{} - Collected {} logs from flow execution", tenant, target.size());
            }
        } finally {
            logs = null;
        }
    }

    /** Overload for callers that accumulate into an OutputCollector. */
    protected void extractLogs(DataPrepContext flowContext, OutputCollector output, String tenant) {
        List<String> temp = new ArrayList<>();
        extractLogs(flowContext, temp, tenant);
        temp.forEach(output::addLog);
    }

    /**
     * Get processor name for error messages.
     * Subclasses should return their class name.
     */
    protected abstract String getProcessorName();

    /**
     * Create input message for JavaScript function.
     * Subclasses implement to create appropriate message type (DeviceMessage or CumulocityObject).
     */
    protected abstract Value createInputMessage(Context graalContext, ProcessingContext<?> context);

    /**
     * Process the result from JavaScript function execution.
     * Subclasses implement to handle their specific message types.
     */
    protected abstract void processResult(Value result, ProcessingContext<?> context, String tenant)
            throws ProcessingException;

    /**
     * Handle processing errors.
     * Subclasses can customize error handling (e.g., checking testing mode).
     */
    protected abstract void handleProcessingError(Exception e, String errorMessage,
            ProcessingContext<?> context, String tenant, Mapping mapping);

}
