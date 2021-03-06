/*
 * Bitronix Transaction Manager
 *
 * Copyright (c) 2012, Bitronix Software.
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

package bitronix.tm.resource.jdbc.proxy;

import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.util.Map;

import bitronix.tm.resource.jdbc.JdbcPooledConnection;

/**
 * @author Brett Wooldridge
 */
public class CallableStatementJavaProxy extends JavaProxyBase<CallableStatement> {

    private final static Map<String, Method> selfMethodMap = createMethodMap(CallableStatementJavaProxy.class);

    private JdbcPooledConnection jdbcPooledConnection;
    
    public CallableStatementJavaProxy() {
        // Default constructor
    }

    public CallableStatementJavaProxy(JdbcPooledConnection jdbcPooledConnection, CallableStatement statement) {
        initialize(jdbcPooledConnection, statement);
    }

    void initialize(JdbcPooledConnection jdbcPooledConnection, CallableStatement statement) {
        this.jdbcPooledConnection = jdbcPooledConnection;
        this.delegate = statement;
    }

    /* Overridden methods of java.sql.CallableStatement */

    public void close() throws SQLException {
        if (delegate == null) {
            return;
        }

        jdbcPooledConnection.unregisterUncachedStatement(delegate);
        delegate.close();
    }

    @Override
    protected Map<String, Method> getMethodMap() {
        return selfMethodMap;
    }
}
