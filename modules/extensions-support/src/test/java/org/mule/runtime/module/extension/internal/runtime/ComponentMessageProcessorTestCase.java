/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime;

import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.event.EventContextFactory.create;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.mule.test.allure.AllureConstants.ExecutionEngineFeature.EXECUTION_ENGINE;
import static org.mule.runtime.core.internal.policy.DefaultPolicyManager.noPolicyOperation;

import static java.util.Optional.of;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.Arrays.asList;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static reactor.core.publisher.Mono.from;
import static reactor.core.publisher.Mono.just;

import org.mule.runtime.api.exception.DefaultMuleException;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.EnrichableModel;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.XmlDslModel;
import org.mule.runtime.core.api.construct.Flow;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.expression.ExpressionRuntimeException;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.core.internal.exception.MessagingException;
import org.mule.runtime.core.internal.metadata.cache.MetadataCacheIdGeneratorFactory;
import org.mule.runtime.core.internal.message.InternalEvent;
import org.mule.runtime.core.internal.policy.PolicyManager;
import org.mule.runtime.core.internal.rx.FluxSinkRecorder;
import org.mule.runtime.core.privileged.processor.MessageProcessors;
import org.mule.runtime.extension.api.runtime.config.ConfigurationProvider;
import org.mule.runtime.module.extension.api.loader.java.property.CompletableComponentExecutorModelProperty;
import org.mule.runtime.module.extension.internal.runtime.operation.ComponentMessageProcessor;
import org.mule.runtime.module.extension.internal.runtime.resolver.ResolverSet;
import org.mule.runtime.module.extension.internal.runtime.resolver.ResolverSetResult;
import org.mule.runtime.module.extension.internal.runtime.resolver.ValueResolvingContext;
import org.mule.tck.junit4.AbstractMuleContextTestCase;

import java.util.ArrayList;
import java.util.List;

import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;


@Feature(EXECUTION_ENGINE)
@RunWith(Parameterized.class)
public class ComponentMessageProcessorTestCase extends AbstractMuleContextTestCase {

  private static final Logger LOGGER = LoggerFactory.getLogger(ComponentMessageProcessorTestCase.class);

  protected ComponentMessageProcessor<ComponentModel> processor;
  protected ExtensionModel extensionModel;
  protected ComponentModel componentModel;
  protected ResolverSet resolverSet;
  protected ExtensionManager extensionManager;

  protected PolicyManager mockPolicyManager;

  // A cached flow for creating test events. It doesn't even have to contain the processor we are going to test, because we will
  // be sending the events directly through the processor, without using the flow.
  private Flow testFlow;

  private final boolean isWithinProcessToApply;

  @Parameterized.Parameters(name = "Is within process to apply: {0}")
  public static List<Object[]> parameters() {
    return asList(
                  new Object[] {false},
                  new Object[] {true});
  }

  public ComponentMessageProcessorTestCase(boolean isWithinProcessToApply) {
    this.isWithinProcessToApply = isWithinProcessToApply;
  }

  @Before
  public void before() throws MuleException {
    extensionModel = mock(ExtensionModel.class);
    when(extensionModel.getXmlDslModel()).thenReturn(XmlDslModel.builder().setPrefix("mock").build());

    componentModel = mock(ComponentModel.class, withSettings().extraInterfaces(EnrichableModel.class));
    when((componentModel).getModelProperty(CompletableComponentExecutorModelProperty.class))
        .thenReturn(of(new CompletableComponentExecutorModelProperty(IdentityExecutor::create)));
    resolverSet = mock(ResolverSet.class);

    extensionManager = mock(ExtensionManager.class);
    mockPolicyManager = mock(PolicyManager.class);
    when(mockPolicyManager.createOperationPolicy(any(), any(), any())).thenReturn(noPolicyOperation());

    testFlow = getTestFlow(muleContext);
    initialiseIfNeeded(testFlow, muleContext);

    processor = createProcessor();
    processor.setAnnotations(getAppleFlowComponentLocationAnnotations());
    processor.setComponentLocator(componentLocator);
    processor.setCacheIdGeneratorFactory(mock(MetadataCacheIdGeneratorFactory.class));

    initialiseIfNeeded(processor, muleContext);
    startIfNeeded(processor);
  }

  @After
  public void after() throws MuleException {
    stopIfNeeded(processor);
    disposeIfNeeded(processor, LOGGER);

    stopIfNeeded(testFlow);
    disposeIfNeeded(testFlow, LOGGER);
  }

  protected ComponentMessageProcessor<ComponentModel> createProcessor() {
    return new TestComponentMessageProcessor(extensionModel,
                                             componentModel, null, null, null,
                                             resolverSet, null, null, null,
                                             null, extensionManager,
                                             mockPolicyManager, null, null,
                                             muleContext.getConfiguration().getShutdownTimeout()) {

      @Override
      protected void validateOperationConfiguration(ConfigurationProvider configurationProvider) {}

      @Override
      public ProcessingType getInnerProcessingType() {
        return ProcessingType.CPU_LITE;
      }
    };
  }

  @Override
  protected CoreEvent.Builder getEventBuilder() {
    // Overrides to avoid creating a new test flow for each new test event
    return InternalEvent.builder(create(testFlow, TEST_CONNECTOR_LOCATION));
  }

  @Test
  public void happyPath() throws MuleException {
    final ResolverSetResult resolverSetResult = mock(ResolverSetResult.class);
    when(resolverSet.resolve(any(ValueResolvingContext.class))).thenReturn(resolverSetResult);
    assertNotNull(from(processor.apply(just(testEvent())))
        .subscriberContext(ctx -> ctx.put(MessageProcessors.WITHIN_PROCESS_TO_APPLY, isWithinProcessToApply)).block());
  }

