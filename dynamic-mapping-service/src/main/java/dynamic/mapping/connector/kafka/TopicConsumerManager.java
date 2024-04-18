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

import java.util.HashMap;
    import java.util.Map;
    import java.util.concurrent.BlockingQueue;
    import java.util.concurrent.LinkedBlockingQueue;

    public class TopicConsumerManager {
        private final CommandExecutor commandExecutor;

        private volatile BlockingQueue<Object> commands = new LinkedBlockingQueue<>(10_000); // some reasonable limit
        // just to prevent possible OOM

        public TopicConsumerManager() {
            commandExecutor = new CommandExecutor(commands);
            commandExecutor.start();
        }

        public void execute(final Object command) throws InterruptedException {
            final BlockingQueue<Object> cms = commands;
            if (cms == null) { // closed
                return;
            }
            cms.put(command);
        }

        public void close() throws InterruptedException {
            synchronized (this) {
                if (commands == null) {
                    return;
                }
                commands = null;
            }

            commandExecutor.interrupt();

            if (Thread.currentThread() != commandExecutor) {
                commandExecutor.join();
            }
        }

        private class CommandExecutor extends Thread {
            private final Map<String, TopicConsumer> consumers = new HashMap<>();
            private final BlockingQueue<Object> commands;

            public CommandExecutor(final BlockingQueue<Object> commands) {
                super("TopicConsumerManager");
                this.commands = commands;
            }

            @Override
            public void run() {
                try {
                    while (true) {
                        final Object command = commands.take();
                        try {
                            if (command instanceof MakeTopicConsumer) {
                                final MakeTopicConsumer makeTc = (MakeTopicConsumer) command;

                                final TopicConsumer oldTc = consumers.get(makeTc.getConfig().getTopic());
                                if (oldTc != null) {
                                    oldTc.close();
                                }

                                final TopicConsumer newTc = new TopicConsumer(makeTc.getConfig());
                                consumers.put(makeTc.getConfig().getTopic(), newTc);

                                newTc.start(makeTc.getListener());
                            }
                        } catch (final InterruptedException e) {
                            throw e; // push the exception up to break the loop
                        } catch (final Exception e) {
                            // log ("An error while executing: " + command, e)
                        }
                    }
                } catch (final InterruptedException ignore) {
                    // we don't need to restore interrupted() flag, since
                    // we need to do additional job - to close
                    // all started consumers
                } finally {
                    // close all existing consumers
                    for (TopicConsumer tc : consumers.values()) {
                        try {
                            tc.close();
                        } catch (final Exception ignore) {
                        }
                    }
                }
            }
        }
    }
