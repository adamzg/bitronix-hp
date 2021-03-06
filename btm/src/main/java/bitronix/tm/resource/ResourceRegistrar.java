/*
 * Bitronix Transaction Manager
 *
 * Copyright (c) 2010, Bitronix Software.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA 02110-1301 USA
 */
package bitronix.tm.resource;

import bitronix.tm.TransactionManagerServices;
import bitronix.tm.recovery.IncrementalRecoverer;
import bitronix.tm.recovery.RecoveryException;
import bitronix.tm.resource.common.XAResourceHolder;
import bitronix.tm.resource.common.XAResourceProducer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.xa.XAResource;

import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Collection of initialized {@link XAResourceProducer}s. All resources must be registered in the {@link ResourceRegistrar}
 * before they can be used by the transaction manager.
 * <p/>
 * Note: The implementation is based on a thread safe, read-optimized list (copy-on-write) assuming that the
 * number of registered resources is around 1 to 16 entries and does not change often. If this assumption is
 * not-true it may be required to re-implement this with a ConcurrentMap instead.
 *
 * @author lorban, jkellerer
 */
public final class ResourceRegistrar {

    private final static Logger log = LoggerFactory.getLogger(ResourceRegistrar.class);

    /**
     * Specifies the charset that unique names of resources must be encodable with to be storeable in a TX journal.
     */
    public final static String UNIQUE_NAME_CHARSET = "US-ASCII";

    private final static Set<ProducerHolder> resources = new CopyOnWriteArraySet<ProducerHolder>();

    /**
     * Get a registered {@link XAResourceProducer}.
     *
     * @param uniqueName the name of the recoverable resource producer.
     * @return the {@link XAResourceProducer} or null if there was none registered under that name.
     */
    public static XAResourceProducer get(final String uniqueName) {
        if (uniqueName != null) {
            for (ProducerHolder holder : resources) {
                if (!holder.isInitialized())
                    continue;
                if (uniqueName.equals(holder.getUniqueName()))
                    return holder.producer;
            }
        }
        return null;
    }

    /**
     * Get all {@link XAResourceProducer}s unique names.
     * @return a Set containing all {@link bitronix.tm.resource.common.XAResourceProducer}s unique names.
     */
    public static Set<String> getResourcesUniqueNames() {
        final Set<String> names = new HashSet<String>(resources.size());
        for (ProducerHolder holder : resources) {
            if (!holder.isInitialized())
                continue;
            names.add(holder.getUniqueName());
        }

        return Collections.unmodifiableSet(names);
    }

    /**
     * Register a {@link XAResourceProducer}. If registration happens after the transaction manager started, incremental
     * recovery is run on that resource.
     * @param producer the {@link XAResourceProducer}.
     * @throws RecoveryException When an error happens during recovery.
     */
    public static void register(XAResourceProducer producer) throws RecoveryException {
        try {
            final boolean alreadyRunning = TransactionManagerServices.isTransactionManagerRunning();
            final ProducerHolder holder = alreadyRunning ? new InitializableProducerHolder(producer) : new ProducerHolder(producer);

            if (resources.add(holder)) {
                if (holder instanceof InitializableProducerHolder) {
                    boolean recovered = false;
                    try {
                        if (log.isDebugEnabled()) { log.debug("Transaction manager is running, recovering resource '" + holder.getUniqueName() + "'."); }
                        IncrementalRecoverer.recover(producer);
                        ((InitializableProducerHolder) holder).initialize();
                        recovered = true;
                    } finally {
                        if (!recovered) { resources.remove(holder); }
                    }
                }
            } else {
                throw new IllegalStateException("A resource with uniqueName '" + holder.getUniqueName() + "' has already been registered. " +
                        "Cannot register XAResourceProducer '" + producer + "'.");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot register the XAResourceProducer '" + producer + "' caused by invalid input.", e);
        }
    }

    /**
     * Unregister a previously registered {@link XAResourceProducer}.
     * @param producer the {@link XAResourceProducer}.
     */
    public static void unregister(XAResourceProducer producer) {
        final ProducerHolder holder = new ProducerHolder(producer);

        if (!resources.remove(holder)) {
            if (log.isDebugEnabled()) { log.debug("resource with uniqueName '{}' has not been registered", holder.getUniqueName()); }
        }
    }

    /**
     * Find in the registered {@link XAResourceProducer}s the {@link XAResourceHolder} from which the specified {@link XAResource} comes from.
     *
     * @param xaResource the {@link XAResource} to look for
     * @return the associated {@link XAResourceHolder} or null if it cannot be found.
     */
    public static XAResourceHolder findXAResourceHolder(XAResource xaResource) {
        final boolean debug = log.isDebugEnabled();

        for (ProducerHolder holder : resources) {
            if (!holder.isInitialized())
                continue;

            final XAResourceProducer producer = holder.producer;
            final XAResourceHolder resourceHolder = producer.findXAResourceHolder(xaResource);
            if (resourceHolder != null) {
                if (debug) { log.debug("XAResource " + xaResource + " belongs to " + resourceHolder + " that itself belongs to " + producer); }
                return resourceHolder;
            }
            if (debug) { log.debug("XAResource " + xaResource + " does not belong to any resource of " + producer); }
        }

        return null;
    }

    private ResourceRegistrar() {
    }

    /**
     * Implements a holder that maintains XAResourceProducers in a set only differentiating them by their unique names.
     */
    private static class ProducerHolder {

        private final XAResourceProducer producer;

        private ProducerHolder(XAResourceProducer producer) {
            if (producer == null)
                throw new IllegalArgumentException("XAResourceProducer may not be 'null'. Verify your call to ResourceRegistrar.[un]register(...).");

            final String uniqueName = producer.getUniqueName();
            if (uniqueName == null || uniqueName.length() == 0)
                throw new IllegalArgumentException("The given XAResourceProducer '" + producer + "' does not specify a uniqueName.");

            try {
	            final String transcodedUniqueName = new String(uniqueName.getBytes(UNIQUE_NAME_CHARSET), UNIQUE_NAME_CHARSET);
	            if (!transcodedUniqueName.equals(uniqueName)) {
	                throw new IllegalArgumentException("The given XAResourceProducer's uniqueName '" + uniqueName + "' is not compatible with the charset " +
	                        "'US-ASCII' (transcoding results in '" + transcodedUniqueName + "'). " + System.getProperty("line.separator") +
	                        "BTM requires unique names to be compatible with US-ASCII when used with a transaction journal.");
	            }
            } catch (UnsupportedEncodingException e) {
            	log.error(UNIQUE_NAME_CHARSET + " encoding character set not found", e);
            }

            this.producer = producer;
        }

        boolean isInitialized() {
            return true;
        }

        String getUniqueName() {
            return producer.getUniqueName();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProducerHolder)) return false;
            ProducerHolder that = (ProducerHolder) o;
            return getUniqueName().equals(that.getUniqueName());
        }

        @Override
        public int hashCode() {
            return getUniqueName().hashCode();
        }

        @Override
        public String toString() {
            return "ProducerHolder{" +
                    "producer=" + producer +
                    ", initialized=" + isInitialized() +
                    '}';
        }
    }

    /**
     * Extends the default holder with thread safe initialization to put uninitialized holders in the set.
     */
    private static class InitializableProducerHolder extends ProducerHolder {

        private volatile boolean initialized;

        private InitializableProducerHolder(XAResourceProducer producer) {
            super(producer);
        }

        boolean isInitialized() {
            return initialized;
        }

        void initialize() {
            initialized = true;
        }
    }
}
