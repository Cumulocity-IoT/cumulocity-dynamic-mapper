/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.connector.kafka;

import org.apache.kafka.common.errors.TopicAuthorizationException;

import dynamic.mapping.core.ConnectorStatusEvent;
import dynamic.mapping.core.ConnectorStatus;

public class TopicConsumer {
	private final TopicConfig topicConfig;
	private final ConnectorStatusEvent connectorStatus;

	private ConsumingThread consumingThread; // guarded by this
	private boolean closed; // guarded by this

	public TopicConsumer(final TopicConfig topicConfig, ConnectorStatusEvent connectorStatus) {
		this.topicConfig = topicConfig;
		this.connectorStatus = connectorStatus;
	}

	public synchronized void start(final TopicConsumerListener listener) {
		if (closed) {
			throw new IllegalStateException("Closed");
		}

		if (consumingThread != null) {
			throw new IllegalStateException("Already started");
		}

		final ConsumingThread ct = new ConsumingThread(listener, connectorStatus);
		ct.start();
		consumingThread = ct;
	}

	public void stop() throws InterruptedException {
		final ConsumingThread ct;
		synchronized (this) {
			ct = consumingThread;

			if (ct == null) {
				return;
			}

			consumingThread = null;
		}

		ct.close();

		if (Thread.currentThread() != ct) {
			ct.join();
		}
	}

	public boolean shouldStop() {
		if (consumingThread == null)
			return true;
		return consumingThread.shouldStop;
	}

	public void close() throws InterruptedException {
		synchronized (this) {
			if (closed) {
				return;
			}
			closed = true;
		}

		stop();
	}

	private class ConsumingThread extends Thread {
		private static final int WAIT_MS_RECONNECT = 30000;
		private final TopicConsumerListener listener;
		private final ConnectorStatusEvent connectorStatus;
		private volatile boolean closed;
		boolean shouldStop = false;

		ConsumingThread(final TopicConsumerListener listener, ConnectorStatusEvent connectorStatus) {
			super("Consumer#" + topicConfig.getBootstrapServers() + "/" + topicConfig.getTopic());
			this.listener = listener;
			this.connectorStatus = connectorStatus;

		}

		@Override
		public void run() {
			Exception error = null;
			boolean continueToListen = true;

			while (continueToListen) {
				Topic tc = null;
				try {
					tc = new Topic(topicConfig);

					try {
						listener.onStarted();
					} catch (final Exception e) {
						// log ("Unexpected error while onStarted() notification", e);
					}

					// we consume the events from the topic until
					// this thread is interrupted by close()
					connectorStatus.updateStatus(ConnectorStatus.CONNECTED, true);
					tc.consumeUntilError(listener);
				} catch (final Exception e) {
					if (closed) {
						break;
					}
					error = e;
					if (error instanceof TopicAuthorizationException) {
						continueToListen = false;
						shouldStop = true;
					}
				} finally {
					if (tc != null) {
						try {
							tc.close();
						} catch (final Exception ignore) {
						}
					}
				}

				try {
					listener.onStoppedByErrorAndReconnecting(error);
				} catch (final Exception e) {
					// log ("Unexpected error while onStoppedByErrorAndReconnecting() notification",
					// e)
				}

				try {
					Thread.sleep(WAIT_MS_RECONNECT); // TODO: make the timeout configurable and use backoff with jitter
				} catch (final InterruptedException e) {
					break; // interrupted by close()
					// we don't restore the flag interrupted, since we still need
					// to do some additional work like
					// to notify listener.onStopped()
				}
			}

			try {
				listener.onStopped();
			} catch (final Exception e) {
				// log ("Unexpected error while onStoppedByErrorAndReconnecting() notification",
				// e);
			}
		}

		void close() {
			if (closed) { // no atomicity/membars required
				return; // since can be called only by one single thread
			}
			closed = true;

			// We stop the consuming with org.apache.kafka.common.errors.InterruptException
			// In here it isn't convenient to call Topic.close() directly to initiate
			// org.apache.kafka.common.errors.WakeupException, since we recreate
			// the instance of Topic and it takes additional efforts to share the
			// changeable reference to a Topic to close it from other thread.
			interrupt();
		}
	}
}