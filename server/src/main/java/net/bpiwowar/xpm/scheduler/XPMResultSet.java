/*
 *
 *  * This file is part of experimaestro.
 *  * Copyright (c) 2015 B. Piwowarski <benjamin@bpiwowar.net>
 *  *
 *  * experimaestro is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * experimaestro is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with experimaestro.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */

package net.bpiwowar.xpm.scheduler;

import net.bpiwowar.xpm.exceptions.WrappedSQLException;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * A wrapper for a result set
 */
public class XPMResultSet implements AutoCloseable {
    final ResultSet resultSet;

    public XPMResultSet(ResultSet resultSet) {
        this.resultSet = resultSet;
    }

    public boolean next() throws SQLException {
        return resultSet.next();
    }

    @Override
    public void close() throws SQLException {
        resultSet.close();
    }

    public InputStream getBinaryStream(int index) throws SQLException {
        return resultSet.getBinaryStream(index);
    }

    /**
     * Get the string for a given column
     *
     * @throws WrappedSQLException
     * @see ResultSet#getString(int)
     */
    public String getString(int columnIndex) throws WrappedSQLException {
        try {
            return resultSet.getString(columnIndex);
        } catch (SQLException e) {
            throw new WrappedSQLException(e);
        }
    }

    public Timestamp getTimeStamp(int columnIndex) {
        try {
            return resultSet.getTimestamp(columnIndex);
        } catch (SQLException e) {
            throw new WrappedSQLException(e);
        }
    }

    public long getLong(int columnIndex) {
        try {
            return resultSet.getLong(columnIndex);
        } catch (SQLException e) {
            throw new WrappedSQLException(e);
        }
    }

    public boolean isClosed() throws SQLException {
        return resultSet.isClosed();
    }

    public boolean wasNull() throws SQLException {
        return resultSet.wasNull();
    }

    public ResultSet get() {
        return resultSet;
    }
}
