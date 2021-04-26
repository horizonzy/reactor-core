/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Exceptions;
import reactor.core.Scannable;
import reactor.core.scheduler.Scheduler;
import reactor.util.annotation.Nullable;

/**
 * Emits a single 0L value delayed by some time amount with a help of
 * a ScheduledExecutorService instance or a generic function callback that
 * wraps other form of async-delayed execution of tasks.
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class MonoDelay extends Mono<Long> implements Scannable,  SourceProducer<Long>  {

	final Scheduler timedScheduler;

	final long delay;

	final TimeUnit unit;

	MonoDelay(long delay, TimeUnit unit, Scheduler timedScheduler) {
		this.delay = delay;
		this.unit = Objects.requireNonNull(unit, "unit");
		this.timedScheduler = Objects.requireNonNull(timedScheduler, "timedScheduler");
	}

	@Override
	public void subscribe(CoreSubscriber<? super Long> actual) {
		boolean failOnBackpressure = actual.currentContext().getOrDefault(CONTEXT_OPT_OUT_NOBACKPRESSURE, false) == Boolean.TRUE;

		MonoDelayRunnable r = new MonoDelayRunnable(actual, failOnBackpressure);

		actual.onSubscribe(r);

		try {
			r.setCancel(timedScheduler.schedule(r, delay, unit));
		}
		catch (RejectedExecutionException ree) {
			if(r.cancel != OperatorDisposables.DISPOSED) {
				actual.onError(Operators.onRejectedExecution(ree, r, null, null,
						actual.currentContext()));
			}
		}
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_ON) return timedScheduler;
		if (key == Attr.RUN_STYLE) return Attr.RunStyle.ASYNC;

		return null;
	}

	static final class MonoDelayRunnable implements Runnable, InnerProducer<Long> {
		final CoreSubscriber<? super Long> actual;
		final boolean failOnBackpressure;

		volatile Disposable cancel;
		static final AtomicReferenceFieldUpdater<MonoDelayRunnable, Disposable> CANCEL =
				AtomicReferenceFieldUpdater.newUpdater(MonoDelayRunnable.class,
						Disposable.class,
						"cancel");

		volatile boolean requested;

		static final Disposable FINISHED = Disposables.disposed();
		static final Disposable DONE_BEFORE_REQUEST = Disposables.disposed();

		MonoDelayRunnable(CoreSubscriber<? super Long> actual, boolean failOnBackpressure) {
			this.actual = actual;
			this.failOnBackpressure = failOnBackpressure;
		}

		public void setCancel(Disposable cancel) {
			if (!CANCEL.compareAndSet(this, null, cancel)) {
				cancel.dispose();
			}
		}

		@Override
		public CoreSubscriber<? super Long> actual() {
			return actual;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.TERMINATED) return cancel == FINISHED;
			if (key == Attr.CANCELLED) return cancel == OperatorDisposables.DISPOSED;
			if (key == Attr.RUN_STYLE) return Attr.RunStyle.ASYNC;

			return InnerProducer.super.scanUnsafe(key);
		}

		private void delayDone() {
			try {
				actual.onNext(0L);
				actual.onComplete();
			}
			catch (Throwable t){
				actual.onError(Operators.onOperatorError(t, actual.currentContext()));
			}
		}

		@Override
		public void run() {
			if (requested) {
				if (CANCEL.getAndSet(this, FINISHED) != OperatorDisposables.DISPOSED) {
					delayDone();
				}
			} else if (failOnBackpressure) {
				actual.onError(Exceptions.failWithOverflow("Could not emit value due to lack of requests"));
			}
			else {
				for(;;) {
					//either null or Disposable from scheduling
					//can't be FINISHED, can't be DONE_BEFORE_REQUEST
					Disposable c = CANCEL.get(this);
					if (c == OperatorDisposables.DISPOSED //cancelled
							|| CANCEL.compareAndSet(this, c, DONE_BEFORE_REQUEST)) { //managed to update
						return;
					}
				}
			}
		}

		@Override
		public void cancel() {
			Disposable c = cancel;
			//we'll allow turning DONE_BEFORE_REQUEST to CANCELLED
			//(note that DONE_BEFORE_REQUEST can be disposed, it is a NO-OP)
			if (c != OperatorDisposables.DISPOSED && c != FINISHED) {
				c =  CANCEL.getAndSet(this, OperatorDisposables.DISPOSED);
				if (c != null && c != OperatorDisposables.DISPOSED && c != FINISHED) {
					c.dispose();
				}
			}
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				requested = true;
				if (!failOnBackpressure && CANCEL.compareAndSet(this, DONE_BEFORE_REQUEST, FINISHED)) {
					delayDone();
				}
			}
		}
	}

	private static final String CONTEXT_OPT_OUT_NOBACKPRESSURE = "reactor.core.publisher.MonoDelay.failOnBackpressure";
}
