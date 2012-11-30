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

/**
 * The resource state
 * 
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 */
public enum ResourceState {
    /**
     * For a job only: the job is waiting dependencies to be met
     */
    WAITING,

    /**
	 * For a job only: the job is waiting for an available thread to launch it
	 */
	READY,

	/**
	 *  For a job only: The job is currently running
	 */
	RUNNING, 
	
	/**
	 * The job is on hold
	 */
	ON_HOLD,
	
	/**
	 * The job ran but did not complete or the data was not generated
	 */
	ERROR,

	/**
	 * Completed (for a job) or generated (for a data resource) 
	 */
	DONE;


    /**
     * Returns true if the resource is a state that will not change
     *
     * @return
     */
    public boolean isBlocking() {
        return this == ON_HOLD || this == ERROR;
    }

}
