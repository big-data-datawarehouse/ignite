/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.mvcc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.cluster.ClusterTopologyCheckedException;
import org.apache.ignite.internal.managers.communication.GridMessageListener;
import org.apache.ignite.internal.managers.discovery.DiscoCache;
import org.apache.ignite.internal.managers.eventstorage.GridLocalEventListener;
import org.apache.ignite.internal.processors.cache.GridCacheSharedManagerAdapter;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.events.EventType.EVT_NODE_FAILED;
import static org.apache.ignite.events.EventType.EVT_NODE_LEFT;
import static org.apache.ignite.internal.GridTopic.TOPIC_CACHE_COORDINATOR;
import static org.apache.ignite.internal.managers.communication.GridIoPolicy.SYSTEM_POOL;

/**
 *
 */
public class CacheCoordinatorsSharedManager<K, V> extends GridCacheSharedManagerAdapter<K, V> {
    /** */
    private final AtomicLong mvccCntr = new AtomicLong(0L);

    /** */
    private final AtomicLong committedCntr = new AtomicLong(0L);

    /** */
    private final ConcurrentHashMap<GridCacheVersion, Long> activeTxs = new ConcurrentHashMap<>();

    /** */
    private final ConcurrentMap<Long, MvccCounterFuture> cntrFuts = new ConcurrentHashMap<>();

    /** */
    private final ConcurrentMap<Long, TxAckFuture> ackFuts = new ConcurrentHashMap<>();

    /** */
    private final AtomicLong futIdCntr = new AtomicLong();

    /** {@inheritDoc} */
    @Override protected void start0() throws IgniteCheckedException {
        super.start0();

        cctx.gridEvents().addLocalEventListener(new CacheCoordinatorDiscoveryListener(),
            EVT_NODE_FAILED, EVT_NODE_LEFT);

        cctx.gridIO().addMessageListener(TOPIC_CACHE_COORDINATOR, new CoordinatorMessageListener());
    }

    /**
     * @param crd Coordinator.
     * @param txId Transaction ID.
     * @return Counter request future.
     */
    public IgniteInternalFuture<Long> requestTxCounter(ClusterNode crd, GridCacheVersion txId) {
        MvccCounterFuture fut = new MvccCounterFuture(futIdCntr.incrementAndGet(), crd);

        cntrFuts.put(fut.id, fut);

        try {
            cctx.gridIO().sendToGridTopic(crd,
                TOPIC_CACHE_COORDINATOR,
                new CoordinatorTxCounterRequest(fut.id, txId),
                SYSTEM_POOL);
        }
        catch (IgniteCheckedException e) {
            if (cntrFuts.remove(fut.id) != null)
                fut.onDone(e);
        }

        return fut;
    }

    public IgniteInternalFuture<Long> requestQueryCounter(ClusterNode crd) {
        MvccCounterFuture fut = new MvccCounterFuture(futIdCntr.incrementAndGet(), crd);

        cntrFuts.put(fut.id, fut);

        try {
            cctx.gridIO().sendToGridTopic(crd,
                TOPIC_CACHE_COORDINATOR,
                new CoordinatorQueryCounterRequest(fut.id),
                SYSTEM_POOL);
        }
        catch (IgniteCheckedException e) {
            if (cntrFuts.remove(fut.id) != null)
                fut.onDone(e);
        }

        return fut;
    }

    /**
     * @param txId Transaction ID.
     * @return Acknowledge future.
     */
    public IgniteInternalFuture<Void> ackTxCommit(ClusterNode crd, GridCacheVersion txId) {
        TxAckFuture fut = new TxAckFuture(futIdCntr.incrementAndGet(), crd);

        ackFuts.put(fut.id, fut);

        try {
            cctx.gridIO().sendToGridTopic(crd,
                TOPIC_CACHE_COORDINATOR,
                new CoordinatorTxAckRequest(fut.id, txId),
                SYSTEM_POOL);
        }
        catch (ClusterTopologyCheckedException e) {
            if (cntrFuts.remove(fut.id) != null)
                fut.onDone();
        }
        catch (IgniteCheckedException e) {
            if (cntrFuts.remove(fut.id) != null)
                fut.onDone(e);
        }

        return fut;
    }

