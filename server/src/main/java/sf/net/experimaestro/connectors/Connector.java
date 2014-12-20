package sf.net.experimaestro.connectors;

/*
 * This file is part of experimaestro.
 * Copyright (c) 2014 B. Piwowarski <benjamin@bpiwowar.net>
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

import javax.persistence.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;

/**
 * This class represents any layer that can get between a host where files can be stored
 * and possibly where a command can be executed.
 * <p/>
 * Connectors are stored in the database so that they can be used
 *
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 */
@Entity
@Table(name = "connector")
@DiscriminatorColumn(name = "type", discriminatorType = DiscriminatorType.INTEGER)
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
public abstract class Connector implements Comparable<Connector> {
    /**
     * Each connector has a unique integer ID
     */
    @Id()
    String identifier;

    public Connector(String identifier) {
        this.identifier = identifier;
    }

    protected Connector() {
    }

    /**
     * Return a new connector from an URI
     */
    public static Connector create(String uriString, ConnectorOptions options) throws URISyntaxException {
        return create(new URI(uriString), options);
    }

    public static Connector create(URI uri, ConnectorOptions options) {
        switch (uri.getScheme()) {
            case "ssh":
                return new SSHConnector(uri, options);
            case "local":
            case "file":
                return new LocalhostConnector();
            default:
                throw new IllegalArgumentException("Unknown connector scheme: " + uri.getScheme());
        }
    }

    /**
     * Retrieves a connector with some requirements
     *
     * @return A valid connector or null if no connector meet the requirements
     */
    public abstract SingleHostConnector getConnector(ComputationalRequirements requirements);

    /**
     * Returns the main connector for this group
     *
     * @return A valid single host connector
     */
    public abstract SingleHostConnector getMainConnector();


//    /**
//     * Create a file with a thread safe mechanism
//     *
//     * @param path
//     * @return A lock object
//     * @throws LockException
//     */
//    public abstract Lock createLockFile(String path) throws LockException;
//

    /**
     * Returns true if the connector can execute commands
     */
    public boolean canExecute() {
        return false;
    }

    /**
     * Returns the connectorId identifier
     */
    public final String getIdentifier() {
        return identifier;
    }

    @Override
    final public int compareTo(Connector other) {
        return identifier.compareTo(other.identifier);
    }

    public ConnectorDelegator delegate() {
        return new ConnectorDelegator(this);
    }

    public abstract Path resolve(String path) throws FileSystemException;
}