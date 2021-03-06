/*
 * Copyright 2015-2020 Real Logic Limited, Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.AgentInvoker;
import uk.co.real_logic.artio.FixCounters;
import uk.co.real_logic.artio.GatewayProcess;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.StreamInformation;
import uk.co.real_logic.artio.engine.framer.FramerContext;
import uk.co.real_logic.artio.engine.framer.LibraryInfo;
import uk.co.real_logic.artio.engine.framer.PruneOperation;
import uk.co.real_logic.artio.timing.EngineTimers;

import java.io.File;
import java.util.List;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static uk.co.real_logic.artio.dictionary.generation.Exceptions.closeAll;
import static uk.co.real_logic.artio.dictionary.generation.Exceptions.suppressingClose;

/**
 * A FIX Engine is a process in the gateway that accepts or initiates FIX connections and
 * hands them off to different FixLibrary instances. The engine can replicate and/or durably
 * store streams2 of FIX messages for replay, archival, administrative or analytics purposes.
 * <p>
 * Each engine can have one or more associated libraries that manage sessions and perform business
 * logic. These may run in the same JVM process or a different JVM process.
 *
 * @see uk.co.real_logic.artio.library.FixLibrary
 */
public final class FixEngine extends GatewayProcess
{
    private static final Object CLOSE_MUTEX = new Object();

    public static final int ENGINE_LIBRARY_ID = 0;

    private final EngineTimers timers;
    private final EngineConfiguration configuration;
    private final RecordingCoordinator recordingCoordinator;

    private EngineScheduler scheduler;
    private FramerContext framerContext;
    private EngineContext engineContext;

    private volatile boolean startingClose = false;
    private volatile boolean isClosed = false;

    private final Object resetStateLock = new Object();
    private volatile boolean stateHasBeenReset = false;

    /**
     * Launch the engine. This method starts up the engine threads and then returns.
     *
     * @param configuration the configuration to use for this engine.
     * @return the new FIX engine instance.
     */
    public static FixEngine launch(final EngineConfiguration configuration)
    {
        synchronized (CLOSE_MUTEX)
        {
            configuration.conclude();

            return new FixEngine(configuration).launch();
        }
    }

    /**
     * Query the engine for the list of libraries currently active.
     *
     * If the reply is <code>null</code> then the query hasn't been enqueued and the operation
     * should be retried on a duty cycle.
     *
     * @return a list of currently active libraries.
     */
    public Reply<List<LibraryInfo>> libraries()
    {
        return framerContext.libraries();
    }

    /**
     * Unbinds the acceptor socket. This does not disconnect any currently connected TCP connections.
     *
     * If the reply is <code>null</code> then the query hasn't been enqueued and the operation
     * should be retried on a duty cycle.
     *
     * @return the reply object, or null if the request hasn't been successfully enqueued.
     */
    public Reply<?> unbind()
    {
        return framerContext.bind(false);
    }

    /**
     * Binds the acceptor socket to the configured address. This only needs to be called if you had called
     * {@link #unbind()} previously - {@link FixEngine#launch()} will bind the socket by default.
     *
     * If the reply is <code>null</code> then the query hasn't been enqueued and the operation
     * should be retried on a duty cycle.
     *
     * @return the reply object, or null if the request hasn't been successfully enqueued.
     */
    public Reply<?> bind()
    {
        return framerContext.bind(true);
    }

    /**
     * Resets the set of session ids.
     *
     * @param backupLocation the location to backup the current session ids file to.
     *                       Can be null to indicate that no backup is required.
     * @return the reply object, or null if the request hasn't been successfully enqueued.
     */
    public Reply<?> resetSessionIds(final File backupLocation)
    {
        return framerContext.resetSessionIds(backupLocation);
    }

    /**
     * Resets the sequence number of a given session. Asynchronous method, the Reply instance
     * needs to be polled to ensure that it has completed.
     *
     * If the reply is <code>null</code> then the query hasn't been enqueued and the operation
     * should be retried on a duty cycle.
     *
     * @param sessionId the id of the session that you want to reset
     *
     * @return the reply object, or null if the request hasn't been successfully enqueued.
     */
    public Reply<?> resetSequenceNumber(final long sessionId)
    {
        return framerContext.resetSequenceNumber(sessionId);
    }

