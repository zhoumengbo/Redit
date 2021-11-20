/*
 * MIT License
 *
 * Copyright (c) 2021 SATE-Lab
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.redit.execution;

import io.redit.dsl.entities.Deployment;
import io.redit.dsl.events.internal.SchedulingEvent;
import io.redit.dsl.ReferableDeploymentEntity;
import io.redit.dsl.events.internal.BlockingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class EventService {
    private static Logger logger = LoggerFactory.getLogger(EventService.class);

    private ConcurrentHashMap<String, Boolean> eventCheckList;
    private final Deployment deployment;
    private Instant lastTimeEventReceived;

    EventService(Deployment deployment) {
        this.deployment = deployment;
        eventCheckList = new ConcurrentHashMap<>();
        lastTimeEventReceived = Instant.now();
        markEligibleBlockingEventsAsReceived();
    }

    public boolean hasEventReceived(String eventName) {
        return eventCheckList.containsKey(eventName) ? true : false;
    }

    public void receiveEvent(String eventName) {
        if (!eventCheckList.containsKey(eventName)) {
            eventCheckList.put(eventName, true);
            logger.info("Event " + eventName + " received!");
            lastTimeEventReceived = Instant.now();
            // if the dependencies of any block scheduling event is met, then mark it as received
            markEligibleBlockingEventsAsReceived();
        }
    }

    public void markEligibleBlockingEventsAsReceived() {
        for (SchedulingEvent schedulingEvent: deployment.getBlockingSchedulingEvents().values()) {
            if (!eventCheckList.containsKey(schedulingEvent.getName()) && areDependenciesMet(schedulingEvent.getName())) {
                logger.info("Event " + schedulingEvent.getName() + " received!");
                eventCheckList.put(schedulingEvent.getName(), true);
            }
        }
    }

    public boolean areDependenciesMet(String eventName) {
        return areDependenciesMet(eventName, 0);
    }

    public boolean areDependenciesMet(String eventName, Integer eventInclusion) {
        ReferableDeploymentEntity deploymentEntity = deployment.getReferableDeploymentEntity(eventName);
        if (deploymentEntity == null) {
            return false;
        }

        if (deploymentEntity.getDependsOn() == null) {
            return true;
        }

        String[] dependencies = deploymentEntity.getDependsOn().split(",");
        for (String dependency: dependencies) {
            if (!eventCheckList.containsKey(dependency)) {
                return false;
            }
        }

        if (eventInclusion == 0) {
            return true;
        } else {
            return eventCheckList.containsKey(eventName);
        }
    }

    public boolean areBlockDependenciesMet(String eventName) {
        BlockingEvent blockingEvent = deployment.getBlockingEvent(eventName);
        if (blockingEvent == null || !blockingEvent.isBlocking()) {
            return true;
        }

        if (blockingEvent.getBlockingCondition() == null) {
            return true;
        }

        String[] blockDependencies = blockingEvent.getBlockingCondition().split(",");
        for (String blockDependency: blockDependencies) {
            if (!eventCheckList.containsKey(blockDependency)) {
                return false;
            }
        }
        return true;
    }

    public boolean isTheRunSequenceCompleted() {
        for (String id: deployment.getRunSequence().split("\\W+")) {
            if (!eventCheckList.containsKey(id)) {
                return false;
            }
        }
        return true;
    }

    public boolean isLastEventReceivedTimeoutPassed(Integer nextEventReceiptTimeout) {
        if (isTheRunSequenceCompleted()) {
            return false;
        }

        if (nextEventReceiptTimeout == null) {
            return false;
        }

        if (Duration.between(lastTimeEventReceived, Instant.now()).getSeconds() >= nextEventReceiptTimeout) {
            return true;
        }
        return false;
    }
}
