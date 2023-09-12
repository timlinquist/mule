/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.rx;

import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.util.MuleSystemProperties.MULE_PRINT_STACK_TRACES_ON_DROP;

import static java.lang.Boolean.getBoolean;

import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.publisher.Flux.create;
import static reactor.util.context.Context.empty;

import org.mule.runtime.api.exception.MuleRuntimeException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Utility class for using with {@link Flux#create(Consumer)}.
 *
 * @param <T> The type of values in the flux
 */
public class FluxSinkRecorder<T> implements Consumer<FluxSink<T>> {

  private static final Logger LOGGER = getLogger(FluxSinkRecorder.class);

  private volatile FluxSinkRecorderDelegate<T> delegate = new NotYetAcceptedDelegate<>();

  private static final boolean PRINT_STACK_TRACES_ON_DROP = getBoolean(MULE_PRINT_STACK_TRACES_ON_DROP);
  private volatile String completionStackTrace = null;
  private volatile String acceptStackTrace = null;

  public Flux<T> flux() {
    return create(this)
        .contextWrite(ctx -> empty());
  }

  @Override
  public void accept(FluxSink<T> fluxSink) {
    if (PRINT_STACK_TRACES_ON_DROP) {
      synchronized (this) {
        acceptStackTrace = getStackTraceAsString();
      }
    }
    FluxSinkRecorderDelegate<T> previousDelegate = this.delegate;
    delegate = new DirectDelegate<>(fluxSink);
    previousDelegate.accept(fluxSink);
  }

  public FluxSink<T> getFluxSink() {
    return delegate.getFluxSink();
  }

  public void next(T response) {
    if (PRINT_STACK_TRACES_ON_DROP) {
      synchronized (this) {
        if (completionStackTrace != null) {
          LOGGER.warn("Event will be dropped {}\nCompletion StackTrace:\n{}\nAccept StackTrace:\n{}", response,
                      completionStackTrace, acceptStackTrace);
        }
      }
    }
    delegate.next(response);
  }

  public void error(Throwable error) {
    if (PRINT_STACK_TRACES_ON_DROP) {
      synchronized (this) {
        completionStackTrace = getStackTraceAsString();
      }
    }
    delegate.error(error);
  }

  public void complete() {
    if (PRINT_STACK_TRACES_ON_DROP) {
      synchronized (this) {
        completionStackTrace = getStackTraceAsString();
      }
    }
    delegate.complete();
  }

  private String getStackTraceAsString() {
    StringBuilder sb = new StringBuilder();
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    for (StackTraceElement element : stackTrace) {
      sb.append('\t').append(element).append('\n');
    }
    return sb.toString();
  }

  private interface FluxSinkRecorderDelegate<T> extends Consumer<FluxSink<T>> {

    FluxSink<T> getFluxSink();

    void next(T response);

    void error(Throwable error);

    void complete();

  }

  private static class NotYetAcceptedDelegate<T> implements FluxSinkRecorderDelegate<T> {

    private volatile CompletableFuture<FluxSink<T>> futureFluxSink = new CompletableFuture<>();
    // If a fluxSink as not yet been accepted, events are buffered until one is accepted
    private final List<Runnable> bufferedEvents = new ArrayList<>();

    @Override
    public void accept(FluxSink<T> fluxSink) {
      synchronized (this) {
        futureFluxSink.complete(fluxSink);
      }
      bufferedEvents.forEach(Runnable::run);
    }

    @Override
    public FluxSink<T> getFluxSink() {
      try {
        return futureFluxSink.get();
      } catch (Exception e) {
        throw new MuleRuntimeException(createStaticMessage("Error while waiting for subscription", e));
      }
    }

    @Override
    public void next(T response) {
      handle(response, this::emitBufferedElement);
    }

    private <V> void handle(V element, Consumer<V> elementConsumer) {
      boolean present = true;
      synchronized (this) {
        if (!futureFluxSink.isDone()) {
          present = false;
          bufferedEvents.add(() -> elementConsumer.accept(element));
        }
      }

      if (present) {
        elementConsumer.accept(element);
      }
    }

    private void emitBufferedElement(T element) {
      try {
        futureFluxSink.get().next(element);
      } catch (Exception e) {
        throw new MuleRuntimeException(createStaticMessage("Error while submitting buffered event", e));
      }
    }

    private void emitBufferedError(Throwable error) {
      try {
        futureFluxSink.get().error(error);
      } catch (Exception e) {
        throw new MuleRuntimeException(createStaticMessage("Error while submitting buffered error", e));
      }
    }

    private void emitCompletion() {
      try {
        futureFluxSink.get().complete();
      } catch (Exception e) {
        throw new MuleRuntimeException(createStaticMessage("Error while submitting buffered error", e));
      }
    }

    @Override
    public void error(Throwable error) {
      handle(error, this::emitBufferedError);
    }

    @Override
    public void complete() {
      handle(null, alwaysNull -> emitCompletion());
    }
  }

  private static class DirectDelegate<T> implements FluxSinkRecorderDelegate<T> {

    private final FluxSink<T> fluxSink;

    DirectDelegate(FluxSink<T> fluxSink) {
      this.fluxSink = fluxSink;
    }

    @Override
    public void accept(FluxSink<T> t) {
      // Nothing to do
    }

    @Override
    public FluxSink<T> getFluxSink() {
      return fluxSink;
    }

    @Override
    public void next(T response) {
      fluxSink.next(response);
    }

    @Override
    public void error(Throwable error) {
      fluxSink.error(error);
    }

    @Override
    public void complete() {
      fluxSink.complete();
    }

  }
}