    /**
     * This method resets the state of the of the FixEngine that also performs usual end of day processing
     * operations. It must can only be called when the FixEngine object has been closed. These are:
     *
     * <ol>
     *     <li>Reset and optionally back up all Artio state (including session ids and sequence numbers</li>
     *     <li>Truncate any recordings associated with this engine instance.</li>
     * </ol>
     *
     * Blocks until the operation is complete.
     *
     * @param backupLocation the directory that you wish to copy Artio's session state over to for later inspection.
     *                       If this is null no backup of data will be performed. If the directory exists it will be
     *                       re-used, if it doesn't it will be created.
     *
     * @throws IllegalStateException if this <code>FixEngine</code> hasn't been closed when this method is called.
     */
    public void resetState(final File backupLocation)
    {
        if (!isClosed())
        {
            throw new IllegalStateException("Engine should be closed before the state is reset");
        }

        synchronized (resetStateLock)
        {
            if (!stateHasBeenReset)
            {
                final ResetArchiveState resetArchiveState = new ResetArchiveState(
                    configuration, backupLocation, recordingCoordinator);
                resetArchiveState.resetState();

                stateHasBeenReset = true;
            }
        }
    }


    /**
     * Gets session info for all sessions the FixEngine is aware of including offline ones.
     * Can be used to acquire offline sessions or for administration purposes.
     * The returned list is updated in a thread-safe manner when new sessions are created.
     *
     * @return the list of session infos.
     */
    public List<SessionInfo> allSessions()
    {
        return framerContext.allSessions();
    }

    /**
     * Gets the session id associated with some combination of id fields
     *
     * @param localCompId the senderCompId of messages sent by the gateway on this session.
     * @param remoteCompId the senderCompId of messages received by the gateway on this session.
     * @param localSubId the senderSubId of messages sent by the gateway on this session
     *                   or <code>null</code> if not used in session identification.
     * @param remoteSubId the senderSubId of messages received by the gateway on this session
     *                    or <code>null</code> if not used in session identification.
     * @param localLocationId the senderLocationId of messages sent by the gateway on this session
     *                        or <code>null</code> if not used in session identification.
     * @param remoteLocationId the senderLocationId of messages received by the gateway on this session
     *                         or <code>null</code> if not used in session identification.
     *
     * @return the reply object asynchronously wrapping the session id
     */
    public Reply<Long> lookupSessionId(
        final String localCompId,
        final String remoteCompId,
        final String localSubId,
        final String remoteSubId,
        final String localLocationId,
        final String remoteLocationId)
    {
        return framerContext.lookupSessionId(
            localCompId, remoteCompId, localSubId, remoteSubId, localLocationId, remoteLocationId);
    }

    private FixEngine(final EngineConfiguration configuration)
    {
        try
        {
            this.configuration = configuration;

            timers = new EngineTimers(configuration.clock());
            scheduler = configuration.scheduler();
            scheduler.configure(configuration.aeronContext());
            init(configuration);
            final AeronArchive.Context archiveContext = configuration.aeronArchiveContext();
            final AeronArchive aeronArchive =
                configuration.logAnyMessages() ? AeronArchive.connect(archiveContext.aeron(aeron)) : null;
            recordingCoordinator = new RecordingCoordinator(
                aeron,
                aeronArchive,
                configuration,
                configuration.archiverIdleStrategy(),
                errorHandler);

            final ExclusivePublication replayPublication = replayPublication();
            engineContext = new EngineContext(
                configuration,
                errorHandler,
                replayPublication,
                fixCounters,
                aeron,
                aeronArchive,
                recordingCoordinator);
            initFramer(configuration, fixCounters, replayPublication.sessionId());
            initMonitoringAgent(timers.all(), configuration, aeronArchive);
        }
        catch (final Exception e)
        {
            if (engineContext != null)
            {
                engineContext.completeDuringStartup();
            }

            suppressingClose(this, e);

            throw e;
        }
    }

    private ExclusivePublication replayPublication()
    {
        final ExclusivePublication publication = aeron.addExclusivePublication(
            IPC_CHANNEL, configuration.outboundReplayStream());
        StreamInformation.print("replayPublication", publication, configuration);
        return publication;
    }

