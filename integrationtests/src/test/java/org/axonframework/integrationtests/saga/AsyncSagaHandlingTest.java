/*
 * Copyright (c) 2010-2011. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.integrationtests.saga;

import org.axonframework.domain.AggregateIdentifier;
import org.axonframework.domain.UUIDAggregateIdentifier;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.saga.AssociationValue;
import org.axonframework.saga.repository.AbstractSagaRepository;
import org.junit.*;
import org.junit.runner.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * @author Allard Buijze
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {"/META-INF/spring/async-saga-context.xml"})
public class AsyncSagaHandlingTest {

    private static final int EVENTS_PER_SAGA = 100;
    private List<AggregateIdentifier> aggregateIdentifiers = new LinkedList<AggregateIdentifier>();

    @Autowired
    private EventBus eventBus;

    @Autowired
    private AbstractSagaRepository sagaRepository;

    @Qualifier("executor")
    @Autowired
    private ExecutorService executor;

    @Before
    public void setUp() {
        assertNotNull(eventBus);
        assertNotNull(sagaRepository);
        for (int t = 0; t < 10; t++) {
            aggregateIdentifiers.add(new UUIDAggregateIdentifier());
        }
    }

    @Test
    @DirtiesContext
    public void testInvokeRandomEvents() throws InterruptedException {
        for (int t = 0; t < EVENTS_PER_SAGA * aggregateIdentifiers.size(); t++) {
            eventBus.publish(new SagaTriggeringEvent(t,
                                                     aggregateIdentifiers.get(t % aggregateIdentifiers.size()),
                                                     "message" + (t / aggregateIdentifiers.size())));
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        sagaRepository.purgeCache();

        for (AggregateIdentifier id : aggregateIdentifiers) {
            validateSaga(id.asString());
        }
    }

    @DirtiesContext
    @Test
    public void testAssociationProcessingOrder() throws InterruptedException {
        UUID currentAssociation = UUID.randomUUID();
        eventBus.publish(new SagaTriggeringEvent(0, new UUIDAggregateIdentifier(currentAssociation), "message"));
        for (int t = 0; t < EVENTS_PER_SAGA; t++) {
            UUID newAssociation = UUID.randomUUID();
            eventBus.publish(new SagaAssociationChangingEvent(this,
                                                              currentAssociation.toString(),
                                                              newAssociation.toString()));
            currentAssociation = newAssociation;
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        Set<AsyncSaga> result = sagaRepository.find(AsyncSaga.class, Collections.singleton(new AssociationValue(
                "currentAssociation",
                currentAssociation.toString())));
        assertEquals(1, result.size());
    }

    private void validateSaga(String myId) {
        Set<AsyncSaga> sagas = sagaRepository.find(AsyncSaga.class,
                                                   new HashSet<AssociationValue>(Arrays.asList(new AssociationValue(
                                                           "myId",
                                                           myId))));
        assertEquals(1, sagas.size());
        AsyncSaga saga = sagas.iterator().next();
        Iterator<String> messageIterator = saga.getReceivedMessages().iterator();
        for (int t = 0; t < EVENTS_PER_SAGA; t++) {
            assertEquals("Message out of order in saga " + saga.getSagaIdentifier(),
                         "message" + t,
                         messageIterator.next());
        }
    }
}