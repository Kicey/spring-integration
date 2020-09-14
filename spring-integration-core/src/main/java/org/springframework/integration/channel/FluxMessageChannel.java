/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.channel;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.util.Assert;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * The {@link AbstractMessageChannel} implementation for the
 * Reactive Streams {@link Publisher} based on the Project Reactor {@link Flux}.
 *
 * @author Artem Bilan
 * @author Gary Russell
 * @author Sergei Egorov
 *
 * @since 5.0
 */
public class FluxMessageChannel extends AbstractMessageChannel
		implements Publisher<Message<?>>, ReactiveStreamsSubscribableChannel {

	private final Sinks.Many<Message<?>> sink;

	private final FluxProcessor<Message<?>, Message<?>> processor;

	private final Sinks.Many<Boolean> subscribedSignal = Sinks.many().replay().limit(1);

	private final Disposable.Composite upstreamSubscriptions = Disposables.composite();

	private volatile boolean active = true;

	public FluxMessageChannel() {
		this.sink = Sinks.many().multicast().onBackpressureBuffer(1, false);
		this.processor = FluxProcessor.fromSink(this.sink);
	}

	@Override
	protected boolean doSend(Message<?> message, long timeout) {
		Assert.state(this.active && this.processor.hasDownstreams(),
				() -> "The [" + this + "] doesn't have subscribers to accept messages");
		long remainingTime = 0;
		if (timeout > 0) {
			remainingTime = timeout;
		}
		long parkTimeout = 10; // NOSONAR
		long parkTimeoutNs = TimeUnit.MILLISECONDS.toNanos(parkTimeout);
		while (this.active && !tryEmitMessage(message)) {
			remainingTime -= parkTimeout;
			if (timeout >= 0 && remainingTime <= 0) {
				return false;
			}
			LockSupport.parkNanos(parkTimeoutNs);
		}
		return true;
	}

	private boolean tryEmitMessage(Message<?> message) {
		switch (this.sink.tryEmitNext(message)) {
			case OK:
				return true;
			case FAIL_NON_SERIALIZED:
			case FAIL_OVERFLOW:
				return false;
			case FAIL_ZERO_SUBSCRIBER:
				throw new IllegalStateException("The [" + this + "] doesn't have subscribers to accept messages");
			case FAIL_TERMINATED:
			case FAIL_CANCELLED:
				throw new IllegalStateException("Cannot emit messages into the cancelled or terminated sink: "
						+ this.sink);
			default:
				throw new UnsupportedOperationException();
		}
	}

	@Override
	public void subscribe(Subscriber<? super Message<?>> subscriber) {
		this.processor
				.doFinally((s) -> this.subscribedSignal.tryEmitNext(this.processor.hasDownstreams()))
				.share()
				.subscribe(subscriber);
		this.subscribedSignal.tryEmitNext(this.processor.hasDownstreams());
	}

	@Override
	public void subscribeTo(Publisher<? extends Message<?>> publisher) {
		this.upstreamSubscriptions.add(
				Flux.from(publisher)
						.delaySubscription(this.subscribedSignal.asFlux().filter(Boolean::booleanValue).next())
						.publishOn(Schedulers.boundedElastic())
						.doOnNext((message) -> {
							try {
								if (!send(message)) {
									throw new MessageDeliveryException(message,
											"Failed to send message to channel '" + this);
								}
							}
							catch (Exception ex) {
								logger.warn("Error during processing event: " + message, ex);
							}
						})
						.subscribe());
	}

	@Override
	public void destroy() {
		this.active = false;
		this.subscribedSignal.tryEmitNext(false);
		this.upstreamSubscriptions.dispose();
		this.processor.onComplete();
		super.destroy();
	}

}