    private void initFramer(
        final EngineConfiguration configuration, final FixCounters fixCounters, final int replaySessionId)
    {
        framerContext = new FramerContext(
            configuration,
            fixCounters,
            engineContext,
            errorHandler,
            replayImage("replay", replaySessionId),
            replayImage("slow-replay", replaySessionId),
            timers,
            aeron.conductorAgentInvoker(),
            recordingCoordinator
        );

        engineContext.framerContext(framerContext);
    }

    private Image replayImage(final String name, final int replaySessionId)
    {
        final Subscription subscription = aeron.addSubscription(
            IPC_CHANNEL, configuration.outboundReplayStream());
        StreamInformation.print(name, subscription, configuration);

        // Await replay publication
        while (true)
        {
            final Image image = subscription.imageBySessionId(replaySessionId);
            if (image != null)
            {
                return image;
            }

            invokeAeronConductor();

            Thread.yield();
        }
    }

    // To be invoked by called called before a scheduler has launched
    private void invokeAeronConductor()
    {
        final AgentInvoker invoker = aeron.conductorAgentInvoker();
        if (invoker != null)
        {
            invoker.invoke();
        }
    }

    private FixEngine launch()
    {
        scheduler.launch(
            configuration,
            errorHandler,
            framerContext.framer(),
            engineContext.indexingAgent(),
            monitoringAgent,
            conductorAgent(),
            recordingCoordinator);

        return this;
    }

    /**
     * Close the engine down, including stopping other running threads. This also stops accepting new connections, and
     * logs out and disconnects all currently active FIX sessions.
     *
     * NB: graceful shutdown of the FixEngine will wait for logouts to occur. This entails communicating with all
     * <code>FixLibrary</code> instances currently live in order for them to gracefully close as well. Therefore if you
     * close a <code>FixLibrary</code> before you call this method then the close operation could be delayed by up to
     * {@link uk.co.real_logic.artio.CommonConfiguration#replyTimeoutInMs()} in order for the <code>FixEngine</code>
     * to timeout the <code>FixLibrary</code>.
     *
     * This does not remove files associated with the engine, that are persistent
     * over multiple runs of the engine.
     */
    public void close()
    {
        synchronized (CLOSE_MUTEX)
        {
            if (!isClosed)
            {
                startingClose = true;

                framerContext.startClose();

                closeAll(scheduler, engineContext, configuration, super::close);

                isClosed = true;
            }
        }
    }

    /**
     * Find out whether the {@link #close()} operation has been called.
     *
     * @return true if the {@link #close()} operation has been called, false otherwise.
     */
    public boolean isClosed()
    {
        return isClosed;
    }

    /**
     * Frees up space from the Aeron archive of messages. This operation does not remove all entries from the Aeron
     * archive logs: only entries that are not part of the latest sequence index. That means that resend requests for
     * the current sequence index can still be processed.
     *
     * Archive logs are pruned in chunks called segments - see the Aeron Archiver documentation for
     * details. This means that if there are less than a segment's worth of messages that can be
     * freed up then no space is pruned.
     *
     * @param recordingIdToMinimumPrunePositions the minimum positions to prune or <code>null</code> otherwise.
     *                                           If you're archiving segments of the
     *                                           Aeron archive log then this parameter can be used in order to stop
     *                                           those segments from being removed. The hashmap should be initialised
     *                                           with <code>new Long2LongHashMap(Aeron.NULL_VALUE)</code>.
     * @return the positions pruned up to. This is a map from recording id to a pruned position if pruning has occurred.
     *         It may be empty if no recordings have been pruned. <code>Aeron.NULL_VALUE</code> is used to denote
     *         missing values in the map.
     */
    public Reply<Long2LongHashMap> pruneArchive(final Long2LongHashMap recordingIdToMinimumPrunePositions)
    {
        if (startingClose)
        {
            return new PruneOperation(new IllegalStateException("Unable to prune archive during shutdown."));
        }

        if (isClosed)
        {
            return new PruneOperation(new IllegalStateException("Unable to prune archive when closed."));
        }

        return engineContext.pruneArchive(recordingIdToMinimumPrunePositions);
    }

    public EngineConfiguration configuration()
    {
        return configuration;
    }
}
