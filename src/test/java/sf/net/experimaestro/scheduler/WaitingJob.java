/*
 * This file is part of experimaestro.
 * Copyright (c) 2013 B. Piwowarski <benjamin@bpiwowar.net>
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

import com.sleepycat.persist.model.Persistent;
import sf.net.experimaestro.connectors.LocalhostConnector;
import sf.net.experimaestro.connectors.SingleHostConnector;
import sf.net.experimaestro.connectors.XPMProcess;
import sf.net.experimaestro.locks.Lock;
import sf.net.experimaestro.utils.ThreadCount;
import sf.net.experimaestro.utils.log.Logger;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * A job that just waits a bit
 *
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 30/1/13
 */
@Persistent
public class WaitingJob extends Job<JobData> {
    final static private Logger LOGGER = Logger.getLogger();

    transient private ThreadCount counter;

    // When was the job ready to run
    long readyTimestamp = 0;

    // id for debugging
    private String id;

    // Duration before exiting
    long duration;

    // The code to return
    private int code;

    protected WaitingJob() {
    }


    @Override
    public String toString() {
        return id;
    }

    public WaitingJob(Scheduler scheduler, ThreadCount counter, File dir, String id, long duration, int code) {
        super(scheduler, new JobData(new ResourceLocator(LocalhostConnector.getInstance(), new File(dir, id).getAbsolutePath())));
        this.counter = counter;
        this.id = id;
        this.duration = duration;
        counter.add();
        this.code = code;

        // put ourselves in waiting mode (rather than ON HOLD default)
        this.state = ResourceState.WAITING;
    }


    @Override
    protected XPMProcess startJob(ArrayList<Lock> locks) throws Throwable {
        return new MyXPMProcess(counter, getMainConnector(), this, duration, code);
    }

    @Override
    public boolean canBeOverriden(Resource current) {
        // We don't want to be overriden
        return false;
    }

    @Override
    boolean storeState(boolean notify) {
        final boolean r = super.storeState(notify);
        if (!state.isActive() && counter != null) {
            LOGGER.debug("Releasing counter for job " + id);
            counter.del();
            counter = null;
        }
        return r;
    }

    @Override
    public synchronized void notify(Resource resource, Message message) {
        super.notify(resource, message);
        if (resource == null || this == resource) {
            if (message instanceof DependencyChangedMessage && state == ResourceState.READY) {
                readyTimestamp = System.currentTimeMillis();
            }
        }
    }

    @Override
    protected synchronized boolean doUpdateStatus(boolean store) throws Exception {
        final boolean b = super.doUpdateStatus(store);
        return b;
    }


    @Persistent
    static private class MyXPMProcess extends XPMProcess {
        private long timestamp;
        private long duration;
        private transient ThreadCount counter;
        int code;

        public MyXPMProcess() {

        }

        public MyXPMProcess(ThreadCount counter, SingleHostConnector connector, Job job, long duration, int code) {
            super(connector, "1", job, true);
            this.timestamp = System.currentTimeMillis();
            this.duration = duration;
            this.counter = counter;
            this.code = code;
        }

        @Override
        public void dispose() {
            super.dispose();
        }

        @Override
        public int waitFor() throws InterruptedException {
            synchronized (this) {
                long toWait = duration - (System.currentTimeMillis() - timestamp);
                if (toWait > 0) wait(toWait);
            }
            return code;
        }

        @Override
        public boolean isRunning() throws Exception {
            return duration < System.currentTimeMillis() - timestamp;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public OutputStream getOutputStream() {
            return null;
        }

        @Override
        public InputStream getErrorStream() {
            return null;
        }

        @Override
        public InputStream getInputStream() {
            return null;
        }

        @Override
        public void destroy() {
        }


    }
}