    public void ackTxRollback(ClusterNode crd, GridCacheVersion txId) {
        CoordinatorTxAckRequest msg = new CoordinatorTxAckRequest(0, txId);

        msg.skipResponse(true);

        try {
            cctx.gridIO().sendToGridTopic(crd,
                TOPIC_CACHE_COORDINATOR,
                msg,
                SYSTEM_POOL);
        }
        catch (ClusterTopologyCheckedException e) {
            if (log.isDebugEnabled())
                log.debug("Failed to send tx rollback ack, node left [msg=" + msg + ", node=" + crd.id() + ']');
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send tx rollback ack [msg=" + msg + ", node=" + crd.id() + ']', e);
        }
    }


    /**
     * @param txId Transaction ID.
     * @return Counter.
     */
    private long assignTxCounter(GridCacheVersion txId) {
        long nextCtr = mvccCntr.incrementAndGet();

        Object old = activeTxs.put(txId, nextCtr);

        assert old == null : txId;

        return nextCtr;
    }

    /**
     * @param nodeId Sender node ID.
     * @param msg Message.
     */
    private void processCoordinatorTxCounterRequest(UUID nodeId, CoordinatorTxCounterRequest msg) {
        ClusterNode node = cctx.discovery().node(nodeId);

        if (node == null) {
            if (log.isDebugEnabled())
                log.debug("Ignore tx counter request processing, node left [msg=" + msg + ", node=" + nodeId + ']');

            return;
        }

        long nextCtr = assignTxCounter(msg.txId());

        try {
            cctx.gridIO().sendToGridTopic(node,
                TOPIC_CACHE_COORDINATOR,
                new CoordinatorMvccCounterResponse(nextCtr, msg.futureId()),
                SYSTEM_POOL);
        }
        catch (ClusterTopologyCheckedException e) {
            if (log.isDebugEnabled())
                log.debug("Failed to send tx counter response, node left [msg=" + msg + ", node=" + nodeId + ']');
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send tx counter response [msg=" + msg + ", node=" + nodeId + ']', e);
        }
    }

    /**
     * @param nodeId Sender node ID.
     * @param msg Message.
     */
    private void processCoordinatorCounterResponse(UUID nodeId, CoordinatorMvccCounterResponse msg) {
        MvccCounterFuture fut = cntrFuts.remove(msg.futureId());

        if (fut != null)
            fut.onResponse(msg.counter());
        else {
            if (cctx.discovery().alive(nodeId))
                U.warn(log, "Failed to find coordinator counter future [node=" + nodeId + ", msg=" + msg + ']');
            else if (log.isDebugEnabled())
                log.debug("Failed to find query counter future [node=" + nodeId + ", msg=" + msg + ']');
        }
    }
    /**
     *
     * @param nodeId Sender node ID.
     * @param msg Message.
     */
    private void processCoordinatorQueryStateRequest(UUID nodeId, CoordinatorQueryCounterRequest msg) {
        ClusterNode node = cctx.discovery().node(nodeId);

        if (node == null) {
            if (log.isDebugEnabled())
                log.debug("Ignore query counter request processing, node left [msg=" + msg + ", node=" + nodeId + ']');

            return;
        }

        long qryCntr = assignQueryCounter(nodeId);

        CoordinatorMvccCounterResponse res = new CoordinatorMvccCounterResponse(msg.futureId(), qryCntr);

        try {
            cctx.gridIO().sendToGridTopic(node,
                TOPIC_CACHE_COORDINATOR,
                res,
                SYSTEM_POOL);
        }
        catch (ClusterTopologyCheckedException e) {
            if (log.isDebugEnabled())
                log.debug("Failed to send query counter response, node left [msg=" + msg + ", node=" + nodeId + ']');

            onQueryDone(qryCntr);
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send query counter response [msg=" + msg + ", node=" + nodeId + ']', e);

            onQueryDone(qryCntr);
        }
    }

    /**
     * @param msg Message.
     */
    private void processCoordinatorQueryAckRequest(CoordinatorQueryAckRequest msg) {
        onQueryDone(msg.counter());
    }

    /**
     * @param nodeId Sender node ID.
     * @param msg Message.
     */
    private void processCoordinatorTxAckRequest(UUID nodeId, CoordinatorTxAckRequest msg) {
        activeTxs.remove(msg.txId());

        if (!msg.skipResponse()) {
            try {
                cctx.gridIO().sendToGridTopic(nodeId,
                    TOPIC_CACHE_COORDINATOR,
                    new CoordinatorTxAckResponse(msg.futureId()),
                    SYSTEM_POOL);
            }
            catch (ClusterTopologyCheckedException e) {
                if (log.isDebugEnabled())
                    log.debug("Failed to send tx ack response, node left [msg=" + msg + ", node=" + nodeId + ']');
            }
            catch (IgniteCheckedException e) {
                U.error(log, "Failed to send tx ack response [msg=" + msg + ", node=" + nodeId + ']', e);
            }
        }
    }

    /**
     * @param nodeId Sender node ID.
     * @param msg Message.
     */
    private void processCoordinatorTxAckResponse(UUID nodeId, CoordinatorTxAckResponse msg) {
        TxAckFuture fut = ackFuts.get(msg.futureId());

        if (fut != null)
            fut.onResponse();
        else {
            if (cctx.discovery().alive(nodeId))
                U.warn(log, "Failed to find tx ack future [node=" + nodeId + ", msg=" + msg + ']');
            else if (log.isDebugEnabled())
                log.debug("Failed to find tx ack future [node=" + nodeId + ", msg=" + msg + ']');
        }
    }

    /**
     * @param qryNodeId Node initiated query.
     * @return Counter for query.
     */
    private long assignQueryCounter(UUID qryNodeId) {
        // TODO IGNITE-3478
        return committedCntr.get();
    }

    /**
     * @param cntr Query counter.
     */
    private void onQueryDone(long cntr) {
        // TODO IGNITE-3478
    }

    /**
     * @param discoCache Cluster topology.
     * @return Assigned coordinator.
     */
    @Nullable public ClusterNode assignCoordinator(DiscoCache discoCache) {
        // TODO IGNITE-3478
        List<ClusterNode> srvNodes = discoCache.serverNodes();

        return srvNodes.isEmpty() ? null : srvNodes.get(0);
    }

    /**
     *
     */
    private class MvccCounterFuture extends GridFutureAdapter<Long> {
        /** */
        private final Long id;

        /** */
        private final ClusterNode crd;

        /**
         * @param id Future ID.
         * @param crd Coordinator.
         */
        MvccCounterFuture(Long id, ClusterNode crd) {
            this.id = id;
            this.crd = crd;
        }

        /**
         * @param cntr Counter.
         */
        void onResponse(long cntr) {
            onDone(cntr);
        }

        /**
         * @param nodeId Failed node ID.
         */
        void onNodeLeft(UUID nodeId) {
            if (crd.id().equals(nodeId) && cntrFuts.remove(id) != null)
                onDone(new ClusterTopologyCheckedException("Failed to request counter, node failed: " + nodeId));
        }
    }

    /**
     *
     */
    private class TxAckFuture extends GridFutureAdapter<Void> {
        /** */
        private final Long id;

        /** */
        private final ClusterNode crd;

        /**
         * @param id Future ID.
         * @param crd Coordinator.
         */
        TxAckFuture(Long id, ClusterNode crd) {
            this.id = id;
            this.crd = crd;
        }

        /**
         *
         */
        void onResponse() {
            onDone();
        }

        /**
         * @param nodeId Failed node ID.
         */
        void onNodeLeft(UUID nodeId) {
            if (crd.id().equals(nodeId) && cntrFuts.remove(id) != null)
                onDone();
        }
    }

    /**
     *
     */
    private class CacheCoordinatorDiscoveryListener implements GridLocalEventListener {
        /** {@inheritDoc} */
        @Override public void onEvent(Event evt) {
            assert evt instanceof DiscoveryEvent : evt;

            DiscoveryEvent discoEvt = (DiscoveryEvent)evt;

            UUID nodeId = discoEvt.eventNode().id();

            for (MvccCounterFuture fut : cntrFuts.values())
                fut.onNodeLeft(nodeId);

//            for (AckFuture fut : ackFuts.values())
//                fut.onNodeLeft(nodeId);
//
        }
    }
    /**
     *
     */
    private class CoordinatorMessageListener implements GridMessageListener {
        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
            if (msg instanceof CoordinatorTxCounterRequest)
                processCoordinatorTxCounterRequest(nodeId, (CoordinatorTxCounterRequest)msg);
            else if (msg instanceof CoordinatorMvccCounterResponse)
                processCoordinatorCounterResponse(nodeId, (CoordinatorMvccCounterResponse)msg);
            else if (msg instanceof CoordinatorTxAckRequest)
                processCoordinatorTxAckRequest(nodeId, (CoordinatorTxAckRequest)msg);
            else if (msg instanceof CoordinatorTxAckResponse)
                processCoordinatorTxAckResponse(nodeId, (CoordinatorTxAckResponse)msg);
            else if (msg instanceof CoordinatorQueryAckRequest)
                processCoordinatorQueryAckRequest((CoordinatorQueryAckRequest)msg);
            else if (msg instanceof CoordinatorQueryCounterRequest)
                processCoordinatorQueryStateRequest(nodeId, (CoordinatorQueryCounterRequest)msg);
            else
                U.warn(log, "Unexpected message received: " + msg);
        }
    }
}
