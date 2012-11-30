/*
 * This file is part of experimaestro.
 * Copyright (c) 2012 B. Piwowarski <benjamin@bpiwowar.net>
 *
 * experimaestro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * experimaestro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with experimaestro.  If not, see <http://www.gnu.org/licenses/>.
 */

package sf.net.experimaestro.scheduler;

import com.google.common.collect.HashMultiset;
import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Transaction;
import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.SecondaryIndex;
import org.apache.commons.lang.NotImplementedException;
import sf.net.experimaestro.manager.DotName;
import sf.net.experimaestro.utils.Heap;
import sf.net.experimaestro.utils.Trie;
import sf.net.experimaestro.utils.log.Logger;

import java.util.ArrayList;
import java.util.TreeMap;

import static com.sleepycat.je.CursorConfig.READ_UNCOMMITTED;

/**
 * A set of resources
 *
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 */
public class Resources extends CachedEntitiesStore<ResourceLocator, Resource> {
    final static private Logger LOGGER = Logger.getLogger();


    /**
     * The associated scheduler
     */
    private final Scheduler scheduler;

    /**
     * The groups the resources belong to
     */
    private final SecondaryIndex<String, ResourceLocator, Resource> groups;

    /**
     * The dependencies
     */
    private PrimaryIndex<Long, Dependency> dependencies;

    /**
     * Access to the depencies
     */
    private SecondaryIndex<ResourceLocator, Long, Dependency> dependenciesFrom;
    private SecondaryIndex<ResourceLocator, Long, Dependency> dependenciesTo;

    /**
     * The job states
     */
    private SecondaryIndex<ResourceState, ResourceLocator, Resource> states;

    /**
     * A Trie
     */
    Trie<String, DotName> groupsTrie = new Trie<>();

    /**
     * Initialise the set of resources
     *
     * @param scheduler The scheduler
     * @param readyJobs A heap where the resources that are ready will be placed during the initialisation
     * @param dbStore
     * @throws DatabaseException
     */
    public Resources(Scheduler scheduler, EntityStore dbStore, Heap<Job> readyJobs)
            throws DatabaseException {
        super(dbStore.getPrimaryIndex(ResourceLocator.class, Resource.class));

        groups = dbStore.getSecondaryIndex(index, String.class, Resource.GROUP_KEY_NAME);
        dependencies = dbStore.getPrimaryIndex(Long.class, Dependency.class);
        dependenciesFrom = dbStore.getSecondaryIndex(dependencies, ResourceLocator.class, Dependency.FROM_KEY_NAME);
        dependenciesTo = dbStore.getSecondaryIndex(dependencies, ResourceLocator.class, Dependency.TO_KEY_NAME);
        states = dbStore.getSecondaryIndex(index, ResourceState.class, Resource.STATE_KEY_NAME);

        this.scheduler = scheduler;
    }

    void init(Heap<Job> readyJobs) {
        // Get the groups
        final EntityCursor<String> keys = groups.keys();
        for (String key : keys)
            groupsTrie.put(DotName.parse(key));
        keys.close();

        update(ResourceState.RUNNING, readyJobs);
        update(ResourceState.READY, readyJobs);

        // Now, update waiting tasks (so they can be ready if a job finished but we did not
        // get notified)
        update(ResourceState.WAITING, readyJobs);
    }

    // Update resources in a given state
    private void update(ResourceState state, Heap<Job> readyJobs) {
        final EntityCursor<Resource> cursor = states.entities(null, state, true, state, true, CursorConfig.READ_UNCOMMITTED);
        for (Resource resource : cursor) {
            resource.init(scheduler);
            try {
                if (resource.updateStatus(false))
                    put(null, resource);

                switch(resource.getState()) {
                    case READY:
                        readyJobs.add((Job) resource);
                    case RUNNING:
                        super.cache(resource);
                }

            } catch (Exception e) {
                LOGGER.error(e, "Error while updating resource %s", resource);
            }
        }
    }



    @Override
    public synchronized boolean put(Transaction txn, Resource resource) throws DatabaseException {
        return put(txn, resource, false);
    }

    public synchronized boolean put(Transaction txn, Resource resource, boolean fullStore) throws DatabaseException {
        // Get the group
        groupsTrie.put(DotName.parse(resource.getGroup()));

        // Starts the transaction
        if (!super.put(txn, resource))
            return false;

        if (fullStore && resource.retrievedDependencies()) {
            // Delete everything
            dependenciesFrom.delete(txn, resource.getLocator());

            // Store the dependencies
            for (Dependency dependency : resource.getDependencies()) {
                dependencies.put(txn, dependency);
            }
        }

        return true;
    }

    @Override
    protected boolean canOverride(Resource old, Resource resource) {
        if (old.state == ResourceState.RUNNING && resource != old) {
            LOGGER.error(String.format("Cannot override a running task [%s] / %s vs %s", resource.locator,
                    resource.hashCode(), old.hashCode()));
            return false;
        }
        return true;
    }


    @Override
    protected ResourceLocator getKey(Resource resource) {
        return new ResourceLocator(resource.getLocator());
    }

    @Override
    protected void init(Resource resource) {
        resource.init(scheduler);
    }


    public Iterable<Resource> fromGroup(final String group, boolean recursive) {
        if (!recursive)
            return groups.entities(group, true, group, true);

        throw new NotImplementedException();
    }

    /**
     * Returns subgroups
     *
     * @param group
     * @return
     */
    public HashMultiset<String> subgroups(String group) {
        System.err.format("Searching children of group [%s]%n", group);
        final Trie<String, DotName> node = groupsTrie.find(DotName.parse(group));
        final HashMultiset<String> set = HashMultiset.create();
        if (node == null)
            return set;

        System.err.format("Found a node%n");
        for (String key : node.childrenKeys())
            set.add(key);

        return set;
    }

    /**
     * Notify the dependencies that a resource has changed
     *
     * @param resource The resource that has changed
     */
    public void notifyDependencies(Resource resource) {
        // Join between active states
        // TODO: should limit to the dependencies of some resources
        final ResourceLocator from = resource.getLocator();

        // Get all the dependencies
        ArrayList<Dependency> dependencies = new ArrayList<>();
        try (final EntityCursor<Dependency> entities = dependenciesFrom.entities(null, from, true, from, true, READ_UNCOMMITTED)) {
            Dependency dep;
            while ((dep = entities.next()) != null)
                dependencies.add(dep);
        }

        // Notify each of these
        for (Dependency dependency : dependencies) {
            if (dependency.state != resource.state) {
                Resource dep = get(dependency.getTo());
                if (dep == null)
                    LOGGER.warn("Dependency [%s] of [%s] was not found", from, dependency.getTo());
                else {
                    LOGGER.info("Notifying dependency [%s] from [%s]", from, dependency.getTo());
                    dep.notify(resource);
                }
            }
        }
    }


    public TreeMap<ResourceLocator, Dependency> retrieveDependencies(ResourceLocator locator) {
        TreeMap<ResourceLocator, Dependency> deps = new TreeMap<>();
        try (final EntityCursor<Dependency> entities = dependenciesTo.entities(locator, true, locator, true)) {
            for (Dependency dependency : entities)
                deps.put(dependency.getFrom(), dependency);
        }
        return deps;
    }
}