  @Test
  public void muleRuntimeExceptionInResolutionResult() throws MuleException {
    final Exception thrown = new ExpressionRuntimeException(createStaticMessage("Expected"));
    when(resolverSet.resolve(any(ValueResolvingContext.class))).thenThrow(thrown);
    assertMessagingExceptionCausedBy(thrown, isWithinProcessToApply);
  }

  @Test
  public void muleExceptionInResolutionResult() throws MuleException {
    final Exception thrown = new DefaultMuleException(createStaticMessage("Expected"));
    when(resolverSet.resolve(any(ValueResolvingContext.class))).thenThrow(thrown);
    assertMessagingExceptionCausedBy(thrown, isWithinProcessToApply);
  }

  @Test
  public void runtimeExceptionInResolutionResult() throws MuleException {
    final Exception thrown = new NullPointerException("Expected");
    when(resolverSet.resolve(any(ValueResolvingContext.class))).thenThrow(thrown);
    assertMessagingExceptionCausedBy(thrown, isWithinProcessToApply);
  }

  private void assertMessagingExceptionCausedBy(Exception thrown, boolean isWithinProcessToApply) throws MuleException {
    final List<Throwable> processingErrors = new ArrayList<>();
    FluxSinkRecorder<CoreEvent> emitter = new FluxSinkRecorder<>();
    emitter.next(testEvent());
    emitter.complete();

    Flux<CoreEvent> processorFlux =
        Flux.create(emitter).transform(objectFlux -> processor.apply(objectFlux)).onErrorContinue((throwable, o) -> {
          processingErrors.add(throwable);
        });

    Subscriber<CoreEvent> assertingSubscriber = new BaseSubscriber<CoreEvent>() {

      @Override
      protected void hookOnComplete() {
        super.hookOnComplete();
        assertThat("Only one error should be returned has part of the event processing", processingErrors, hasSize(1));
        assertThat("Error should be wrapper in a MessagingException", processingErrors,
                   contains(is(instanceOf(MessagingException.class))));
        assertThat("Error is not the expected one", processingErrors,
                   contains(hasProperty("cause", equalTo(thrown))));
      }
    };

    processorFlux.subscriberContext(ctx -> ctx.put(MessageProcessors.WITHIN_PROCESS_TO_APPLY, isWithinProcessToApply))
        .subscribeWith(assertingSubscriber);
  }

  @Test
  public void happyPathFluxPublisher() throws MuleException, InterruptedException {
    final ResolverSetResult resolverSetResult = mock(ResolverSetResult.class);
    when(resolverSet.resolve(any(ValueResolvingContext.class))).thenReturn(resolverSetResult);

    subscribeToParallelPublisherAndAwait(3);
  }

  @Test
  public void multipleUpstreamPublishers() throws MuleException, InterruptedException {
    final ResolverSetResult resolverSetResult = mock(ResolverSetResult.class);
    when(resolverSet.resolve(any(ValueResolvingContext.class))).thenReturn(resolverSetResult);

    InfiniteEmitter<CoreEvent> eventsEmitter = new InfiniteEmitter<>(this::newEvent);
    InfiniteEmitter<CoreEvent> eventsEmitter2 = new InfiniteEmitter<>(this::newEvent);
    ItemsConsumer<CoreEvent> eventsConsumer = new ItemsConsumer<>(10);
    ItemsConsumer<CoreEvent> eventsConsumer2 = new ItemsConsumer<>(3);

    Flux.create(eventsEmitter)
        .transform(processor)
        .subscriberContext(ctx -> ctx.put(MessageProcessors.WITHIN_PROCESS_TO_APPLY, isWithinProcessToApply))
        .subscribe(eventsConsumer);

    Flux.create(eventsEmitter2)
        .transform(processor)
        .subscriberContext(ctx -> ctx.put(MessageProcessors.WITHIN_PROCESS_TO_APPLY, isWithinProcessToApply))
        .subscribe(eventsConsumer2);

    eventsEmitter.start();
    eventsEmitter2.start();
    try {
      assertThat(eventsConsumer.await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));
      assertThat(eventsConsumer2.await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));
    } finally {
      eventsEmitter.stop();
      eventsEmitter2.stop();
    }
  }

  @Test
  @Issue("W-13563214")
  public void newSubscriptionAfterPreviousPublisherTermination() throws MuleException, InterruptedException {
    final ResolverSetResult resolverSetResult = mock(ResolverSetResult.class);
    when(resolverSet.resolve(any(ValueResolvingContext.class))).thenReturn(resolverSetResult);

    subscribeToParallelPublisherAndAwait(5);
    subscribeToParallelPublisherAndAwait(4);
  }

  private void subscribeToParallelPublisherAndAwait(int numEvents) throws InterruptedException {
    InfiniteEmitter<CoreEvent> eventsEmitter = new InfiniteEmitter<>(this::newEvent);
    ItemsConsumer<CoreEvent> eventsConsumer = new ItemsConsumer<>(numEvents);

    Flux.create(eventsEmitter)
        .transform(processor)
        .doOnNext(Assert::assertNotNull)
        .subscriberContext(ctx -> ctx.put(MessageProcessors.WITHIN_PROCESS_TO_APPLY, isWithinProcessToApply))
        .subscribe(eventsConsumer);

    eventsEmitter.start();
    try {
      assertThat(eventsConsumer.await(RECEIVE_TIMEOUT, MILLISECONDS), is(true));
    } finally {
      eventsEmitter.stop();
    }
  }

}
