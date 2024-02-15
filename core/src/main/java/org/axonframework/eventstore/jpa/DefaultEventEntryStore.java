/*
 * Copyright (c) 2010-2014. Axon Framework
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

package org.axonframework.eventstore.jpa;

import org.axonframework.domain.DomainEventMessage;
import org.axonframework.serializer.SerializedDomainEventData;
import org.axonframework.serializer.SerializedObject;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * Implementation of the EventEntryStore that stores events in DomainEventEntry entities and snapshot events in
 * SnapshotEventEntry entities.
 * <p/>
 * This implementation requires that the aforementioned instances are available in the current persistence context.
 * <p/>
 * <em>Note: the SerializedType of Message Meta Data is not stored in this EventEntryStore. Upon retrieval,
 * it is set to the default value (name = "org.axonframework.domain.MetaData", revision = null). See {@link
 * org.axonframework.serializer.SerializedMetaData#isSerializedMetaData(org.axonframework.serializer.SerializedObject)}</em>
 *
 * @author Allard Buijze
 * @since 1.2
 */
public class DefaultEventEntryStore implements EventEntryStore {

    private static final Logger logger = LoggerFactory.getLogger(DefaultEventEntryStore.class);

    private final boolean forceUtc;

    /**
     * Initialize the Event Entry Store, storing timestamps in the system timezone.
     *
     * @see #DefaultEventEntryStore(boolean)
     */
    public DefaultEventEntryStore() {
        this(false);
    }

    /**
     * Initializes the EventEntryStore, with the possibility to force timestamps to be stored in UTC timezone. Although
     * it is strongly recommended to set this value to <code>true</code>, it defaults to <code>false</code>, for
     * backwards compatibility reasons.
     * <p/>
     * Providing <code>false</code> will store the timestamps in the system timezone.
     *
     * @param forceUtcTimestamp whether to store dates in UTC format.
     */
    public DefaultEventEntryStore(boolean forceUtcTimestamp) {
        this.forceUtc = forceUtcTimestamp;
    }


    @Override
    @SuppressWarnings({"unchecked"})
    public void persistEvent(String aggregateType, DomainEventMessage event, SerializedObject serializedPayload,
                             SerializedObject serializedMetaData, EntityManager entityManager) {
        entityManager.persist(createDomainEventEntry(aggregateType, event, serializedPayload, serializedMetaData));
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public SimpleSerializedDomainEventData loadLastSnapshotEvent(String aggregateType, Object identifier,
                                                                 EntityManager entityManager) {
        List<SimpleSerializedDomainEventData> entries = entityManager
                .createQuery("SELECT new org.axonframework.eventstore.jpa.SimpleSerializedDomainEventData("
                                     + "e.eventIdentifier, e.aggregateIdentifier, e.sequenceNumber, "
                                     + "e.timeStamp, e.payloadType, e.payloadRevision, e.payload, e.metaData) "
                                     + "FROM " + snapshotEventEntryEntityName() + " e "
                                     + "WHERE e.aggregateIdentifier = :id AND e.type = :type "
                                     + "ORDER BY e.sequenceNumber DESC")
                .setParameter("id", identifier.toString())
                .setParameter("type", aggregateType)
                .setMaxResults(1)
                .setFirstResult(0)
                .getResultList();
        if (entries.size() < 1) {
            return null;
        }
        return entries.get(0);
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public Iterator<SerializedDomainEventData> fetchFiltered(String whereClause, Map<String, Object> parameters,
                                                             int batchSize,
                                                             EntityManager entityManager) {
        return new BatchingIterator(whereClause, parameters, batchSize, domainEventEntryEntityName(), entityManager);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void persistSnapshot(String aggregateType, DomainEventMessage snapshotEvent,
                                SerializedObject serializedPayload, SerializedObject serializedMetaData,
                                EntityManager entityManager) {
        entityManager.persist(createSnapshotEventEntry(aggregateType,
                                                       snapshotEvent,
                                                       serializedPayload,
                                                       serializedMetaData));
    }

    /**
     * Allows for customization of the DomainEventEntry to store. Subclasses may choose to override this method to
     * use a different entity configuration.
     * <p/>
     * When overriding this method, also make sure the {@link #domainEventEntryEntityName()} method is overridden to
     * return the correct entity name.
     *
     * @param aggregateType      The type identifier of the aggregate
     * @param event              The event to be stored
     * @param serializedPayload  The serialized payload of the event
     * @param serializedMetaData The serialized meta data of the event
     * @return a JPA entity, ready to be stored using the entity manager
     *
     * @see #domainEventEntryEntityName()
     */
    protected DomainEventEntry createDomainEventEntry(String aggregateType, DomainEventMessage event,
                                                      SerializedObject<byte[]> serializedPayload,
                                                      SerializedObject<byte[]> serializedMetaData) {
        if (forceUtc) {
            return new DomainEventEntry(aggregateType, event, event.getTimestamp().toDateTime(DateTimeZone.UTC),
                                        serializedPayload, serializedMetaData);
        }
        return new DomainEventEntry(aggregateType, event, serializedPayload, serializedMetaData);
    }

    /**
     * Allows for customization of the SnapshotEventEntry to store. Subclasses may choose to override this method to
     * use a different entity configuration.
     * <p/>
     * When overriding this method, also make sure the {@link #snapshotEventEntryEntityName()} method is overridden to
     * return the correct entity name.
     *
     * @param aggregateType      The type identifier of the aggregate
     * @param snapshotEvent      The snapshot event to be stored
     * @param serializedPayload  The serialized payload of the event
     * @param serializedMetaData The serialized meta data of the event
     * @return a JPA entity, ready to be stored using the entity manager
     *
     * @see #snapshotEventEntryEntityName()
     */
    protected SnapshotEventEntry createSnapshotEventEntry(String aggregateType, DomainEventMessage snapshotEvent,
                                                          SerializedObject<byte[]> serializedPayload,
                                                          SerializedObject<byte[]> serializedMetaData) {
        return new SnapshotEventEntry(aggregateType, snapshotEvent, serializedPayload,
                                      serializedMetaData);
    }

    /**
     * The name of the DomainEventEntry entity to use when querying for domain events.
     *
     * @return The entity name of the DomainEventEntry subclass to use
     *
     * @see #createDomainEventEntry(String, org.axonframework.domain.DomainEventMessage,
     * org.axonframework.serializer.SerializedObject, org.axonframework.serializer.SerializedObject)
     */
    protected String domainEventEntryEntityName() {
        return "DomainEventEntry";
    }

    /**
     * The name of the SnapshotEventEntry entity to use when querying for snapshot events.
     *
     * @return The entity name of the SnapshotEventEntry subclass to use
     *
     * @see #createSnapshotEventEntry(String, org.axonframework.domain.DomainEventMessage,
     * org.axonframework.serializer.SerializedObject, org.axonframework.serializer.SerializedObject)
     */
    protected String snapshotEventEntryEntityName() {
        return "SnapshotEventEntry";
    }

    @Override
    public void pruneSnapshots(String type, DomainEventMessage mostRecentSnapshotEvent, int maxSnapshotsArchived,
                               EntityManager entityManager) {
        Iterator<Long> redundantSnapshots = findRedundantSnapshots(type, mostRecentSnapshotEvent,
                                                                   maxSnapshotsArchived, entityManager);
        if (redundantSnapshots.hasNext()) {
            Long sequenceOfFirstSnapshotToPrune = redundantSnapshots.next();
            entityManager.createQuery("DELETE FROM " + snapshotEventEntryEntityName() + " e "
                                              + "WHERE e.type = :type "
                                              + "AND e.aggregateIdentifier = :aggregateIdentifier "
                                              + "AND e.sequenceNumber <= :sequenceOfFirstSnapshotToPrune")
                         .setParameter("type", type)
                         .setParameter("aggregateIdentifier",
                                       mostRecentSnapshotEvent.getAggregateIdentifier().toString())
                         .setParameter("sequenceOfFirstSnapshotToPrune", sequenceOfFirstSnapshotToPrune)
                         .executeUpdate();
        }
    }

    /**
     * Finds the first of redundant snapshots, returned as an iterator for convenience purposes.
     *
     * @param type                 the type of the aggregate for which to find redundant snapshots
     * @param snapshotEvent        the last appended snapshot event
     * @param maxSnapshotsArchived the number of snapshots that may remain archived
     * @param entityManager        the entityManager providing access to the data store
     * @return an iterator over the snapshots found
     */
    @SuppressWarnings({"unchecked"})
    private Iterator<Long> findRedundantSnapshots(String type, DomainEventMessage snapshotEvent,
                                                  int maxSnapshotsArchived,
                                                  EntityManager entityManager) {
        return entityManager.createQuery(
                "SELECT e.sequenceNumber FROM " + snapshotEventEntryEntityName() + " e "
                        + "WHERE e.type = :type AND e.aggregateIdentifier = :aggregateIdentifier "
                        + "ORDER BY e.sequenceNumber DESC"
        )
                            .setParameter("type", type)
                            .setParameter("aggregateIdentifier", snapshotEvent.getAggregateIdentifier().toString())
                            .setFirstResult(maxSnapshotsArchived)
                            .setMaxResults(1)
                            .getResultList().iterator();
    }

    @SuppressWarnings({"unchecked"})
    @Override
    public Iterator<SerializedDomainEventData> fetchAggregateStream(String aggregateType, Object identifier,
                                                                    long firstSequenceNumber,
                                                                    int batchSize, EntityManager entityManager) {

        return new BatchingAggregateStreamIterator(firstSequenceNumber, identifier, aggregateType, batchSize,
                                                   domainEventEntryEntityName(), entityManager);
    }

    private static final class BatchingAggregateStreamIterator implements Iterator<SerializedDomainEventData> {

        private int currentBatchSize;
        private Iterator<SerializedDomainEventData> currentBatch;
        private SerializedDomainEventData next;
        private final Object id;
        private final String typeId;
        private final int batchSize;
        private final String domainEventEntryEntityName;
        private final EntityManager entityManager;

        private BatchingAggregateStreamIterator(long firstSequenceNumber, Object id, String typeId, int batchSize,
                                                String domainEventEntryEntityName, EntityManager entityManager) {
            this.id = id;
            this.typeId = typeId;
            this.batchSize = batchSize;
            this.domainEventEntryEntityName = domainEventEntryEntityName;
            this.entityManager = entityManager;
            List<SerializedDomainEventData> firstBatch = fetchBatch(firstSequenceNumber);
            this.currentBatchSize = firstBatch.size();
            this.currentBatch = firstBatch.iterator();
            if (currentBatch.hasNext()) {
                next = currentBatch.next();
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public SerializedDomainEventData next() {
            SerializedDomainEventData current = next;
            if (next != null && !currentBatch.hasNext() && currentBatchSize >= batchSize) {
                logger.debug("Fetching new batch for Aggregate [{}]", id);
                List<SerializedDomainEventData> entries = fetchBatch(next.getSequenceNumber() + 1);

                currentBatchSize = entries.size();
                currentBatch = entries.iterator();
            }
            next = currentBatch.hasNext() ? currentBatch.next() : null;
            return current;
        }

        @SuppressWarnings("unchecked")
        private List<SerializedDomainEventData> fetchBatch(long firstSequenceNumber) {
            return entityManager.createQuery(
                    "SELECT new org.axonframework.eventstore.jpa.SimpleSerializedDomainEventData("
                            + "e.eventIdentifier, e.aggregateIdentifier, e.sequenceNumber, "
                            + "e.timeStamp, e.payloadType, e.payloadRevision, e.payload, e.metaData) "
                            + "FROM " + domainEventEntryEntityName + " e "
                            + "WHERE e.aggregateIdentifier = :id AND e.type = :type "
                            + "AND e.sequenceNumber >= :seq "
                            + "ORDER BY e.sequenceNumber ASC"
            )
                                .setParameter("id", id.toString())
                                .setParameter("type", typeId)
                                .setParameter("seq", firstSequenceNumber)
                                .setMaxResults(batchSize)
                                .getResultList();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Remove is not supported");
        }
    }

    private static class BatchingIterator implements Iterator<SerializedDomainEventData> {

        private int currentBatchSize;
        private Iterator<SerializedDomainEventData> currentBatch;
        private SerializedDomainEventData next;
        private SerializedDomainEventData lastItem;
        private final String whereClause;
        private final Map<String, Object> parameters;
        private final int batchSize;

        private final String domainEventEntryEntityName;
        private final EntityManager entityManager;

        public BatchingIterator(
                String whereClause, Map<String, Object> parameters, int batchSize, String domainEventEntryEntityName,
                EntityManager entityManager) {
            this.whereClause = whereClause;
            this.parameters = parameters;
            this.batchSize = batchSize;
            this.domainEventEntryEntityName = domainEventEntryEntityName;
            this.entityManager = entityManager;
            List<SerializedDomainEventData> firstBatch = fetchBatch();

            this.currentBatchSize = firstBatch.size();
            this.currentBatch = firstBatch.iterator();
            if (currentBatch.hasNext()) {
                next = currentBatch.next();
            }
        }

        @SuppressWarnings("unchecked")
        private List<SerializedDomainEventData> fetchBatch() {
            Map<String, Object> params = new HashMap<String, Object>(parameters);
            Query query = entityManager.createQuery(
                    String.format("SELECT new org.axonframework.eventstore.jpa.SimpleSerializedDomainEventData("
                                          + "e.eventIdentifier, e.aggregateIdentifier, e.sequenceNumber, "
                                          + "e.timeStamp, e.payloadType, e.payloadRevision, e.payload, e.metaData) "
                                          + "FROM " + domainEventEntryEntityName + " e %s ORDER BY e.timeStamp ASC, "
                                          + "e.sequenceNumber ASC, e.aggregateIdentifier ASC",
                                  buildWhereClause(params)
                    )
            )
                                       .setMaxResults(batchSize);
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof DateTime) {
                    value = entry.getValue().toString();
                }
                query.setParameter(entry.getKey(), value);
            }
            final List<SerializedDomainEventData> resultList = query.getResultList();
            if (!resultList.isEmpty()) {
                lastItem = resultList.get(resultList.size() - 1);
            }
            return resultList;
        }

        private String buildWhereClause(Map<String, Object> paramRegistry) {
            if (lastItem == null && whereClause == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder("WHERE ");
            if (lastItem != null) {
                // although this may look like a long and inefficient where clause, it is (so far) the fastest way
                // to find the next batch of items
                sb.append("((")
                  .append("e.timeStamp > :timestamp")
                  .append(") OR (")
                  .append("e.timeStamp = :timestamp AND e.sequenceNumber > :sequenceNumber")
                  .append(") OR (")
                  .append("e.timeStamp = :timestamp AND e.sequenceNumber = :sequenceNumber AND ")
                  .append("e.aggregateIdentifier > :aggregateIdentifier))");
                paramRegistry.put("timestamp", lastItem.getTimestamp());
                paramRegistry.put("sequenceNumber", lastItem.getSequenceNumber());
                paramRegistry.put("aggregateIdentifier", lastItem.getAggregateIdentifier());
            }
            if (whereClause != null && whereClause.length() > 0) {
                if (lastItem != null) {
                    sb.append(" AND (");
                }
                sb.append(whereClause);
                if (lastItem != null) {
                    sb.append(")");
                }
            }
            return sb.toString();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public SerializedDomainEventData next() {
            SerializedDomainEventData current = next;
            if (next != null && !currentBatch.hasNext() && currentBatchSize >= batchSize) {
                List<SerializedDomainEventData> entries = fetchBatch();

                currentBatchSize = entries.size();
                currentBatch = entries.iterator();
            }
            next = currentBatch.hasNext() ? currentBatch.next() : null;
            return current;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Not implemented yet");
        }
    }
}
